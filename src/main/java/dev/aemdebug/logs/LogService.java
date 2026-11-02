package dev.aemdebug.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Reads and searches error.log: directly from the file by byte offset when it is readable on this machine,
 * otherwise through the Sling log tailer endpoint of the web console.
 */
public final class LogService {

    private static final Logger LOG = LoggerFactory.getLogger(LogService.class);
    private static final String[] TAILER_APPENDERS = {"/logs/error.log", "logs/error.log", "logs\\error.log"};

    /** A position in error.log taken before an action, so that only new lines are read afterwards. */
    public static final class Mark {
        public final String id;
        public final String origin;
        public final Path file;
        public final long offset;
        public final long time;

        Mark(String id, String origin, Path file, long offset, long time) {
            this.id = id;
            this.origin = origin;
            this.file = file;
            this.offset = offset;
            this.time = time;
        }
    }

    /** Filters for reading the log. */
    public static final class Query {
        public int minSeverity;
        public String logger;
        public Pattern grep;
        public String requestPath;
        public long sinceMillis = -1;
        public int limit = 200;
        public boolean requestOnly;
    }

    /** Entries plus a description of where they came from. */
    public static final class Result {
        public final List<LogEntry> entries;
        public final String source;
        public final boolean truncated;
        public final int scanned;

        public Result(List<LogEntry> entries, String source, boolean truncated, int scanned) {
            this.entries = entries;
            this.source = source;
            this.truncated = truncated;
            this.scanned = scanned;
        }
    }

    private final LogLocator locator;
    private final AemClient aem;
    private final Map<String, Mark> marks = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public LogService(LogLocator locator, AemClient aem) {
        this.locator = locator;
        this.aem = aem;
    }

    public Mark mark(ToolContext context, AemInstance instance) {
        LogLocator.Location location = locator.locate(context, instance);
        long now = System.currentTimeMillis();
        String id = "m" + counter.incrementAndGet();
        Mark mark = location.readable()
                ? new Mark(id, instance.origin(), location.errorLog, LogReader.size(location.errorLog), now)
                : new Mark(id, instance.origin(), null, -1, now);
        marks.put(id, mark);
        return mark;
    }

    public Mark mark(String id) {
        return id == null ? null : marks.get(id);
    }

    /** Entries written after the mark. */
    public Result since(ToolContext context, AemInstance instance, Mark mark, int maxBytes) {
        if (mark.file != null) {
            try {
                LogReader.Chunk chunk = LogReader.readFrom(mark.file, mark.offset, maxBytes);
                List<LogEntry> entries = ErrorLogParser.parse(chunk.text, chunk.from);
                String source = mark.file + " (bytes " + chunk.from + ".." + chunk.to
                        + (chunk.rotated ? ", log was rotated" : "") + ")";
                return new Result(entries, source, chunk.truncated, entries.size());
            } catch (IOException e) {
                LOG.info("Cannot read {}: {}", mark.file, e.getMessage());
            }
        }
        List<LogEntry> all = ErrorLogParser.parse(tailer(context, instance, 3000, null));
        List<LogEntry> recent = new ArrayList<>();
        for (LogEntry entry : all) {
            if (entry.timestamp() < 0 || entry.timestamp() >= mark.time - 1500) {
                recent.add(entry);
            }
        }
        return new Result(recent, "Sling log tailer (last 3000 lines, filtered by time)", false, all.size());
    }

    /** Tail/search of the log with filters; reads the newest {@code maxBytes} of the file. */
    public Result read(ToolContext context, AemInstance instance, Query query, int maxBytes) {
        LogLocator.Location location = locator.locate(context, instance);
        List<LogEntry> entries;
        String source;
        boolean truncated = false;
        if (location.readable()) {
            try {
                LogReader.Chunk chunk = LogReader.tail(location.errorLog, maxBytes);
                entries = ErrorLogParser.parse(chunk.text, chunk.from);
                truncated = chunk.truncated;
                source = location.errorLog + " (last " + Text.formatBytes(chunk.to - chunk.from) + ")";
            } catch (IOException e) {
                throw new ToolException("Cannot read " + location.errorLog + ": " + e.getMessage());
            }
        } else {
            String serverGrep = query.grep != null ? query.grep.pattern() : null;
            entries = ErrorLogParser.parse(tailer(context, instance, Math.max(500, query.limit * 20), serverGrep));
            source = "Sling log tailer (" + location.note + ")";
        }
        int scanned = entries.size();
        return new Result(filter(entries, query), source, truncated, scanned);
    }

    public static List<LogEntry> filter(List<LogEntry> entries, Query query) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (entry.severity() < query.minSeverity) {
                continue;
            }
            if (query.logger != null && (entry.logger() == null || !entry.logger().contains(query.logger))) {
                continue;
            }
            if (query.sinceMillis > 0 && entry.timestamp() > 0 && entry.timestamp() < query.sinceMillis) {
                continue;
            }
            RequestKey key = entry.requestKey();
            if (query.requestOnly && key == null) {
                continue;
            }
            if (query.requestPath != null && (key == null || !key.uri().contains(query.requestPath))) {
                continue;
            }
            if (query.grep != null && !query.grep.matcher(entry.fullText()).find()) {
                continue;
            }
            result.add(entry);
        }
        if (result.size() > query.limit) {
            return new ArrayList<>(result.subList(result.size() - query.limit, result.size()));
        }
        return result;
    }

    /** Groups entries by request (in first-seen order); entries without a request go under "other". */
    public static Map<String, List<LogEntry>> groupByRequest(List<LogEntry> entries) {
        Map<String, List<LogEntry>> groups = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            String key = entry.requestKey() == null ? "other threads" : entry.requestKey().label();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }
        return groups;
    }

    public static String format(List<LogEntry> entries, int maxStackLines) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append(entry.format(maxStackLines)).append('\n');
        }
        return sb.toString();
    }

    public static String fullText(List<LogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append(entry.fullText()).append('\n');
        }
        return sb.toString();
    }

    private String tailer(ToolContext context, AemInstance instance, int lines, String grep) {
        String grepParam = grep == null ? "" : "&grep=" + Text.urlEncode(grep);
        for (String appender : TAILER_APPENDERS) {
            AemResponse response = aem.get(context, instance, "/system/console/slinglog/tailer.txt?tail=" + lines
                    + grepParam + "&name=" + Text.urlEncode(appender));
            if (response.ok() && !response.contentType().contains("html")) {
                return response.text();
            }
        }
        throw new ToolException("error.log is not readable on this machine and the Sling log tailer "
                + "(/system/console/slinglog/tailer.txt) did not answer. Set "
                + (instance.isAuthor() ? "AEM_LOG_DIR" : "AEM_PUBLISH_LOG_DIR") + " to crx-quickstart/logs.");
    }
}
