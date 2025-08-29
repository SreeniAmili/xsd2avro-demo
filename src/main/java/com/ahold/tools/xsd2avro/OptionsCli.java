package com.ahold.tools.xsd2avro;

import org.apache.commons.cli.*;
import java.io.File;

public class OptionsCli {
    public File in;
    public File outDir;
    public String rootName;
    public String namespace;
    public boolean pretty;
    public boolean logicalTypes;
    public boolean nullableAttrs;
    public boolean showHelp;
    public String glob = "*.xsd";
    public String outNaming = "file+root"; // root|file|file+root
    public String avroName;
    public boolean flattenTop;
    public String forceString; // comma list (case-insensitive)

    public static OptionsCli parse(String[] args) throws ParseException {
        CommandLine cmd = new DefaultParser().parse(options(), args);
        OptionsCli c = new OptionsCli();
        c.showHelp      = cmd.hasOption("help");
        if (cmd.hasOption("in"))  c.in  = new File(cmd.getOptionValue("in"));
        if (cmd.hasOption("out")) c.outDir = new File(cmd.getOptionValue("out"));
        c.rootName      = cmd.getOptionValue("root-name");
        c.namespace     = cmd.getOptionValue("namespace");
        c.pretty        = cmd.hasOption("pretty");
        c.logicalTypes  = cmd.hasOption("logical-types");
        c.nullableAttrs = cmd.hasOption("nullable-attrs");
        if (cmd.hasOption("glob"))       c.glob = cmd.getOptionValue("glob");
        if (cmd.hasOption("out-naming")) c.outNaming = cmd.getOptionValue("out-naming");
        c.avroName      = cmd.getOptionValue("avro-name");
        c.flattenTop    = cmd.hasOption("flatten-top");
        c.forceString   = cmd.getOptionValue("force-string");
        return c;
    }

    public static void printHelp() {
        HelpFormatter hf = new HelpFormatter();
        hf.setWidth(120);
        hf.printHelp("java -jar xsd2avro-generic.jar --in <file-or-dir> --out <dir> [options]",
                "\nOptions:", options(), "", true);
    }

    private static Options options() {
        Options opts = new Options();
        opts.addOption(Option.builder().longOpt("in").hasArg().argName("file-or-dir").desc("Input XSD file or directory").build());
        opts.addOption(Option.builder().longOpt("out").hasArg().argName("dir").desc("Output directory for .avsc").build());
        opts.addOption(Option.builder().longOpt("root-name").hasArg().argName("name").desc("Root global element name").build());
        opts.addOption(Option.builder().longOpt("namespace").hasArg().argName("ns").desc("Avro namespace; default derives from targetNamespace").build());
        opts.addOption(Option.builder().longOpt("pretty").desc("Pretty-print JSON").build());
        opts.addOption(Option.builder().longOpt("logical-types").desc("Reserved flag").build());
        opts.addOption(Option.builder().longOpt("nullable-attrs").desc("Attributes nullable unless required").build());
        opts.addOption(Option.builder().longOpt("glob").hasArg().argName("pattern").desc("Glob in --in directory (default: *.xsd)").build());
        opts.addOption(Option.builder().longOpt("out-naming").hasArg().argName("mode").desc("Output name: root | file | file+root (default)").build());
        opts.addOption(Option.builder().longOpt("avro-name").hasArg().argName("name").desc("Override Avro record name").build());
        opts.addOption(Option.builder().longOpt("flatten-top").desc("Flatten one level of top-level child records into root").build());
        opts.addOption(Option.builder().longOpt("force-string").hasArg().argName("fields").desc("Comma-separated field names to coerce to string (case-insensitive)").build());
        opts.addOption(Option.builder().longOpt("help").desc("Show help").build());
        return opts;
    }
}
