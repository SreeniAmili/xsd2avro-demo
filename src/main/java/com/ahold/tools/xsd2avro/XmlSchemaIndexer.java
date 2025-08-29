package com.ahold.tools.xsd2avro;

import org.apache.ws.commons.schema.*;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class XmlSchemaIndexer {
    private final OptionsCli cli;
    private final XmlSchemaCollection coll;
    private final XmlSchema mainSchema;

    public XmlSchemaIndexer(OptionsCli cli) throws Exception {
        this.cli = cli;
        this.coll = new XmlSchemaCollection();

        // Null-safe base URI (works with relative paths)
        java.io.File parent = cli.in.getParentFile();
        if (parent == null) parent = cli.in.getAbsoluteFile().getParentFile();
        if (parent == null) parent = new java.io.File(".").getAbsoluteFile();
        coll.setBaseUri(parent.toURI().toString());

        try (InputStream in = new FileInputStream(cli.in)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);
            this.mainSchema = coll.read(doc, cli.in.toURI().toString());
        }
    }

    public XmlSchema schema() { return mainSchema; }
    public XmlSchemaCollection collection() { return coll; }

    public XmlSchemaElement selectRootElement() {
        List<XmlSchemaElement> globals = new ArrayList<>();
        for (XmlSchema s : coll.getXmlSchemas()) {
            if (s == null) continue;
            // elements() -> Map<QName, XmlSchemaElement> in xmlschema-core
            for (XmlSchemaElement e : s.getElements().values()) {
                if (e != null) globals.add(e);
            }
        }
        if (globals.isEmpty()) throw new IllegalStateException("No global elements in XSDs.");

        // If user forced a root name, honor it
        if (cli.rootName != null && !cli.rootName.isBlank()) {
            for (XmlSchemaElement e : globals) {
                if (cli.rootName.equals(e.getName())) return e;
                if (e.getQName() != null && cli.rootName.equals(e.getQName().getLocalPart())) return e;
            }
            throw new IllegalArgumentException("Root element '" + cli.rootName + "' not found.");
        }

        // Prefer a global element literally named 'Payload' (case-insensitive)
        for (XmlSchemaElement e : globals) {
            String n = e.getName();
            if (n != null && n.equalsIgnoreCase("Payload")) return e;
            if (e.getQName() != null && "Payload".equalsIgnoreCase(e.getQName().getLocalPart())) return e;
        }

        // Fallback to the first global
        return globals.get(0);
    }

    public String deriveNamespace() {
        if (cli.namespace != null && !cli.namespace.isBlank()) return cli.namespace;
        String tns = mainSchema != null ? mainSchema.getTargetNamespace() : null;
        if (tns == null || tns.isBlank()) return "xsd2avro.generated";
        String trimmed = tns.replaceFirst("^https?://", "").replaceFirst("^urn:", "");
        String[] parts = trimmed.split("[/:]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(p.replaceAll("[^A-Za-z0-9_]", "_"));
        }
        return sb.length() == 0 ? "xsd2avro.generated" : sb.toString();
    }

    public XmlSchemaType findType(QName qn) {
        if (qn == null) return null;
        for (XmlSchema s : coll.getXmlSchemas()) {
            if (s == null) continue;
            XmlSchemaType t = s.getTypeByName(qn);
            if (t != null) return t;
        }
        return null;
    }

    public XmlSchemaElement findElement(QName qn) {
        if (qn == null) return null;
        for (XmlSchema s : coll.getXmlSchemas()) {
            if (s == null) continue;
            XmlSchemaElement e = s.getElementByName(qn);
            if (e != null) return e;
        }
        return null;
    }

    public XmlSchemaGroup findGroup(QName qn) {
        if (qn == null) return null;
        for (XmlSchema s : coll.getXmlSchemas()) {
            if (s == null) continue;
            Map<QName, XmlSchemaGroup> groups = s.getGroups();
            if (groups != null) {
                XmlSchemaGroup g = groups.get(qn);
                if (g != null) return g;
            }
        }
        return null;
    }

    public XmlSchemaAttributeGroup findAttributeGroup(QName qn) {
        if (qn == null) return null;
        for (XmlSchema s : coll.getXmlSchemas()) {
            if (s == null) continue;
            Map<QName, XmlSchemaAttributeGroup> ags = s.getAttributeGroups();
            if (ags != null) {
                XmlSchemaAttributeGroup g = ags.get(qn);
                if (g != null) return g;
            }
        }
        return null;
    }
}
