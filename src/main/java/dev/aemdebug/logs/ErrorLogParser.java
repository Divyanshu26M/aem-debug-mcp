package dev.aemdebug.logs;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AEM error.log lines such as
 * {@code 22.10.2026 17:19:00.945 *ERROR* [127.0.0.1 [1729590000000] GET /content/x.html HTTP/1.1] logger message}.
 * The thread field may contain nested brackets; continuation lines (stack traces) are folded into the entry.
 */
public final class ErrorLogParser {

    private static final Pattern HEAD = Pattern.compile(
            "^(\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\*([A-Z]+)\\* \\[");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS", Locale.ROOT);

    private ErrorLogParser() {
    }

    public static List<LogEntry> parse(String text) {
        return parse(text, 0);
    }

    /** Parses text that starts at the given byte offset of the log file. */
    public static List<LogEntry> parse(String text, long baseOffset) {
        List<LogEntry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return entries;
        }
        LogEntry current = null;
        long offset = baseOffset;
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = text.indexOf('\n', start);
            int next = end < 0 ? length : end + 1;
            String line = text.substring(start, end < 0 ? length : end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            LogEntry parsed = parseHead(line, offset);
            if (parsed != null) {
                current = parsed;
                entries.add(parsed);
            } else if (current != null && !line.isEmpty()) {
                current.addContinuation(line);
            }
            offset += text.substring(start, next).getBytes(StandardCharsets.UTF_8).length;
            start = next;
        }
        return entries;
    }

    /** Parses a single header line; returns null for continuation lines. */
    static LogEntry parseHead(String line, long offset) {
        Matcher m = HEAD.matcher(line);
        if (!m.find()) {
            return null;
        }
        int threadStart = m.end();
        int depth = 1;
        int i = threadStart;
        while (i < line.length() && depth > 0) {
            char c = line.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            i++;
        }
        if (depth != 0) {
            return null;
        }
        String thread = line.substring(threadStart, i - 1);
        String rest = i < line.length() ? line.substring(i).trim() : "";
        String logger;
        String message;
        int space = rest.indexOf(' ');
        if (space < 0) {
            logger = rest;
            message = "";
        } else {
            logger = rest.substring(0, space);
            message = rest.substring(space + 1);
        }
        return new LogEntry(toEpochMillis(m.group(1)), m.group(1), m.group(2), thread, logger, message, offset);
    }

    public static long toEpochMillis(String timestamp) {
        try {
            return LocalDateTime.parse(timestamp, TIMESTAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }
}
