package cupthenthecnt;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Java implementation of the CupTheCnt file-extraction utility.
 *
 * <p>It reads the 3DS CupList format used by the original tool and the
 * Contents.cnt header used by developer System Updaters, then extracts the
 * referenced CIA ranges without loading whole CIA files into memory.</p>
 */
public final class CupTheCnt {
    private static final String VERSION = "1.0.0";
    private static final int CUPLIST_BYTES = 0x800;
    private static final int CUPLIST_ENTRIES = 0x100;
    private static final int CONTENTS_HEADER_BYTES = 0x1400;
    private static final int CONTENTS_ENTRIES_OFFSET = 0xC00;
    private static final long DATA_BASE = 0xC00L;

    private CupTheCnt() {}

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (CliException e) {
            System.err.println("Error: " + e.getMessage());
            exitCode = 2;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            exitCode = 1;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws IOException, CliException {
        if (args.length == 0) {
            printUsage(new PrintWriter(System.out, true));
            return 0;
        }

        Options options = Options.parse(args);
        if (options.help) {
            printUsage(new PrintWriter(System.out, true));
            return 0;
        }
        if (options.version) {
            System.out.println("CupTheCnt Java " + VERSION);
            return 0;
        }

        if (options.batch) {
            require(options.inputs.size() == 1,
                    "--batch expects exactly one input directory");
            return runBatch(options);
        }

        require(options.inputs.size() == 2,
                "expected <CupList> <Contents.cnt>");
        Path cupList = options.inputs.get(0);
        Path contents = options.inputs.get(1);

        Dataset dataset = Dataset.read(cupList, contents);
        Verification verification = dataset.verify();

        if (options.verifyOnly) {
            verification.print(new PrintWriter(System.out, true), dataset);
            return verification.ok ? 0 : 1;
        }

        if (options.listOnly) {
            verification.requireUsable();
            dataset.printList(new PrintWriter(System.out, true));
            return 0;
        }

        verification.requireUsable();
        Path output = options.output;
        Files.createDirectories(output);
        return dataset.extract(output, options.overwrite, options.quiet);
    }

    private static int runBatch(Options options) throws IOException, CliException {
        Path inputRoot = options.inputs.get(0);
        require(Files.isDirectory(inputRoot),
                "batch input is not a directory: " + inputRoot);

        List<DatasetPair> pairs = discoverPairs(inputRoot, options.recursive);
        if (pairs.isEmpty()) {
            System.out.println("No CupList + Contents.cnt pairs found in " + inputRoot);
            return 1;
        }

        Path outputRoot = options.output;
        Files.createDirectories(outputRoot);

        int failed = 0;
        System.out.printf("Found %d dataset pair(s).%n", pairs.size());
        for (DatasetPair pair : pairs) {
            Path relative = inputRoot.relativize(pair.directory);
            Path datasetOutput = outputRoot.resolve(relative).resolve("updates");
            System.out.println();
            System.out.println("== " + (relative.toString().isEmpty() ? "." : relative) + " ==");
            System.out.println("CupList     : " + pair.cupList.getFileName());
            System.out.println("Contents.cnt: " + pair.contents.getFileName());
            try {
                Dataset dataset = Dataset.read(pair.cupList, pair.contents);
                Verification verification = dataset.verify();
                if (!verification.ok) {
                    verification.print(new PrintWriter(System.out, true), dataset);
                    failed++;
                    continue;
                }
                dataset.extract(datasetOutput, options.overwrite, options.quiet);
            } catch (IOException | CliException e) {
                System.err.println("Failed: " + e.getMessage());
                failed++;
            }
        }

        System.out.printf("%nBatch finished: %d succeeded, %d failed.%n",
                pairs.size() - failed, failed);
        return failed == 0 ? 0 : 1;
    }

    private static List<DatasetPair> discoverPairs(Path root, boolean recursive) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (recursive) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isDirectory).forEach(dirs::add);
            }
        } else {
            dirs.add(root);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child)) {
                        dirs.add(child);
                    }
                }
            }
        }

        dirs.sort(Comparator.comparing(Path::toString));
        List<DatasetPair> pairs = new ArrayList<>();
        for (Path dir : dirs) {
            Path cupList = findByName(dir, "cuplist");
            Path contents = findByName(dir, "contents.cnt");
            if (cupList != null && contents != null) {
                pairs.add(new DatasetPair(dir, cupList, contents));
            }
        }
        return pairs;
    }

    private static Path findByName(Path dir, String wanted) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) throws CliException {
        if (!condition) throw new CliException(message);
    }

    private static void printUsage(PrintWriter out) {
        out.println("CupTheCnt Java " + VERSION);
        out.println();
        out.println("Usage:");
        out.println("  java -jar CupTheCnt.jar [options] <CupList> <Contents.cnt>");
        out.println("  java -jar CupTheCnt.jar --verify [options] <CupList> <Contents.cnt>");
        out.println("  java -jar CupTheCnt.jar --list [options] <CupList> <Contents.cnt>");
        out.println("  java -jar CupTheCnt.jar --batch [options] <input-directory>");
        out.println();
        out.println("Options:");
        out.println("  --output, -o DIR   Output directory (default: updates)");
        out.println("  --verify           Validate CupList, Contents.cnt and all referenced ranges");
        out.println("  --list             Print title IDs, offsets and CIA sizes; do not extract");
        out.println("  --batch            Process every directory containing CupList + Contents.cnt");
        out.println("  --recursive, -r    Search batch input recursively");
        out.println("  --overwrite        Replace existing CIA files (default: fail on collision)");
        out.println("  --quiet, -q        Suppress per-file progress output");
        out.println("  --version          Print version");
        out.println("  --help, -h         Show this help");
        out.println();
        out.println("Batch naming rule:");
        out.println("  Each dataset directory must contain files named exactly");
        out.println("  'CupList' and 'Contents.cnt' (case-insensitive).");
    }

    private record DatasetPair(Path directory, Path cupList, Path contents) {}

    private static final class Options {
        boolean verifyOnly;
        boolean listOnly;
        boolean batch;
        boolean recursive;
        boolean overwrite;
        boolean quiet;
        boolean help;
        boolean version;
        Path output = Path.of("updates");
        final List<Path> inputs = new ArrayList<>();

        static Options parse(String[] args) throws CliException {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--verify" -> o.verifyOnly = true;
                    case "--list" -> o.listOnly = true;
                    case "--batch" -> o.batch = true;
                    case "--recursive", "-r" -> o.recursive = true;
                    case "--overwrite" -> o.overwrite = true;
                    case "--quiet", "-q" -> o.quiet = true;
                    case "--help", "-h" -> o.help = true;
                    case "--version" -> o.version = true;
                    case "--output", "-o" -> {
                        if (++i >= args.length) throw new CliException(a + " requires a directory");
                        o.output = Path.of(args[i]);
                    }
                    default -> {
                        if (a.startsWith("-")) throw new CliException("unknown option: " + a);
                        o.inputs.add(Path.of(a));
                    }
                }
            }
            if (o.verifyOnly && o.listOnly) throw new CliException("--verify and --list are mutually exclusive");
            if (o.batch && (o.verifyOnly || o.listOnly)) throw new CliException("--batch cannot be combined with --verify or --list");
            if ((o.help || o.version) && !o.inputs.isEmpty()) throw new CliException("--help/--version cannot be combined with inputs");
            return o;
        }
    }

    private static final class CliException extends Exception {
        CliException(String message) { super(message); }
    }

    private record CupList(long[] titleIds, boolean truncated) {
        static CupList read(Path path) throws IOException, CliException {
            if (!Files.isRegularFile(path)) throw new IOException("CupList is not a file: " + path);
            long size = Files.size(path);
            if (size < 8) throw new CliException("CupList is too small: " + path);

            byte[] data = new byte[CUPLIST_BYTES];
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                readFully(ch, buf, 0);
            }

            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            List<Long> ids = new ArrayList<>();
            boolean zeroSeen = false;
            for (int i = 0; i < CUPLIST_ENTRIES; i++) {
                long titleId = b.getLong();
                if (titleId == 0) {
                    zeroSeen = true;
                    break;
                }
                ids.add(titleId);
            }
            return new CupList(toLongArray(ids), !zeroSeen);
        }

        private static long[] toLongArray(List<Long> ids) {
            long[] result = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
            return result;
        }
    }

    private record ContentsEntry(long offset, long offsetEnd) {
        long size() { return offsetEnd - offset; }
    }

    private record Dataset(CupList cupList, ContentsEntry[] entries, long fileSize, Path contentsPath) {
        static Dataset read(Path cupListPath, Path contentsPath) throws IOException, CliException {
            CupList cupList = CupList.read(cupListPath);
            long fileSize = Files.size(contentsPath);
            if (fileSize < CONTENTS_HEADER_BYTES) {
                throw new CliException("Contents.cnt is smaller than its fixed header (0x1400 bytes): " + contentsPath);
            }

            ContentsEntry[] entries = readEntries(contentsPath, cupList.titleIds.length);
            return new Dataset(cupList, entries, fileSize, contentsPath);
        }

        private static ContentsEntry[] readEntries(Path path, int count) throws IOException {
            byte[] header = new byte[CONTENTS_HEADER_BYTES];
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                readFully(ch, ByteBuffer.wrap(header), 0);
            }

            if (header[0] != 'C' || header[1] != 'O' || header[2] != 'N' || header[3] != 'T') {
                throw new IOException("Invalid Contents.cnt magic; expected CONT");
            }

            ByteBuffer b = ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .position(CONTENTS_ENTRIES_OFFSET);
            ContentsEntry[] entries = new ContentsEntry[count];
            for (int i = 0; i < count; i++) {
                long offset = Integer.toUnsignedLong(b.getInt());
                long offsetEnd = Integer.toUnsignedLong(b.getInt());
                entries[i] = new ContentsEntry(offset, offsetEnd);
            }
            return entries;
        }

        Verification verify() {
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            if (cupList.truncated) {
                warnings.add("CupList did not contain a zero terminator within 0x100 entries; treating all 256 entries as indexed.");
            }
            if (cupList.titleIds.length == 0) {
                errors.add("CupList contains no title IDs.");
            }

            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < cupList.titleIds.length; i++) {
                long tid = cupList.titleIds[i];
                if (!seen.add(tid)) errors.add("duplicate Title ID at index " + i + ": " + formatTitleId(tid));

                ContentsEntry e = entries[i];
                if (e.offsetEnd < e.offset) {
                    errors.add("entry " + i + " has offset_end < offset");
                    continue;
                }
                long actualStart = DATA_BASE + e.offset;
                long size = e.size();
                if (actualStart < DATA_BASE || actualStart > fileSize) {
                    errors.add("entry " + i + " starts outside file: absolute=0x" + Long.toHexString(actualStart));
                    continue;
                }
                if (size > fileSize - actualStart) {
                    errors.add("entry " + i + " exceeds file: start=0x" + Long.toHexString(actualStart)
                            + ", size=0x" + Long.toHexString(size));
                }
                if (size == 0) {
                    warnings.add("entry " + i + " has zero-length CIA for " + formatTitleId(tid));
                }
            }
            return new Verification(errors.isEmpty(), errors, warnings);
        }

        void printList(PrintWriter out) {
            out.println("Index  Title ID          Offset      End         Size (KB)");
            out.println("-----  ----------------  ----------  ----------  ---------");
            for (int i = 0; i < cupList.titleIds.length; i++) {
                ContentsEntry e = entries[i];
                out.printf(Locale.ROOT, "%5d  %016x  0x%08x  0x%08x  %9d%n",
                        i,
                        cupList.titleIds[i],
                        e.offset(),
                        e.offsetEnd(),
                        e.size() / 1024);
            }
        }

        int extract(Path outputDir, boolean overwrite, boolean quiet) throws IOException, CliException {
            Verification verification = verify();
            verification.requireUsable();
            Files.createDirectories(outputDir);

            int written = 0;
            try (FileChannel input = FileChannel.open(contentsPath, StandardOpenOption.READ)) {
                for (int i = 0; i < cupList.titleIds.length; i++) {
                    long titleId = cupList.titleIds[i];
                    ContentsEntry entry = entries[i];
                    long size = entry.size();
                    long absoluteStart = DATA_BASE + entry.offset();
                    Path output = outputDir.resolve(formatTitleId(titleId) + ".cia");

                    if (Files.exists(output) && !overwrite) {
                        throw new CliException("output already exists (use --overwrite): " + output);
                    }
                    try (FileChannel out = FileChannel.open(output,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                        copyRange(input, out, absoluteStart, size);
                    } catch (IOException | RuntimeException e) {
                        try { Files.deleteIfExists(output); } catch (IOException ignored) {}
                        throw e;
                    }

                    written++;
                    if (!quiet) {
                        System.out.printf(Locale.ROOT, "Writing %s (%d KB)%n", output, size / 1024);
                    }
                }
            }
            if (!quiet) System.out.println("Extracted " + written + " CIA file(s).");
            return 0;
        }
    }

    private static final class Verification {
        final boolean ok;
        final List<String> errors;
        final List<String> warnings;

        Verification(boolean ok, List<String> errors, List<String> warnings) {
            this.ok = ok;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        void requireUsable() throws CliException {
            if (!ok) throw new CliException("verification failed; rerun with --verify for details");
        }

        void print(PrintWriter rawOut, Dataset dataset) {
            PrintWriter out = rawOut;
            out.printf(Locale.ROOT, "Contents.cnt: %s%n", dataset.contentsPath);
            out.printf(Locale.ROOT, "File size   : %d bytes (0x%x)%n", dataset.fileSize, dataset.fileSize);
            out.printf(Locale.ROOT, "Title count : %d%n", dataset.cupList.titleIds.length);
            for (String warning : warnings) out.println("WARNING: " + warning);
            for (String error : errors) out.println("ERROR: " + error);
            out.println(ok ? "VERIFY OK" : "VERIFY FAILED");
        }
    }

    private static String formatTitleId(long id) {
        return String.format(Locale.ROOT, "%016x", id);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) break;
            if (n == 0) continue;
            position += n;
        }
    }

    private static void copyRange(FileChannel src, FileChannel dst, long position, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long n = src.transferTo(position, remaining, dst);
            if (n > 0) {
                position += n;
                remaining -= n;
                continue;
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect((int) Math.min(1024 * 1024, remaining));
            int read = src.read(buffer, position);
            if (read < 0) throw new IOException("unexpected EOF while copying CIA data");
            if (read == 0) throw new IOException("unable to make progress while copying CIA data");
            buffer.flip();
            while (buffer.hasRemaining()) dst.write(buffer);
            position += read;
            remaining -= read;
        }
    }

}
