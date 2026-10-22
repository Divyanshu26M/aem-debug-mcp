package dev.aemdebug.tools.impl;

import dev.aemdebug.browser.NetworkEntry;
import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.jdi.Snapshot;
import dev.aemdebug.logs.LogEntry;
import dev.aemdebug.logs.LogService;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.mapping.ComponentNode;
import dev.aemdebug.rpt.RptParser;
import dev.aemdebug.server.Services;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Text and artifacts for the server-side part of debug_page and debug_capture. */
final class DebugReport {

    private static final int WARN = LogEntry.severity("WARN");
    private static final int ERROR = LogEntry.severity("ERROR");

    private DebugReport() {
    }

    /** Server events per browser request (debug_page); {@code full} lifts the size limits (report.md). */
    static String pageServerSide(DebugRun run, RequestCorrelator.Result result, List<NetworkEntry> network,
                                 boolean full) {
        StringBuilder sb = new StringBuilder("### Server side (matched to the browser requests by Sling request thread)\n");
        appendSources(sb, run);
        if (result.requests().isEmpty()) {
            sb.append("No exceptions, snapshots, log lines or RPT entries on the page's own requests.\n");
        }
        int maxGroups = full ? Integer.MAX_VALUE : 8;
        int shown = 0;
        for (RequestCorrelator.Group group : result.requests()) {
            if (shown == maxGroups) {
                sb.append("... ").append(result.requests().size() - shown)
                        .append(" more requests with server events (report.md)\n");
                break;
            }
            shown++;
            NetworkEntry entry = DebugRun.entryFor(group.browser(), network);
            sb.append("#### ").append(group.browser().method()).append(' ').append(group.browser().path());
            if (entry != null) {
                sb.append(" (").append(entry.navigation() ? "document" : Text.firstNonBlank(entry.resourceType(), "request"))
                        .append(", HTTP ").append(entry.status() < 0 ? "-" : String.valueOf(entry.status())).append(')');
            }
            sb.append(" - ").append(counts(group.events())).append('\n');
            appendEvents(sb, group.events(), full ? Integer.MAX_VALUE : 12, full || shown <= 2, "");
        }
        appendOthers(sb, result, full);
        return sb.toString();
    }

    /** Server events grouped per Sling request (debug_capture). */
    static String captureBody(DebugRun run, RequestCorrelator.Result grouped, String filter, boolean full) {
        StringBuilder sb = new StringBuilder();
        appendSources(sb, run);
        if (filter != null) {
            sb.append("- filter: requests whose URI contains '").append(filter).append("'\n");
        }
        if (!run.newRequests.isEmpty()) {
            sb.append("- Recent Requests console: ").append(run.newRequests.size()).append(" new request(s) recorded\n");
        }
        List<RequestCorrelator.Group> groups = grouped.serverOnly();
        if (groups.isEmpty() && grouped.unattributed().isEmpty()) {
            sb.append("\nNothing was captured. Did the user reproduce while the capture was running? Is the JVM "
                    + "attached (see above)? Are the watched packages right?\n");
        }
        int maxGroups = full ? Integer.MAX_VALUE : 10;
        int shown = 0;
        for (RequestCorrelator.Group group : groups) {
            if (shown == maxGroups) {
                sb.append("... ").append(groups.size() - shown).append(" more requests (report.md)\n");
                break;
            }
            shown++;
            sb.append("\n#### ").append(label(group)).append(" - ").append(counts(group.events())).append('\n');
            appendEvents(sb, group.events(), full ? Integer.MAX_VALUE : 10, full || shown <= 3, "");
        }
        if (!grouped.unattributed().isEmpty()) {
            sb.append("\n#### Not on a request thread (background jobs, schedulers, OSGi)\n");
            appendEvents(sb, grouped.unattributed(), full ? Integer.MAX_VALUE : 8, full, "");
        }
        return sb.toString();
    }

