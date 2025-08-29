package com.ahold.tools.xsd2avro;

import org.apache.ws.commons.schema.*;
import javax.xml.namespace.QName;
import java.util.*;

public class XmlSchemaJsonGen {
    public static class Result {
        public final String rootName;
        public final String jsonCompact;
        public final String jsonPretty;
        public Result(String rootName, String jsonCompact, String jsonPretty) {
            this.rootName = rootName; this.jsonCompact = jsonCompact; this.jsonPretty = jsonPretty;
        }
    }

    private final OptionsCli cli;
    private final XmlSchemaIndexer idx;
    private final String namespace;

    public XmlSchemaJsonGen(OptionsCli cli) throws Exception {
        this.cli = cli;
        this.idx = new XmlSchemaIndexer(cli);
        this.namespace = idx.deriveNamespace();
    }

    public Result generate() {
        XmlSchemaElement root = idx.selectRootElement();
        String name = (cli.avroName != null && !cli.avroName.isBlank()) ? cli.avroName :
                (root.getName() != null ? root.getName() : "Record");
        String ns = (cli.namespace != null && !cli.namespace.isBlank()) ? cli.namespace : namespace;

        // Collect fields (unique names)
        List<Field> fields = elementToFields(root, new HashSet<>(), "");
        if (cli.flattenTop) {
            fields = flattenOneLevel(fields);
        }
        fields = unique(fields);

        String compact = toRecordJson(name, ns, fields, false);
        String pretty  = toRecordJson(name, ns, fields, true);
        return new Result(name, compact, pretty);
    }

    private static class Field {
        String name;
        Type type;
        Field(String n, Type t){ this.name=n; this.type=t; }
    }
    private static class Type {
        String primitive; // "string","int","long","float","double","boolean","bytes","record","array","enum","union"
        String logical;   // optional logical type name
        String name;      // for record/enum
        List<Field> fields; // for record
        List<String> symbols; // for enum
        Type items;       // for array
        boolean nullable;
    }

    private List<Field> elementToFields(XmlSchemaElement elem, Set<String> seenTypes, String prefix) {
        List<Field> res = new ArrayList<>();
        String fname = (elem.getName()!=null?elem.getName() :
                (elem.getRef()!=null && elem.getRef().getTargetQName()!=null
                        ? elem.getRef().getTargetQName().getLocalPart() : "field"));

        Type t = typeOf(elem);
        res.add(new Field(fname, t));
        return res;
    }

    private Type typeOf(XmlSchemaElement elem) {
        boolean isArray = elem.getMaxOccurs() > 1 || elem.getMaxOccurs() == Long.MAX_VALUE;
        boolean isOptional = elem.getMinOccurs() == 0;
        Type base;
        if (elem.getSchemaType() instanceof XmlSchemaComplexType ct) {
            base = complexToType(ct, elem.getName());
        } else if (elem.getSchemaTypeName()!=null) {
            base = simpleFromQName(elem.getSchemaTypeName());
        } else {
            base = simpleFromLocal("string");
        }
        if (isArray) {
            Type arr = new Type(); arr.primitive="array"; arr.items = unwrapNullable(base);
            base = arr;
        }
        if (isOptional) {
            base = wrapNullable(base);
        }
        return base;
    }

    private Type complexToType(XmlSchemaComplexType ct, String preferredName) {
        Type rec = new Type(); rec.primitive="record"; rec.name = preferredName!=null?preferredName:"Record";
        List<Field> fields = new ArrayList<>();
        // attributes
        if (ct.getAttributes()!=null) {
            for (Object o : ct.getAttributes()) {
                if (o instanceof XmlSchemaAttribute a) {
                    String fn = a.getName()!=null?a.getName():"attr";
                    Type at = (a.getSchemaTypeName()!=null? simpleFromQName(a.getSchemaTypeName()): simpleFromLocal("string"));
                    boolean required = a.getUse() == XmlSchemaUse.REQUIRED;
                    if (!required || cli.nullableAttrs) at = wrapNullable(at);
                    fields.add(new Field(fn, at));
                } else if (o instanceof XmlSchemaAttributeGroupRef agr) {
                    QName qn = (agr.getRef()!=null) ? agr.getRef().getTargetQName() : null;
                    if (qn!=null) { /* ignore deep traversal for speed */ }
                }
            }
        }
        // particle
        if (ct.getParticle() != null) {
            fields.addAll(particleToFields(ct.getParticle()));
        }
        rec.fields = fields;
        return rec;
    }

