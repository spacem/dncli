package dncli.pak;

import dncli.utils.OS;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;

import javax.script.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Created by Benjamin Lei on 10/29/2015.
 */
public class Pak {
    public static final Options options = new Options();
    public static final String HEADER = "EyedentityGames Packing File 0.1";
    public static final int META_START = 260;
    public final static int META_SIZE = 256 + 4 + 4 + 4 + 4 + 44;
    public final static int PATH_SIZE = 256;

    public static final byte[] data = new byte[10485760];

    static {
        options.addOption(Option.builder("i")
                .longOpt("info")
                .desc("Shows total files in pak, and of how many are marked as deleted.")
                .build());

        options.addOption(Option.builder("l")
                .longOpt("list")
                .desc("(info only) Lists all the files and their sizes in the pak.")
                .build());

        options.addOption(Option.builder("x")
                .longOpt("extract")
                .desc("Extracts non-deleted files from one or more pak file.")
                .build());

        options.addOption(Option.builder("c")
                .longOpt("compress")
                .desc("Compresses a directory into a pak file. First argument must be the directory, second must be output file.")
                .build());

        options.addOption(Option.builder("f")
                .longOpt("force")
                .desc("(extract/compress with no filter only) Forces overwriting of output file(s) without prompting.")
                .build());

        options.addOption(Option.builder("e")
                .longOpt("filter")
                .hasArg()
                .desc("(extract only) Uses input JS file's filter(object) function that must return a boolean to decide what to extract.")
                .build());

        options.addOption(Option.builder("q")
                .longOpt("quiet")
                .desc("Quiet output")
                .build());

        options.addOption(Option.builder("m")
                .longOpt("min")
                .hasArg()
                .desc("(compress only) Sets the minimum size the output pak must be. Acceptable arguments are B, K, M, and G. For example 100M means 100 Megabytes, or 104857600 bytes.")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Shows this usage message.")
                .build());
    }

    public static void checkUsage(CommandLine cli) throws Exception {
        boolean isInfo = cli.hasOption("info");
        boolean isExtract = cli.hasOption("extract");
        boolean isCompress = cli.hasOption("compress");
        boolean isList = cli.hasOption("list");
        boolean isForce = cli.hasOption("force");
        boolean hasFilter = cli.hasOption("filter");
        boolean isQuiet = cli.hasOption("quiet");
        boolean hasMin = cli.hasOption("min");
        int numArgs = cli.getArgList().size();
        if (!(isInfo ^ isExtract ^ isCompress) ||
                !isInfo & isList ||
                (numArgs == 0) | isQuiet | isForce & isInfo ||
                hasFilter & !isExtract ||
                isCompress & (numArgs != 2) ||
                !isCompress & hasMin ||
                isExtract & (numArgs < 2) ||
                cli.hasOption("help")) {
            OS.usage("pak", "file [file]... [output]",
                    "Inspects/extracts/compresses a pak. For extracting you can specify a filter to evaluate " +
                            "what to extract.\n\nYou cannot specify info/extract/compress options together\n\n" +
                            "Available options:",
                    options);
        }
    }

    public static void use(CommandLine cli) throws Exception {
        checkUsage(cli);

        if (cli.hasOption("extract")) {
            extract(cli);
        } else if (cli.hasOption("compress")) {
            compress(cli);
        } else {
            info(cli);
        }
    }

    private static void info(CommandLine cli) throws Exception {
        List<String> paks = cli.getArgList();
        ArrayList<PakReader> pakList = new ArrayList<>();
        boolean showList = cli.hasOption("list");

        // get the list of paks + make sure they all exist
        for (String pak : paks) {
            File file = new File(pak);
            if (!file.exists()) {
                throw new FileNotFoundException(pak);
            }

            pakList.add(new PakReader(file, false));
        }

        // go through all the paks
        for (int i = 0; i < paks.size(); i++) {
            PakReader reader = pakList.get(i);
            ArrayList<Map<String, Object>> maps = new ArrayList<>();
            int deleted = 0;
            Map<String, Object> map;

            // read each pak and record if anything was deleted or not.
            while ((map = reader.read()) != null) {
                if (showList) {
                    maps.add(map);

                }

                int size = (Integer) map.get("size");
                if (size == 0) {
                    deleted++;
                }
            }

            // show info
            System.out.println(String.format("%s contains %d objects, %d of which are marked as deleted.",
                    paks.get(i),
                    reader.getSize(),
                    deleted));

            // show list of all objects in pak, if it was deleted, and the original to compressed size
            if (showList) {
                for (Map<String, Object> object : maps) {
                    int size = (Integer) object.get("size");
                    int zSize = (Integer) object.get("zsize");
                    String extraMessage;
                    if (size == 0) {
                        extraMessage = "*deleted*";
                    } else {
                        extraMessage = String.format("(%dB -> %dB)", size, zSize);
                    }
                    System.out.println(String.format("%s %s",
                            object.get("path"),
                            extraMessage));
                }
            }
        }
    }

