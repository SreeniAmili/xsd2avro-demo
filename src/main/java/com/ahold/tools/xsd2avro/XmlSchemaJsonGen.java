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

        // Collect fields from root
        List<Field> fields = elementToFields(root, new HashSet<>(), "");

        // Force-string coercions (case-insensitive)
        if (cli.forceString != null && !cli.forceString.isBlank()) {
            Set<String> force = new HashSet<>();
            for (String s : cli.forceString.split(",")) if (!s.isBlank()) force.add(s.trim().toLowerCase());
            fields = forceToString(fields, force);
        }

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
        if (elem.getRef()!=null && elem.getRef().getTargetQName()!=null) {
            XmlSchemaElement target = idx.findElement(elem.getRef().getTargetQName());
            if (target != null) return elementToFields(target, seenTypes, prefix);
        }
        List<Field> res = new ArrayList<>();
        String fname = (elem.getName()!=null?elem.getName() :
                (elem.getRef()!=null && elem.getRef().getTargetQName()!=null
                        ? elem.getRef().getTargetQName().getLocalPart() : "field"));

        Type t = typeOf(elem, seenTypes, fname);
        res.add(new Field(fname, t));
        return res;
    }

    private Type typeOf(XmlSchemaElement elem, Set<String> seenTypes, String preferredName) {
        boolean isArray = elem.getMaxOccurs() > 1 || elem.getMaxOccurs() == Long.MAX_VALUE;
        boolean isOptional = elem.getMinOccurs() == 0;
        Type base;
        if (elem.getSchemaType() instanceof XmlSchemaComplexType ct) {
            base = complexToType(ct, preferredName, seenTypes);
        } else if (elem.getSchemaTypeName()!=null) {
            QName qn = elem.getSchemaTypeName();
            if ("http://www.w3.org/2001/XMLSchema".equals(qn.getNamespaceURI())) {
                base = simpleFromQName(qn);
            } else {
                XmlSchemaType t = idx.findType(qn);
                if (t instanceof XmlSchemaComplexType cct) base = complexToType(cct, qn.getLocalPart(), seenTypes);
                else if (t instanceof XmlSchemaSimpleType st) base = simpleFromRestriction(st, qn.getLocalPart());
                else base = simpleFromLocal("string");
            }
        } else {
            base = simpleFromLocal("string");
        }
        if (isArray) {
            Type arr = new Type(); arr.primitive="array"; arr.items = unwrapNullable(base);
            base = arr;
        }
        if (isOptional) base = wrapNullable(base);
        return base;
    }

    private Type complexToType(XmlSchemaComplexType ct, String preferredName, Set<String> seenTypes) {
        String typeName = ct.getName()!=null?ct.getName():preferredName;
        if (typeName!=null) {
            String key = "T:" + typeName;
            if (seenTypes.contains(key)) { // break cycles
                Type ref = new Type(); ref.primitive="record"; ref.name = typeName; ref.fields = new ArrayList<>(); return ref;
            }
            seenTypes.add(key);
        }

        Type rec = new Type(); rec.primitive="record"; rec.name = (preferredName!=null?preferredName:"Record");
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
                    QName qn = null; try { java.lang.reflect.Method m = agr.getClass().getMethod("getRefName"); Object r = m.invoke(agr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
                    XmlSchemaAttributeGroup g = idx.findAttributeGroup(qn);
                    if (g != null && g.getAttributes()!=null) {
                        for (Object a : g.getAttributes()) {
                            if (a instanceof XmlSchemaAttribute ga) {
                                String fn = ga.getName()!=null?ga.getName():"attr";
                                Type at = (ga.getSchemaTypeName()!=null? simpleFromQName(ga.getSchemaTypeName()): simpleFromLocal("string"));
                                boolean required = ga.getUse() == XmlSchemaUse.REQUIRED;
                                if (!required || cli.nullableAttrs) at = wrapNullable(at);
                                fields.add(new Field(fn, at));
                            }
                        }
                    }
                }
            }
        }

        // content model
        XmlSchemaContentModel content = ct.getContentModel();
        if (content instanceof XmlSchemaComplexContent cc) {
            if (cc.getContent() instanceof XmlSchemaComplexContentExtension ext) {
                // 1) Merge base type fields (headers often live here)
                QName bqn = ext.getBaseTypeName();
                XmlSchemaType bt = idx.findType(bqn);
                if (bt instanceof XmlSchemaComplexType bct) fields.addAll(harvestFromComplex(bct, seenTypes));
                // 2) Extension particle
                if (ext.getParticle() != null) fields.addAll(particleToFields(ext.getParticle(), seenTypes));
                // 3) Extension-level attributes and attributeGroup refs
                if (ext.getAttributes()!=null) {
                    for (Object o : ext.getAttributes()) {
                        if (o instanceof XmlSchemaAttribute a) {
                            String fn = a.getName()!=null?a.getName():"attr";
                            Type at = (a.getSchemaTypeName()!=null? simpleFromQName(a.getSchemaTypeName()): simpleFromLocal("string"));
                            boolean required = a.getUse() == XmlSchemaUse.REQUIRED;
                            if (!required || cli.nullableAttrs) at = wrapNullable(at);
                            fields.add(new Field(fn, at));
                        } else if (o instanceof XmlSchemaAttributeGroupRef agr) {
                            QName qn = null;
                            try { java.lang.reflect.Method m = agr.getClass().getMethod("getRefName"); Object r = m.invoke(agr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
                            XmlSchemaAttributeGroup g = idx.findAttributeGroup(qn);
                            if (g != null && g.getAttributes()!=null) {
                                for (Object a2 : g.getAttributes()) {
                                    if (a2 instanceof XmlSchemaAttribute ga) {
                                        String fn2 = ga.getName()!=null?ga.getName():"attr";
                                        Type at2 = (ga.getSchemaTypeName()!=null? simpleFromQName(ga.getSchemaTypeName()): simpleFromLocal("string"));
                                        boolean required2 = ga.getUse() == XmlSchemaUse.REQUIRED;
                                        if (!required2 || cli.nullableAttrs) at2 = wrapNullable(at2);
                                        fields.add(new Field(fn2, at2));
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (cc.getContent() instanceof XmlSchemaComplexContentRestriction res) {
                QName bqn = res.getBaseTypeName();
                XmlSchemaType bt = idx.findType(bqn);
                if (bt instanceof XmlSchemaComplexType bct) fields.addAll(harvestFromComplex(bct, seenTypes));
                if (res.getParticle() != null) fields.addAll(particleToFields(res.getParticle(), seenTypes));
            }
        } else if (content instanceof XmlSchemaSimpleContent sc) {
            if (sc.getContent() instanceof XmlSchemaSimpleContentExtension ext) {
                Type base = simpleFromQName(ext.getBaseTypeName());
                fields.add(new Field("value", base));
            } else if (sc.getContent() instanceof XmlSchemaSimpleContentRestriction res) {
                Type base = simpleFromQName(res.getBaseTypeName());
                fields.add(new Field("value", base));
            }
        } else {
            if (ct.getParticle() != null) fields.addAll(particleToFields(ct.getParticle(), seenTypes));
        }

        rec.fields = fields;
        return rec;
    }

    private List<Field> particleToFields(XmlSchemaParticle p, Set<String> seenTypes) {
        List<Field> out = new ArrayList<>();
        if (p instanceof XmlSchemaSequence seq) {
            for (Object o : seq.getItems()) {
                if (o instanceof XmlSchemaElement el) out.addAll(elementToFields(el, seenTypes, ""));
                else if (o instanceof XmlSchemaChoice ch) out.add(new Field("choice", simpleFromLocal("string")));
                else if (o instanceof XmlSchemaGroupRef gr) {
                    QName qn = null; try { java.lang.reflect.Method m = gr.getClass().getMethod("getRefName"); Object r = m.invoke(gr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
                    XmlSchemaGroup g = idx.findGroup(qn);
                    if (g!=null && g.getParticle()!=null) out.addAll(particleToFields(g.getParticle(), seenTypes));
                } else if (o instanceof XmlSchemaAny) {
                    out.add(new Field("any", simpleFromLocal("string")));
                }
            }
        } else if (p instanceof XmlSchemaAll all) {
            for (Object o : all.getItems()) {
                if (o instanceof XmlSchemaElement el) out.addAll(elementToFields(el, seenTypes, ""));
            }
        } else if (p instanceof XmlSchemaChoice ch) {
            out.add(new Field("choice", simpleFromLocal("string")));
        } else if (p instanceof XmlSchemaGroupRef gr) {
            QName qn = null; try { java.lang.reflect.Method m = gr.getClass().getMethod("getRefName"); Object r = m.invoke(gr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
            XmlSchemaGroup g = idx.findGroup(qn);
            if (g!=null && g.getParticle()!=null) out.addAll(particleToFields(g.getParticle(), seenTypes));
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

    private Type simpleFromRestriction(XmlSchemaSimpleType st, String preferredName) {
        XmlSchemaSimpleTypeContent c = st.getContent();
        if (c instanceof XmlSchemaSimpleTypeRestriction res) {
            List<String> symbols = new ArrayList<>();
            for (XmlSchemaFacet f : res.getFacets()) {
                if (f instanceof XmlSchemaEnumerationFacet ev) {
                    String v = String.valueOf(ev.getValue());
                    String sym = v.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
                    if (sym.isEmpty()) sym = "_";
                    symbols.add(sym);
                }
            }
            if (!symbols.isEmpty()) {
                Type e = new Type(); e.primitive="enum"; e.name = (preferredName!=null?preferredName:"Enum"); e.symbols = symbols; return e;
            }
            QName base = res.getBaseTypeName();
            if (base!=null && "http://www.w3.org/2001/XMLSchema".equals(base.getNamespaceURI())) return simpleFromQName(base);
        }
        return simpleFromLocal("string");
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

    private List<Field> forceToString(List<Field> fields, Set<String> targetsLower) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields) {
            if (f.type!=null && "record".equals(f.type.primitive) && f.type.fields!=null) {
                Field nf = new Field(f.name, f.type);
                nf.type = new Type();
                nf.type.primitive = "record";
                nf.type.name = f.type.name;
                nf.type.fields = forceToString(f.type.fields, targetsLower);
                out.add(nf);
            } else {
                if (targetsLower.contains(f.name.toLowerCase())) {
                    Field nf = new Field(f.name, simpleFromLocal("string"));
                    out.add(nf);
                } else {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private List<Field> flattenOneLevel(List<Field> fields) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields) {
            if (f.type!=null && "record".equals(f.type.primitive) && f.type.fields!=null && !f.type.fields.isEmpty()) {
                String prefix = Character.toLowerCase(f.name.charAt(0)) + f.name.substring(1);
                for (Field sf : f.type.fields) {
                    String nn = prefix + Character.toUpperCase(sf.name.charAt(0)) + sf.name.substring(1);
                    out.add(new Field(nn, sf.type));
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
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"enum\",\"name\":\"").append(escape(t.name!=null?t.name:"Enum")).append("\",\"symbols\":[");
            for (int i=0;i<(t.symbols!=null?t.symbols.size():0);i++) {
                if (i>0) sb.append(",");
                sb.append("\"").append(escape(t.symbols.get(i))).append("\"");
            }
            sb.append("]}");
            return sb.toString();
        }
        return "\""+t.primitive+"\"";
    }

    private static String escape(String s) {
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }


    // Pull fields/attributes recursively from a complexType (used for base types in extensions)
    private List<Field> harvestFromComplex(XmlSchemaComplexType ct, Set<String> seenTypes) {
        List<Field> fields = new ArrayList<>();
        // attributes on the base type
        if (ct.getAttributes()!=null) {
            for (Object o : ct.getAttributes()) {
                if (o instanceof XmlSchemaAttribute a) {
                    String fn = a.getName()!=null?a.getName():"attr";
                    Type at = (a.getSchemaTypeName()!=null? simpleFromQName(a.getSchemaTypeName()): simpleFromLocal("string"));
                    boolean required = a.getUse() == XmlSchemaUse.REQUIRED;
                    if (!required || cli.nullableAttrs) at = wrapNullable(at);
                    fields.add(new Field(fn, at));
                } else if (o instanceof XmlSchemaAttributeGroupRef agr) {
                    QName qn = null;
                    try { java.lang.reflect.Method m = agr.getClass().getMethod("getRefName"); Object r = m.invoke(agr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
                    XmlSchemaAttributeGroup g = idx.findAttributeGroup(qn);
                    if (g != null && g.getAttributes()!=null) {
                        for (Object a2 : g.getAttributes()) {
                            if (a2 instanceof XmlSchemaAttribute ga) {
                                String fn2 = ga.getName()!=null?ga.getName():"attr";
                                Type at2 = (ga.getSchemaTypeName()!=null? simpleFromQName(ga.getSchemaTypeName()): simpleFromLocal("string"));
                                boolean required2 = ga.getUse() == XmlSchemaUse.REQUIRED;
                                if (!required2 || cli.nullableAttrs) at2 = wrapNullable(at2);
                                fields.add(new Field(fn2, at2));
                            }
                        }
                    }
                }
            }
        }
        // particle on the base type
        if (ct.getParticle()!=null) fields.addAll(particleToFields(ct.getParticle(), seenTypes));
        // nested complex/simple content on the base type
        XmlSchemaContentModel content = ct.getContentModel();
        if (content instanceof XmlSchemaComplexContent cc) {
            if (cc.getContent() instanceof XmlSchemaComplexContentExtension ext) {
                // recurse into its base as well
                QName bqn = ext.getBaseTypeName();
                XmlSchemaType bt = idx.findType(bqn);
                if (bt instanceof XmlSchemaComplexType bct) fields.addAll(harvestFromComplex(bct, seenTypes));
                if (ext.getParticle()!=null) fields.addAll(particleToFields(ext.getParticle(), seenTypes));
                // attributes defined on the extension itself
                if (ext.getAttributes()!=null) {
                    for (Object o : ext.getAttributes()) {
                        if (o instanceof XmlSchemaAttribute a) {
                            String fn = a.getName()!=null?a.getName():"attr";
                            Type at = (a.getSchemaTypeName()!=null? simpleFromQName(a.getSchemaTypeName()): simpleFromLocal("string"));
                            boolean required = a.getUse() == XmlSchemaUse.REQUIRED;
                            if (!required || cli.nullableAttrs) at = wrapNullable(at);
                            fields.add(new Field(fn, at));
                        } else if (o instanceof XmlSchemaAttributeGroupRef agr) {
                            QName qn = null;
                            try { java.lang.reflect.Method m = agr.getClass().getMethod("getRefName"); Object r = m.invoke(agr); if (r instanceof QName) qn = (QName) r; } catch (Exception ignore) {}
                            XmlSchemaAttributeGroup g = idx.findAttributeGroup(qn);
                            if (g != null && g.getAttributes()!=null) {
                                for (Object a2 : g.getAttributes()) {
                                    if (a2 instanceof XmlSchemaAttribute ga) {
                                        String fn2 = ga.getName()!=null?ga.getName():"attr";
                                        Type at2 = (ga.getSchemaTypeName()!=null? simpleFromQName(ga.getSchemaTypeName()): simpleFromLocal("string"));
                                        boolean required2 = ga.getUse() == XmlSchemaUse.REQUIRED;
                                        if (!required2 || cli.nullableAttrs) at2 = wrapNullable(at2);
                                        fields.add(new Field(fn2, at2));
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (cc.getContent() instanceof XmlSchemaComplexContentRestriction res) {
                QName bqn = res.getBaseTypeName();
                XmlSchemaType bt = idx.findType(bqn);
                if (bt instanceof XmlSchemaComplexType bct) fields.addAll(harvestFromComplex(bct, seenTypes));
                if (res.getParticle()!=null) fields.addAll(particleToFields(res.getParticle(), seenTypes));
            }
        } else if (content instanceof XmlSchemaSimpleContent sc) {
            if (sc.getContent() instanceof XmlSchemaSimpleContentExtension ext) {
                Type base = simpleFromQName(ext.getBaseTypeName());
                fields.add(new Field("value", base));
            } else if (sc.getContent() instanceof XmlSchemaSimpleContentRestriction res) {
                Type base = simpleFromQName(res.getBaseTypeName());
                fields.add(new Field("value", base));
            }
        }
        return fields;
    }

}