    private List<Field> particleToFields(XmlSchemaParticle p) {
        List<Field> out = new ArrayList<>();
        if (p instanceof XmlSchemaSequence seq) {
            for (Object o : seq.getItems()) {
                if (o instanceof XmlSchemaElement el) out.addAll(elementToFields(el, new HashSet<>(), ""));
                else if (o instanceof XmlSchemaChoice ch) out.add(new Field("choice", simpleFromLocal("string")));
                else if (o instanceof XmlSchemaGroupRef gr) out.add(new Field("group", simpleFromLocal("string")));
            }
        } else if (p instanceof XmlSchemaAll all) {
            for (Object o : all.getItems()) {
                if (o instanceof XmlSchemaElement el) out.addAll(elementToFields(el, new HashSet<>(), ""));
            }
        } else if (p instanceof XmlSchemaChoice ch) {
            out.add(new Field("choice", simpleFromLocal("string")));
        }
        return out;
    }

    private Type unwrapNullable(Type t) {
        if (t!=null && "union".equals(t.primitive) && t.items!=null) return t.items;
        return t;
    }
    private Type wrapNullable(Type t) {
        Type u = new Type(); u.primitive="union"; u.items = t; u.nullable = true; return u;
    }

    private Type simpleFromQName(QName qn) {
        if (qn==null) return simpleFromLocal("string");
        return simpleFromLocal(qn.getLocalPart());
    }
    private Type simpleFromLocal(String local) {
        Type t = new Type();
        switch (local) {
            case "string","normalizedString","token","anyURI","QName" -> t.primitive="string";
            case "boolean" -> t.primitive="boolean";
            case "float" -> t.primitive="float";
            case "double" -> t.primitive="double";
            case "decimal" -> t.primitive="string";
            case "integer","nonNegativeInteger","positiveInteger","nonPositiveInteger","negativeInteger","long","unsignedInt" -> t.primitive="long";
            case "int","short","byte","unsignedShort","unsignedByte" -> t.primitive="int";
            case "base64Binary","hexBinary" -> t.primitive="bytes";
            case "date","time","dateTime" -> t.primitive="string";
            default -> t.primitive="string";
        }
        return t;
    }

    private List<Field> unique(List<Field> in) {
        Map<String,Integer> seen = new LinkedHashMap<>();
        List<Field> out = new ArrayList<>();
        for (Field f : in) {
            String n = f.name;
            if (!seen.containsKey(n)) { seen.put(n,0); out.add(f); }
            else {
                int i = seen.get(n)+1; String nn = n+"_"+i;
                while (seen.containsKey(nn)) { i++; nn = n+"_"+i; }
                seen.put(n,i); seen.put(nn,0);
                out.add(new Field(nn, f.type));
            }
        }
        return out;
    }

    private List<Field> flattenOneLevel(List<Field> fields) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields) {
            if (f.type!=null && "record".equals(f.type.primitive) && f.type.fields!=null) {
                String prefix = Character.toLowerCase(f.name.charAt(0)) + f.name.substring(1);
                for (Field sf : f.type.fields) {
                    out.add(new Field(prefix + Character.toUpperCase(sf.name.charAt(0)) + sf.name.substring(1), sf.type));
                }
            } else out.add(f);
        }
        return out;
    }

    private String toRecordJson(String name, String ns, List<Field> fields, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        String ind = pretty ? "  " : "";
        String nl  = pretty ? "\n" : "";
        sb.append("{").append(nl);
        sb.append(ind).append("\"type\": \"record\",").append(nl);
        sb.append(ind).append("\"name\": \"").append(escape(name)).append("\",").append(nl);
        sb.append(ind).append("\"namespace\": \"").append(escape(ns)).append("\",").append(nl);
        sb.append(ind).append("\"fields\": [").append(nl);
        for (int i=0;i<fields.size();i++) {
            Field f = fields.get(i);
            sb.append(ind).append(ind).append("{").append(nl);
            sb.append(ind).append(ind).append(ind).append("\"name\": \"").append(escape(f.name)).append("\",").append(nl);
            sb.append(ind).append(ind).append(ind).append("\"type\": ").append(typeJson(f.type, pretty, ind)).append(nl);
            sb.append(ind).append(ind).append("}");
            if (i<fields.size()-1) sb.append(",");
            sb.append(nl);
        }
        sb.append(ind).append("]").append(nl);
        sb.append("}");
        return sb.toString();
    }

    private String typeJson(Type t, boolean pretty, String ind) {
        if (t==null) return "\"string\"";
        if ("array".equals(t.primitive)) {
            return "{\"type\":\"array\",\"items\":" + typeJson(t.items, pretty, ind) + "}";
        }
        if ("union".equals(t.primitive) && t.nullable) {
            return "[\"null\"," + typeJson(t.items, pretty, ind) + "]";
        }
        if ("record".equals(t.primitive)) {
            List<Field> flds = t.fields!=null? unique(t.fields) : List.of();
            return toRecordJson(t.name!=null?t.name:"Record", (this.namespace!=null?this.namespace:"xsd2avro.generated"), flds, pretty);
        }
        if ("enum".equals(t.primitive)) {
            return "{\"type\":\"enum\",\"name\":\""+escape(t.name!=null?t.name:"Enum")+"\",\"symbols\":[]}";
        }
        return "\""+t.primitive+"\"";
    }

    private static String escape(String s) {
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
