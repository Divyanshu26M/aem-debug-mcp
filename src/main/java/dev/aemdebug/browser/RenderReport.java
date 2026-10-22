package dev.aemdebug.browser;

import dev.aemdebug.report.ArtifactStore;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats render results compactly and stores the large parts as report artifacts. */
public final class RenderReport {

    private static final Pattern CONSOLE_STATUS = Pattern.compile("status of (\\d{3})");

    private RenderReport() {
    }

    /** Writes page.html, screenshot-full.png, screenshot.jpg, network.txt and console.txt. Returns name -> uri. */
    public static Map<String, String> saveArtifacts(ArtifactStore store, String reportId, RenderResult result) {
        Map<String, String> saved = new LinkedHashMap<>();
        if (result.html() != null) {
            store.write(reportId, "page.html", result.html());
            saved.put("page.html", store.uri(reportId, "page.html"));
        }
        if (result.fullPagePng() != null) {
            store.write(reportId, "screenshot-full.png", result.fullPagePng());
            saved.put("screenshot-full.png", store.uri(reportId, "screenshot-full.png"));
        }
        if (result.viewportJpeg() != null) {
            store.write(reportId, "screenshot.jpg", result.viewportJpeg());
            saved.put("screenshot.jpg", store.uri(reportId, "screenshot.jpg"));
        }
        store.write(reportId, "network.txt", networkLog(result));
        saved.put("network.txt", store.uri(reportId, "network.txt"));
        if (!result.console().isEmpty()) {
            store.write(reportId, "console.txt", consoleLog(result));
            saved.put("console.txt", store.uri(reportId, "console.txt"));
        }
        return saved;
    }

    public static String networkLog(RenderResult result) {
        StringBuilder sb = new StringBuilder();
        for (NetworkEntry entry : result.network()) {
            sb.append(Text.formatTime(entry.startedAt())).append(' ')
                    .append(entry.status() < 0 ? "---" : String.valueOf(entry.status())).append(' ')
                    .append(entry.method()).append(' ').append(entry.url())
                    .append(" [").append(entry.resourceType()).append(']');
            if (entry.durationMs() >= 0) {
                sb.append(' ').append(entry.durationMs()).append(" ms");
            }
            if (entry.failure() != null) {
                sb.append(" FAILED: ").append(entry.failure());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String consoleLog(RenderResult result) {
        StringBuilder sb = new StringBuilder();
        for (ConsoleEntry entry : result.console()) {
            sb.append(Text.formatTime(entry.at())).append(' ').append(entry.type().toUpperCase(Locale.ROOT)).append(' ')
                    .append(entry.text());
            if (entry.location() != null && !entry.location().isBlank()) {
                sb.append("  (").append(entry.location()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** One-paragraph summary of requests, failures and console errors. */
    public static String summary(RenderResult result, int maxFailures) {
        StringBuilder sb = new StringBuilder();
        if (result.navigationError() != null) {
            sb.append("Navigation problem: ").append(result.navigationError()).append('\n');
        }
        NetworkEntry document = result.documentRequest();
        Map<String, Integer> byType = new TreeMap<>();
        int external = 0;
        for (NetworkEntry entry : result.network()) {
            byType.merge(entry.resourceType() == null ? "other" : entry.resourceType(), 1, Integer::sum);
            if (entry.host() != null && !dev.aemdebug.config.HostPolicy.isLocalHost(entry.host())) {
                external++;
            }
        }
        sb.append("Requests: ").append(result.network().size());
        if (document != null) {
            sb.append(" (document ").append(document.status()).append(document.durationMs() >= 0
                    ? ", " + document.durationMs() + " ms" : "").append(')');
        }
        if (!byType.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : byType.entrySet()) {
                parts.add(e.getValue() + " " + e.getKey());
            }
            sb.append(" - ").append(String.join(", ", parts));
        }
        if (external > 0) {
            sb.append(" - ").append(external).append(" to non-local hosts");
        }
        if (!result.networkIdle()) {
            sb.append(" - network never went idle");
        }
        sb.append('\n');
        List<NetworkEntry> failed = new ArrayList<>();
        for (NetworkEntry entry : result.failedRequests()) {
            if (!isFavicon(entry.url())) {
                failed.add(entry);
            }
        }
        if (!failed.isEmpty()) {
            sb.append("Failed requests (").append(failed.size()).append("):\n");
            int shown = 0;
            for (NetworkEntry entry : failed) {
                if (shown++ >= maxFailures) {
                    sb.append("  ... ").append(failed.size() - maxFailures).append(" more in network.txt\n");
                    break;
                }
                int status = statusOf(entry, result.console());
                sb.append("  - ").append(status > 0 ? String.valueOf(status) : "FAILED")
                        .append(' ').append(entry.method()).append(' ').append(Text.oneLine(entry.url(), 160))
                        .append(" [").append(entry.resourceType()).append(']');
                if (entry.failure() != null) {
                    sb.append(" ").append(Text.oneLine(entry.failure(), 120));
                }
                sb.append('\n');
            }
        }
        List<ConsoleEntry> errors = new ArrayList<>();
        for (ConsoleEntry entry : result.consoleErrors()) {
            if (!isFavicon(entry.location())) {
                errors.add(entry);
            }
        }
        if (!errors.isEmpty()) {
            sb.append("Console/JS errors (").append(errors.size()).append("):\n");
            int shown = 0;
            for (ConsoleEntry entry : errors) {
                if (shown++ >= maxFailures) {
                    sb.append("  ... more in console.txt\n");
                    break;
                }
                sb.append("  - ").append(Text.oneLine(entry.text(), 220));
                if (entry.location() != null && !entry.location().isBlank()) {
                    sb.append(" (").append(Text.oneLine(entry.location(), 120)).append(')');
                }
                sb.append('\n');
            }
        }
        if (!result.timings().isEmpty()) {
            sb.append("Timings: ").append(result.timings()).append('\n');
        }
        return sb.toString();
    }

    /** "Artifacts:" block listing resource URIs and local file paths. */
    public static String artifactsBlock(ArtifactStore store, String reportId, Map<String, String> saved) {
        StringBuilder sb = new StringBuilder("Artifacts (MCP resources; also readable as local files under ")
                .append(store.reportsDir().resolve(reportId)).append("):\n");
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            sb.append("  - ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** The response status, or the status Chrome reported on the console when the request was aborted. */
    static int statusOf(NetworkEntry entry, List<ConsoleEntry> console) {
        if (entry.status() > 0) {
            return entry.status();
        }
        String url = entry.url();
        if (url == null) {
            return -1;
        }
        for (ConsoleEntry message : console) {
            String location = message.location();
            if (location != null && location.startsWith(url)) {
                Matcher matcher = CONSOLE_STATUS.matcher(Text.nullToEmpty(message.text()));
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        }
        return -1;
    }

    /** The browser's automatic favicon request only adds noise to the summary. */
    static boolean isFavicon(String url) {
        return url != null && url.contains("/favicon.ico");
    }
}