    static void appendSources(StringBuilder sb, DebugRun run) {
        for (String note : run.jvmNotes) {
            sb.append("- JVM: ").append(note).append('\n');
        }
        sb.append("- error.log: ");
        if (run.logSource == null) {
            sb.append("not captured\n");
        } else {
            sb.append(Text.plural(run.logEntries.size(), "new line")).append(" (").append(run.logSource).append(")\n");
        }
        if (run.rptAvailable()) {
            sb.append("- Request Progress Tracker: ").append(run.rpts.isEmpty() ? "no matching entry"
                    : run.rpts.size() + " request(s)").append('\n');
        }
        for (String note : run.notes) {
            sb.append("- note: ").append(note).append('\n');
        }
    }

    private static void appendOthers(StringBuilder sb, RequestCorrelator.Result result, boolean full) {
        List<RequestCorrelator.Group> others = new ArrayList<>();
        int own = 0;
        for (RequestCorrelator.Group group : result.serverOnly()) {
            if (!full && isOwnRequest(group)) {
                own++;
            } else {
                others.add(group);
            }
        }
        if (!others.isEmpty()) {
            sb.append("#### Other requests during the run (not made by this page: other tabs, the author UI, or "
                    + "aem-debug-mcp itself, e.g. the edit-mode render used for component mapping)\n");
            int max = full ? Integer.MAX_VALUE : 6;
            for (int i = 0; i < Math.min(max, others.size()); i++) {
                RequestCorrelator.Group group = others.get(i);
                sb.append("- ").append(label(group)).append(" - ").append(counts(group.events())).append('\n');
                appendEvents(sb, group.events(), full ? Integer.MAX_VALUE : 3, full, "  ");
            }
            if (others.size() > max) {
                sb.append("- ... ").append(others.size() - max).append(" more (report.md)\n");
            }
        }
        if (own > 0) {
            sb.append("- ").append(own).append(" JSON/console request(s), most likely made by aem-debug-mcp for the "
                    + "component map, also had server events (report.md)\n");
        }
        if (!result.unattributed().isEmpty()) {
            sb.append("#### Not on a request thread (background jobs, schedulers, OSGi)\n");
            appendEvents(sb, result.unattributed(), full ? Integer.MAX_VALUE : 5, full, "");
        }
    }

    static boolean isOwnRequest(RequestCorrelator.Group group) {
        if (group.server() == null) {
            return false;
        }
        String path = group.server().path();
        return path.endsWith(".json") || path.startsWith("/system/console") || path.startsWith("/libs/granite/csrf")
                || path.startsWith("/libs/granite/security/currentuser") || path.startsWith("/bin/querybuilder");
    }

    static String label(RequestCorrelator.Group group) {
        RequestCorrelator.ServerRequest server = group.server();
        if (server == null) {
            return group.label();
        }
        return server.method() + " " + server.uri()
                + (server.timed() ? " @ " + Text.formatTime(server.startMillis()) : "");
    }

    static String counts(List<RequestCorrelator.Event> events) {
        int exceptions = 0;
        int snapshots = 0;
        int logs = 0;
        int warnings = 0;
        int rpt = 0;
        for (RequestCorrelator.Event event : events) {
            Object payload = event.payload();
            if (payload instanceof Snapshot) {
                if (((Snapshot) payload).isException()) {
                    exceptions++;
                } else {
                    snapshots++;
                }
            } else if (payload instanceof LogEntry) {
                logs++;
                if (((LogEntry) payload).severity() >= WARN) {
                    warnings++;
                }
            } else if (payload instanceof DebugRun.Rpt) {
                rpt++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (exceptions > 0) {
            parts.add(Text.plural(exceptions, "exception"));
        }
        if (snapshots > 0) {
            parts.add(Text.plural(snapshots, "snapshot"));
        }
        if (logs > 0) {
            parts.add(Text.plural(logs, "log line") + (warnings > 0 ? " (" + warnings + " WARN/ERROR)" : ""));
        }
        if (rpt > 0) {
            parts.add("RPT");
        }
        return parts.isEmpty() ? "no server events" : String.join(", ", parts);
    }

    static void appendEvents(StringBuilder sb, List<RequestCorrelator.Event> events, int max, boolean details,
                             String indent) {
        List<RequestCorrelator.Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.<RequestCorrelator.Event>comparingInt(DebugReport::priority)
                .thenComparingLong(RequestCorrelator.Event::timeMillis));
        int detailed = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (i == max) {
                sb.append(indent).append("- ... ").append(ordered.size() - max).append(" more (report artifacts)\n");
                break;
            }
            RequestCorrelator.Event event = ordered.get(i);
            Object payload = event.payload();
            String text;
            if (payload instanceof Snapshot) {
                Snapshot snapshot = (Snapshot) payload;
                if (details && detailed < 3) {
                    detailed++;
                    text = snapshot.format(12).trim();
                } else {
                    text = snapshot.summary();
                }
            } else if (payload instanceof LogEntry) {
                LogEntry entry = (LogEntry) payload;
                text = entry.severity() >= WARN && entry.hasStackTrace()
                        ? "log " + entry.format(details ? 8 : 3).trim()
                        : "log " + Text.oneLine(entry.timestampText() + " *" + entry.level() + "* " + entry.logger()
                        + " " + entry.message(), 300);
            } else if (payload instanceof DebugRun.Rpt) {
                text = rptSummary((DebugRun.Rpt) payload);
            } else {
                text = event.summary();
            }
            sb.append(bullet(text, indent)).append('\n');
        }
    }

