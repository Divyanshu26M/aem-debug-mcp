package dev.aemdebug.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores large artifacts (full HTML, full-page screenshots, log excerpts, RPT) under
 * {@code <artifactsDir>/reports/<reportId>/<name>} and exposes them as {@code aemdbg://reports/{id}/{name}}.
 */
public final class ArtifactStore {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactStore.class);

    public static final String URI_PREFIX = "aemdbg://reports/";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}");
    private static final Pattern REPORT_DIR = Pattern.compile("rpt-(\\d{4,})");
    private static final int KEEP_REPORTS = 60;

    private final Path baseDir;
    private final Path reportsDir;
    private int counter;

    public ArtifactStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reportsDir = this.baseDir.resolve("reports");
        try {
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create artifacts directory " + reportsDir, e);
        }
        this.counter = highestExistingReportNumber();
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path reportsDir() {
        return reportsDir;
    }

    /** Allocates a new report id such as rpt-0007 and creates its directory. */
    public synchronized String newReport() {
        while (true) {
            counter++;
            String id = String.format(Locale.ROOT, "rpt-%04d", counter);
            Path dir = reportsDir.resolve(id);
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                prune();
                return id;
            }
        }
    }

    public Path write(String reportId, String name, byte[] data) {
        Path file = resolve(reportId, name);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write artifact " + file, e);
        }
    }

    public Path write(String reportId, String name, String text) {
        return write(reportId, name, text.getBytes(StandardCharsets.UTF_8));
    }

    public String uri(String reportId, String name) {
        return URI_PREFIX + reportId + "/" + name;
    }

    /** Resolves an artifact path, rejecting anything that could escape the reports directory. */
    public Path resolve(String reportId, String name) {
        if (reportId == null || name == null || !SAFE_SEGMENT.matcher(reportId).matches()
                || !SAFE_SEGMENT.matcher(name).matches() || name.contains("..") || reportId.contains("..")) {
            throw new IllegalArgumentException("Invalid artifact reference " + reportId + "/" + name);
        }
        Path file = reportsDir.resolve(reportId).resolve(name).normalize();
        if (!file.startsWith(reportsDir)) {
            throw new IllegalArgumentException("Invalid artifact reference " + reportId + "/" + name);
        }
        return file;
    }

    /** Parses aemdbg://reports/{id}/{name}. Returns {id, name}. */
    public static String[] parseUri(String uri) {
        if (uri == null || !uri.startsWith(URI_PREFIX)) {
            throw new IllegalArgumentException("Not an aem-debug-mcp artifact URI: " + uri);
        }
        String rest = uri.substring(URI_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("Artifact URI must look like " + URI_PREFIX + "{id}/{name}: " + uri);
        }
        return new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    public byte[] read(String reportId, String name) {
        Path file = resolve(reportId, name);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Artifact not found: " + reportId + "/" + name, e);
        }
    }

    public List<String> list(String reportId) {
        List<String> names = new ArrayList<>();
        Path dir = reportsDir.resolve(reportId);
        if (!SAFE_SEGMENT.matcher(reportId).matches() || !Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            LOG.debug("Cannot list {}: {}", dir, e.toString());
        }
        names.sort(String::compareTo);
        return names;
    }

    public static String mimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    public static boolean isText(String name) {
        return mimeType(name).startsWith("text/") || mimeType(name).equals("application/json");
    }

    private int highestExistingReportNumber() {
        int max = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir)) {
            for (Path p : stream) {
                Matcher m = REPORT_DIR.matcher(p.getFileName().toString());
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        } catch (IOException | NumberFormatException e) {
            LOG.debug("Cannot scan reports: {}", e.toString());
        }
        return max;
    }

    private void prune() {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && REPORT_DIR.matcher(p.getFileName().toString()).matches()) {
                    dirs.add(p);
                }
            }
        } catch (IOException e) {
            return;
        }
        if (dirs.size() <= KEEP_REPORTS) {
            return;
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (int i = 0; i < dirs.size() - KEEP_REPORTS; i++) {
            deleteRecursively(dirs.get(i));
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Cannot delete {}", p);
                }
            });
        } catch (IOException e) {
            LOG.debug("Cannot prune {}", dir);
        }
    }
}
