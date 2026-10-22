package dev.aemdebug.logs;

import dev.aemdebug.correlate.RequestKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One error.log entry with folded continuation lines (stack traces). */
public final class LogEntry {

    private final long timestamp;
    private final String timestampText;
    private final String level;
    private final String thread;
    private final String logger;
    private final String message;
    private final List<String> continuation = new ArrayList<>();
    private final RequestKey requestKey;
    private final long offset;

    public LogEntry(long timestamp, String timestampText, String level, String thread, String logger, String message,
                    long offset) {
        this.timestamp = timestamp;
        this.timestampText = timestampText;
        this.level = level;
        this.thread = thread;
        this.logger = logger;
        this.message = message;
        this.offset = offset;
        this.requestKey = RequestKey.parse(thread);
    }

    void addContinuation(String line) {
        continuation.add(line);
    }

    public long timestamp() {
        return timestamp;
    }

    public String timestampText() {
        return timestampText;
    }

    public String level() {
        return level;
    }

    public String thread() {
        return thread;
    }

    public String logger() {
        return logger;
    }

    public String message() {
        return message;
    }

    public List<String> continuation() {
        return Collections.unmodifiableList(continuation);
    }

    public RequestKey requestKey() {
        return requestKey;
    }

    public long offset() {
        return offset;
    }

    public int severity() {
        return severity(level);
    }

    public static int severity(String level) {
        if (level == null) {
            return 0;
        }
        switch (level.toUpperCase(Locale.ROOT)) {
            case "ERROR":
                return 5;
            case "WARN":
            case "WARNING":
                return 4;
            case "INFO":
                return 3;
            case "DEBUG":
                return 2;
            case "TRACE":
                return 1;
            default:
                return 0;
        }
    }

    public boolean hasStackTrace() {
        for (String line : continuation) {
            if (line.trim().startsWith("at ")) {
                return true;
            }
        }
        return false;
    }

    /** The innermost "Caused by" line, or the first exception-looking continuation line. */
    public String rootCause() {
        String root = null;
        for (String line : continuation) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                root = trimmed.substring("Caused by:".length()).trim();
            } else if (root == null && !trimmed.startsWith("at ") && !trimmed.startsWith("...")
                    && trimmed.matches("^[\\w.$]+(Exception|Error|Throwable)\\b.*")) {
                root = trimmed;
            }
        }
        return root;
    }

    /** Compact rendering: header plus the first frames of the stack trace. */
    public String format(int maxStackLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(timestampText).append(' ').append(level).append(' ');
        if (requestKey != null) {
            sb.append('[').append(requestKey.method()).append(' ').append(requestKey.uri()).append("] ");
        } else if (thread != null) {
            sb.append('[').append(thread).append("] ");
        }
        sb.append(logger).append(' ').append(message);
        int shown = 0;
        for (String line : continuation) {
            if (shown >= maxStackLines) {
                sb.append("\n    ... ").append(continuation.size() - shown).append(" more lines");
                break;
            }
            sb.append("\n    ").append(line.trim());
            shown++;
        }
        String cause = rootCause();
        if (cause != null && shown < continuation.size() && maxStackLines > 0) {
            sb.append("\n    root cause: ").append(cause);
        }
        return sb.toString();
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestampText).append(" *").append(level).append("* [").append(thread).append("] ").append(logger)
                .append(' ').append(message);
        for (String line : continuation) {
            sb.append('\n').append(line);
        }
        return sb.toString();
    }
}