    static String bullet(String text, String indent) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(indent).append("- ").append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n').append(indent).append("  ").append(lines[i]);
        }
        return sb.toString();
    }

    static int priority(RequestCorrelator.Event event) {
        Object payload = event.payload();
        if (payload instanceof Snapshot) {
            return ((Snapshot) payload).isException() ? 0 : 1;
        }
        if (payload instanceof LogEntry) {
            return ((LogEntry) payload).severity() >= WARN ? 2 : 4;
        }
        return 3;
    }

    static String rptSummary(DebugRun.Rpt rpt) {
        RptParser.Result parsed = rpt.parsed;
        StringBuilder sb = new StringBuilder("RPT ");
        sb.append(parsed.total >= 0 ? parsed.formatDuration(parsed.total) : "?");
        if (parsed.servlet != null) {
            sb.append(", servlet ").append(parsed.servlet);
        }
        if (parsed.resolvedPath != null) {
            sb.append(", resource ").append(parsed.resolvedPath);
        }
        if (parsed.resolvedType != null) {
            sb.append(" [").append(parsed.resolvedType).append(']');
        }
        sb.append(", ").append(Text.plural(parsed.includes.size(), "include"));
        List<RptParser.Include> slowest = new ArrayList<>(parsed.includes);
        slowest.sort((a, b) -> Long.compare(b.elapsed, a.elapsed));
        for (int i = 0; i < Math.min(3, slowest.size()); i++) {
            RptParser.Include include = slowest.get(i);
            if (include.elapsed < 0) {
                break;
            }
            sb.append("\nslow include: ").append(include.resourcePath).append(" [").append(include.resourceType)
                    .append("] ").append(parsed.formatDuration(include.elapsed));
            if (include.script != null) {
                sb.append(' ').append(include.script);
            }
        }
        for (int i = 0; i < Math.min(5, parsed.problems.size()); i++) {
            sb.append("\nproblem: ").append(Text.oneLine(parsed.problems.get(i), 240));
        }
        return sb.toString();
    }

    /** Hints that combine the browser, component and server-side views of one page render. */
    static List<String> pageHints(DebugRun run, ComponentMapper.Result mapping, RequestCorrelator.Result correlation,
                                  NetworkEntry document) {
        Set<String> hints = new LinkedHashSet<>();
        for (Snapshot snapshot : run.snapshots) {
            if (!snapshot.isException()) {
                continue;
            }
            String message = snapshot.exceptionMessage == null ? "" : snapshot.exceptionMessage;
            String type = snapshot.exceptionClass == null ? "" : snapshot.exceptionClass;
            if (type.endsWith("MissingElementsException") || message.contains("Could not inject")
                    || (message.contains("Identifier ") && message.contains("cannot be correctly instantiated"))) {
                hints.add("Sling Model problem (" + snapshot.id + ": " + Text.oneLine(message, 160) + "): compare the "
                        + "model's injected names with the JCR properties (component_inspect), e.g. "
                        + "@ValueMapValue(name = \"jcr:title\"), or make the injection optional.");
            }
            ComponentNode node = componentFor(mapping, snapshot.className);
            if (node != null) {
                hints.add("#" + node.number + " " + node.shortPath(mapping.pagePath) + " uses "
                        + shortName(outer(snapshot.className)) + ", which threw " + shortName(type) + " at "
                        + snapshot.location + " (" + snapshot.id + ")"
                        + (node.renderedEmpty() ? "; the component rendered empty." : "."));
            }
        }
        for (RequestCorrelator.Group group : correlation.requests()) {
            for (RequestCorrelator.Event event : group.events()) {
                if (event.payload() instanceof LogEntry) {
                    LogEntry entry = (LogEntry) event.payload();
                    String text = Text.nullToEmpty(entry.logger()) + " " + Text.nullToEmpty(entry.message());
                    if (entry.severity() >= ERROR && (text.contains("sightly") || text.contains("HTL"))) {
                        hints.add("HTL error while rendering " + group.browser().path() + ": "
                                + Text.oneLine(entry.message(), 200) + " (component_inspect shows the script).");
                    }
                }
            }
        }
        if (document != null) {
            int status = document.status();
            if (status >= 500 && !run.jvmActive) {
                hints.add("HTTP " + status + " without JVM data: free the JDWP port (detach IntelliJ) and run debug_page "
                        + "again to capture the exception at the throw site.");
            }
            if (status == 404) {
                hints.add("HTTP 404: check the path (aem_get_resource) and that the page exists on "
                        + run.instance.name() + ".");
            }
            if (status == 401 || status == 403) {
                hints.add("HTTP " + status + ": the browser session lacks access (aem_auth action=status).");
            }
        }
        if (!run.snapshots.isEmpty()) {
            hints.add("Snapshot ids (sN) above: jvm_snapshots action=get id=sN shows every captured frame and local.");
        }
        return new ArrayList<>(hints);
    }

    static ComponentNode componentFor(ComponentMapper.Result mapping, String className) {
        if (mapping == null || className == null) {
            return null;
        }
        String outer = outer(className);
        for (ComponentNode node : mapping.components) {
            if (mentions(node.runtimeModels, outer) || mentions(node.localModels, outer)) {
                return node;
            }
        }
        return null;
    }

    private static boolean mentions(List<String> values, String className) {
        for (String value : values) {
            if (value != null && (value.equals(className) || value.startsWith(className + " "))) {
                return true;
            }
        }
        return false;
    }

    static String outer(String className) {
        if (className == null) {
            return "";
        }
        int dollar = className.indexOf('$');
        return dollar < 0 ? className : className.substring(0, dollar);
    }

    static String shortName(String className) {
        return className == null ? "" : className.substring(className.lastIndexOf('.') + 1);
    }

    /** Saves error-log.txt, rpt.txt and snapshots.json of a run (masked) and records them in {@code saved}. */
    static void saveRunArtifacts(Services s, String reportId, Map<String, String> saved, DebugRun run) {
        if (!run.logEntries.isEmpty()) {
            save(s, reportId, saved, "error-log.txt", SecretMasker.maskText(LogService.fullText(run.logEntries)));
        }
        if (!run.rpts.isEmpty()) {
            StringBuilder rpt = new StringBuilder();
            for (DebugRun.Rpt entry : run.rpts) {
                rpt.append("=== ").append(entry.request.label).append(" (index ").append(entry.request.index)
                        .append(")\n").append(entry.parsed.tree(400)).append("\n--- raw\n").append(entry.text)
                        .append("\n\n");
            }
            save(s, reportId, saved, "rpt.txt", SecretMasker.maskText(rpt.toString()));
        }
        if (!run.snapshots.isEmpty()) {
            List<Object> maps = new ArrayList<>();
            for (Snapshot snapshot : run.snapshots) {
                maps.add(snapshot.toMap());
            }
            save(s, reportId, saved, "snapshots.json", SecretMasker.maskText(Json.pretty(maps)));
        }
    }

    static void save(Services s, String reportId, Map<String, String> saved, String name, String text) {
        s.artifacts.write(reportId, name, text);
        saved.put(name, s.artifacts.uri(reportId, name));
    }
}