    private static void extract(CommandLine cli) throws Exception {
        List<String> paks = cli.getArgList();
        String output = paks.remove(paks.size() - 1);
        File outputFile = new File(output);
        boolean force = cli.hasOption("force");
        boolean quiet = cli.hasOption("quiet");
        Invocable invocable = null;

        // set the function to be used for filtering files
        if (cli.hasOption("filter")) {
            String filter = cli.getOptionValue("filter");
            File filterFile = new File(filter);
            if (!filterFile.exists()) {
                throw new FileNotFoundException(filter);
            }

            ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
            ScriptEngine scriptEngine = scriptEngineManager.getEngineByName("nashorn");
            Compilable compilable = (Compilable)scriptEngine;
            invocable = (Invocable)scriptEngine;
            CompiledScript compiledScript = compilable.compile(new FileReader(filterFile));
            compiledScript.eval();
        }

        for (String pak : paks) {
            int extracted = 0;
            PakReader reader = new PakReader(new File(pak));
            Map<String, Object> map;
            long start = System.currentTimeMillis();

            // get each js object, maybe filter it, then extract it to output location
            while ((map = reader.read()) != null) {
                if (invocable != null) {
                    if ((Boolean) invocable.invokeFunction("filter", map)) {
                        extractTo(outputFile, force, map, quiet);
                        extracted++;
                    }
                } else if ((Integer) map.get("size") != 0) {
                    extractTo(outputFile, force, map, quiet);
                    extracted++;
                }
            }

            long end = System.currentTimeMillis();
            if (! quiet) {
                System.out.println("Extracted " + extracted + " objects from " + pak + " in " + (end - start) + "ms");
            }
        }
    }

    private static void extractTo(File parent, boolean force, Map<String, Object> map, boolean quiet) throws Exception {
        String child = map.get("path").toString();

        // fix the path
        if (OS.isUnix()) {
            child = child.replace('\\', '/');
        }

        File output = new File(parent, child);
        if (! force && ! OS.confirmOverwrite(output)) {
            return;
        }

        int read;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Inflater inflater = new Inflater();

        // unzip contents
        inflater.setInput((byte[]) map.get("data"));
        while ((read = inflater.inflate(data)) != 0) {
            baos.write(data, 0, read);
        }
        inflater.end();

        // create parent dirs, output it to output
        output.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(output);
        baos.writeTo(out);
        out.close();

        if (! quiet) {
            System.out.println(output.getPath());
        }
    }

    private static void compress(CommandLine cli) throws Exception {
        List<String> args = cli.getArgList();
        String dir = args.get(0);
        String output = args.get(1);
        boolean force = cli.hasOption("force");
        File dirFile = new File(dir);
        File outputFile = new File(output);
        int min = 0;

        // make sure user OK with overwriting
        if (! force && ! OS.confirmOverwrite(outputFile)) {
            return;
        }

        // calculate min pak size
        if (cli.hasOption("min")) {
            String minStr = cli.getOptionValue("min");
            char c = minStr.toLowerCase().charAt(minStr.length() - 1);
            if (c == 'b' || c == 'k' || c == 'm' || c == 'g') {
                minStr = minStr.substring(0, minStr.length() - 1);
            } else if (c < '0' || c > '9') {
                throw new IllegalArgumentException(String.format("'%c' is not a valid file size", c));
            }

            // throw exception if size is too big
            min = Integer.parseInt(minStr);
            if (c == 'k') {
                min = Math.multiplyExact(min, 1024);
            } else if (c == 'm') {
                min = Math.multiplyExact(min, 1024 * 1024);
            } else if (c == 'g') {
                min = Math.multiplyExact(min, 1024 * 1024 * 1024);
            }
        }

        // get iterator of files in directory
        Iterator<File> iterator = FileUtils.iterateFiles(dirFile, null, true);
        PakWriter writer = new PakWriter(outputFile, min);

        // get dirlen to copy off initial part of path
        int dirLen = dirFile.getPath().length();

        // add all files to pak
        while (iterator.hasNext()) {
            File file = iterator.next();
            String path = file.getPath().substring(dirLen);
            if (OS.isUnix()) {
                path = path.replace('/', '\\');
            }
            writer.write(file, path);
        }

        // flush contents
        writer.flush();
    }
}