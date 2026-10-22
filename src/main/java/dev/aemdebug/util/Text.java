package dev.aemdebug.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Formatting helpers for compact, LLM-friendly tool output. */
public final class Text {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private Text() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /** Truncates long text and states how much was cut. */
    public static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        int cut = value.length() - maxChars;
        return value.substring(0, Math.max(0, maxChars)) + "\n... [truncated " + cut + " chars]";
    }

    /** Collapses whitespace/newlines and truncates to a single line. */
    public static String oneLine(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        if (flat.length() <= maxChars) {
            return flat;
        }
        return flat.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    public static String indent(String text, String prefix) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            sb.append(prefix).append(line).append('\n');
        }
        return sb.toString();
    }

    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1000;
        return minutes + " min " + seconds + " s";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String formatTime(long epochMillis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }

    public static String formatDateTime(long epochMillis) {
        return DATE_TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }

    public static String plural(long count, String singular) {
        return count + " " + (count == 1 ? singular : singular + "s");
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Renders a markdown table; cells are flattened to one line. */
    public static String table(List<String> headers, List<List<String>> rows, int maxCellChars) {
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (String header : headers) {
            sb.append(' ').append(cell(header, maxCellChars)).append(" |");
        }
        sb.append('\n').append('|');
        for (int i = 0; i < headers.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (List<String> row : rows) {
            sb.append('|');
            for (int i = 0; i < headers.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                sb.append(' ').append(cell(value, maxCellChars)).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String cell(String value, int maxChars) {
        return oneLine(nullToEmpty(value), maxChars).replace("|", "\\|");
    }

    public static String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\\R", -1).length;
    }
}
