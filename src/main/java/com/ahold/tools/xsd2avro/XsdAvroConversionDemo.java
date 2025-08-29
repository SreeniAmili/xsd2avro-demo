package com.ahold.tools.xsd2avro;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XsdAvroConversionDemo {
    public static void main(String[] args) throws Exception {
        OptionsCli cli = OptionsCli.parse(args);
        if (cli.showHelp) { OptionsCli.printHelp(); return; }
        if (cli.in == null || cli.outDir == null) {
            OptionsCli.printHelp();
            throw new IllegalArgumentException("--in and --out are required");
        }

        if (cli.in.isDirectory()) {
            Path dir = cli.in.toPath();
            String pattern = (cli.glob == null || cli.glob.isBlank()) ? "*.xsd" : cli.glob;
            List<File> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, pattern)) {
                for (Path p : ds) if (Files.isRegularFile(p)) files.add(p.toFile());
            }
            if (files.isEmpty()) throw new IllegalArgumentException("No XSDs matched glob '" + pattern + "' in " + dir);
            int ok = 0, fail = 0;
            for (File xsd : files) {
                try {
                    OptionsCli one = new OptionsCli();
                    one.in = xsd; one.outDir = cli.outDir;
                    one.rootName = cli.rootName; one.namespace = cli.namespace;
                    one.pretty = cli.pretty; one.logicalTypes = cli.logicalTypes; one.nullableAttrs = cli.nullableAttrs;
                    one.glob = cli.glob; one.outNaming = cli.outNaming;
                    one.avroName = cli.avroName; one.flattenTop = cli.flattenTop; one.forceString = cli.forceString;

                    XmlSchemaJsonGen gen = new XmlSchemaJsonGen(one);
                    XmlSchemaJsonGen.Result res = gen.generate();
                    String base = outName(cli, stripExt(xsd.getName()), res.rootName);
                    File out = new File(cli.outDir, base + ".avsc");
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write((cli.pretty ? res.jsonPretty : res.jsonCompact).getBytes(StandardCharsets.UTF_8));
                    }
                    System.out.println(" " + xsd.getName() + " -> " + out.getName());
                    ok++;
                } catch (Exception ex) {
                    System.err.println("✘ " + xsd.getName() + " : " + ex.getMessage());
                    fail++;
                }
            }
            System.out.println("Done. Generated=" + ok + ", Failed=" + fail);
            return;
        }

        XmlSchemaJsonGen gen = new XmlSchemaJsonGen(cli);
        XmlSchemaJsonGen.Result res = gen.generate();
        String base = outName(cli, stripExt(cli.in.getName()), res.rootName);
        File out = new File(cli.outDir, base + ".avsc");
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write((cli.pretty ? res.jsonPretty : res.jsonCompact).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String outName(OptionsCli cli, String fileBase, String rootName) {
        String rn = (rootName == null || rootName.isBlank()) ? "Record" : rootName;
        String fb = (fileBase == null || fileBase.isBlank()) ? "xsd" : fileBase;
        return switch (cli.outNaming == null ? "file+root" : cli.outNaming) {
            case "root" -> rn;
            case "file" -> fb;
            default -> fb + "__" + rn;
        };
    }
}
