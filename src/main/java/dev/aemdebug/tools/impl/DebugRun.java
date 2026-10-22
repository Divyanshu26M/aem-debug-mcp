package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.browser.NetworkEntry;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.jdi.BreakpointSpec;
import dev.aemdebug.jdi.ExceptionWatchSpec;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdiSupport;
import dev.aemdebug.jdi.Snapshot;
import dev.aemdebug.logs.LogEntry;
import dev.aemdebug.logs.LogService;
import dev.aemdebug.rpt.RecentRequestsClient;
import dev.aemdebug.rpt.RptParser;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One capture window shared by debug_page and debug_capture: temporary JVM watches and breakpoints, an error.log
 * mark and a Recent Requests baseline at the start; snapshots, new log lines and Request Progress Tracker entries
 * at the end.
 */
final class DebugRun {

    static final int SETTLE_MS = 800;
    private static final int MAX_LOG_BYTES = 4 * 1024 * 1024;

    /** What to capture. */
    static final class Options {
        boolean jvm = true;
        boolean watchExceptions = true;
        boolean requestThreadsOnly = true;
        boolean rpt = true;
        List<String> packages = new ArrayList<>();
        List<String> breakpoints = new ArrayList<>();
    }

    /** Request Progress Tracker of one request. */
    static final class Rpt {
        final RecentRequestsClient.RecentRequest request;
        final String text;
        final RptParser.Result parsed;

        Rpt(RecentRequestsClient.RecentRequest request, String text, RptParser.Result parsed) {
            this.request = request;
            this.text = text;
            this.parsed = parsed;
        }
    }

    final Services s;
    final AemInstance instance;
    final long startedAt;
    final List<String> jvmNotes = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<Snapshot> snapshots = new ArrayList<>();
    final List<LogEntry> logEntries = new ArrayList<>();
    final List<Rpt> rpts = new ArrayList<>();
    final List<RecentRequestsClient.RecentRequest> newRequests = new ArrayList<>();
    boolean jvmActive;
    String logSource;
    long finishedAt;

    private final List<String> breakpointIds = new ArrayList<>();
    private final List<String> watchIds = new ArrayList<>();
    private LogService.Mark mark;
    private Set<String> rptBaseline;

    private DebugRun(Services s, AemInstance instance) {
        this.s = s;
        this.instance = instance;
        this.startedAt = System.currentTimeMillis();
    }

    static DebugRun start(Services s, ToolContext context, AemInstance instance, Options options) {
        DebugRun run = new DebugRun(s, instance);
        try {
            run.startJvm(context, options);
            run.startLogs(context);
            if (options.rpt) {
                run.startRpt(context);
            } else {
                run.notes.add("Request Progress Tracker skipped (rpt=false).");
            }
        } catch (RuntimeException e) {
            run.release();
            throw e;
        }
        return run;
    }

    private void startJvm(ToolContext context, Options options) {
        if (!options.jvm) {
            jvmNotes.add("JVM capture off (jvm=false).");
            return;
        }
        if (s.jdi == null) {
            jvmNotes.add("JVM capture unavailable: " + JdiSupport.unavailableMessage());
            return;
        }
        if (s.config.jdwpHost() == null) {
            jvmNotes.add("JVM capture disabled: no local JDWP host configured.");
            return;
        }
        JdiEngine engine = s.jdi;
        if (!engine.isAttached()) {
            try {
                context.progress("Attaching to JDWP " + s.config.jdwpHost() + ":" + s.config.jdwpPort());
                JvmTools.attach(s, 5000);
                jvmNotes.add("Attached to JDWP " + engine.endpoint() + "; it stays attached until jvm_connection "
                        + "action=detach, debug_cleanup or the idle timeout (IntelliJ cannot attach meanwhile).");
            } catch (ToolException e) {
                jvmNotes.add("JVM capture skipped: " + e.getMessage());
                return;
            }
        }
        jvmActive = true;
        if (options.watchExceptions) {
            ExceptionWatchSpec watch = new ExceptionWatchSpec();
            watch.packages.addAll(options.packages.isEmpty()
                    ? JvmTools.defaultWatchPackages(s, context) : JvmTools.patterns(options.packages));
            watch.temporary = true;
            if (options.requestThreadsOnly) {
                watch.requestFilter = "requests";
            }
            engine.addExceptionWatch(watch);
            watchIds.add(watch.id);
            jvmNotes.add("Temporary exception watch " + watch.id + " on " + String.join(", ", watch.packages)
                    + (options.requestThreadsOnly ? " (request threads)." : " (all threads)."));
        }
        for (String value : options.breakpoints) {
            addBreakpoint(context, engine, value);
        }
        int existing = engine.breakpointList().size() + engine.watchList().size()
                - breakpointIds.size() - watchIds.size();
        if (existing > 0) {
            jvmNotes.add(existing + " breakpoint(s)/watch(es) defined earlier also capture during this run.");
        }
    }

    /** Adds a temporary breakpoint; "Class:41 if title == null" carries a condition. */
    private void addBreakpoint(ToolContext context, JdiEngine engine, String value) {
        String location = value == null ? "" : value.trim();
        String condition = null;
        int split = location.indexOf(" if ");
        if (split > 0) {
            condition = location.substring(split + 4).trim();
            location = location.substring(0, split).trim();
        }
        try {
            BreakpointSpec spec = BreakpointSpec.parse(location);
            spec.condition = JvmTools.blankToNull(condition);
            spec.temporary = true;
            JvmTools.resolveLocation(s, context, spec);
            engine.addBreakpoint(spec);
            breakpointIds.add(spec.id);
            jvmNotes.add("Temporary breakpoint " + spec.describe().replace("\n", "\n  "));
        } catch (RuntimeException e) {
            jvmNotes.add("Breakpoint '" + value + "' ignored: " + e.getMessage());
        }
    }

    private void startLogs(ToolContext context) {
        try {
            mark = s.logs.mark(context, instance);
        } catch (RuntimeException e) {
            notes.add("error.log not captured: " + Text.oneLine(e.getMessage(), 200));
        }
    }

    private void startRpt(ToolContext context) {
        try {
            Set<String> baseline = new HashSet<>();
            for (RecentRequestsClient.RecentRequest request : s.recentRequests.list(context, instance)) {
                baseline.add(request.index);
            }
            rptBaseline = baseline;
        } catch (RuntimeException e) {
            notes.add("Request Progress Tracker unavailable: " + Text.oneLine(e.getMessage(), 200));
        }
    }

    /** Collects everything captured since {@link #start}; {@code rptCandidates} are looked up first. */
    void finish(ToolContext context, List<RequestCorrelator.BrowserRequest> rptCandidates, int settleMs, int maxRpt) {
        if (settleMs > 0) {
            JvmTools.pause(settleMs);
        }
        finishedAt = System.currentTimeMillis();
        if (jvmActive && s.jdi != null) {
            snapshots.addAll(s.jdi.snapshots().since(startedAt));
        }
        if (mark != null) {
            try {
                LogService.Result result = s.logs.since(context, instance, mark, MAX_LOG_BYTES);
                logEntries.addAll(result.entries);
                logSource = result.source + (result.truncated ? " (truncated)" : "");
            } catch (RuntimeException e) {
                notes.add("error.log could not be read: " + Text.oneLine(e.getMessage(), 200));
            }
        }
        if (rptBaseline != null) {
            collectRpt(context, rptCandidates, maxRpt);
        }
    }

    private void collectRpt(ToolContext context, List<RequestCorrelator.BrowserRequest> candidates, int maxRpt) {
        List<RecentRequestsClient.RecentRequest> after;
        try {
            after = s.recentRequests.list(context, instance);
        } catch (RuntimeException e) {
            notes.add("Recent Requests not read: " + Text.oneLine(e.getMessage(), 200));
            return;
        }
        for (RecentRequestsClient.RecentRequest request : after) {
            if (!rptBaseline.contains(request.index)) {
                newRequests.add(request);
            }
        }
        List<RecentRequestsClient.RecentRequest> chosen = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            for (RecentRequestsClient.RecentRequest request : newRequests) {
                if (chosen.size() >= maxRpt) {
                    break;
                }
                chosen.add(request);
            }
        } else {
            for (RequestCorrelator.BrowserRequest browser : candidates) {
                if (chosen.size() >= maxRpt) {
                    break;
                }
                for (RecentRequestsClient.RecentRequest request : newRequests) {
                    if (!chosen.contains(request) && sameRequest(browser, request)) {
                        chosen.add(request);
                        break;
                    }
                }
            }
        }
        for (RecentRequestsClient.RecentRequest request : chosen) {
            try {
                String text = s.recentRequests.rptText(context, instance, request.index);
                if (text != null && !text.isBlank()) {
                    rpts.add(new Rpt(request, text, RptParser.parse(text)));
                }
            } catch (RuntimeException e) {
                notes.add("RPT of " + request.label + " not read: " + Text.oneLine(e.getMessage(), 160));
            }
        }
    }

    boolean rptAvailable() {
        return rptBaseline != null;
    }

    boolean hasRptFor(RequestCorrelator.BrowserRequest browser) {
        for (Rpt rpt : rpts) {
            if (sameRequest(browser, rpt.request)) {
                return true;
            }
        }
        return false;
    }

    /** Removes the temporary breakpoints and watches of this run; the JDWP connection stays. */
    void release() {
        if (s.jdi == null) {
            return;
        }
        for (String id : breakpointIds) {
            s.jdi.removeBreakpoint(id);
        }
        for (String id : watchIds) {
            s.jdi.removeExceptionWatch(id);
        }
        breakpointIds.clear();
        watchIds.clear();
    }

    /**
     * Correlation events of this run. Without browser data, an RPT entry is attached to the single timed request
     * with the same method and path when there is exactly one.
     */
    List<RequestCorrelator.Event> events(boolean attachRptToTimedRequests) {
        List<RequestCorrelator.Event> events = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            events.add(new RequestCorrelator.Event(snapshot.isException() ? "exception" : "snapshot",
                    server(snapshot.requestKey), snapshot.time, snapshot.summary(), snapshot));
        }
        for (LogEntry entry : logEntries) {
            events.add(new RequestCorrelator.Event("log", server(entry.requestKey()), entry.timestamp(),
                    entry.level() + " " + Text.oneLine(entry.message(), 200), entry));
        }
        for (Rpt rpt : rpts) {
            String method = rpt.request.method == null ? "GET" : rpt.request.method.toUpperCase(Locale.ROOT);
            RequestCorrelator.ServerRequest target = new RequestCorrelator.ServerRequest(method,
                    rpt.request.pathOnly(), -1);
            if (attachRptToTimedRequests) {
                RequestCorrelator.ServerRequest timed = uniqueTimed(events, target);
                if (timed != null) {
                    target = timed;
                }
            }
            events.add(new RequestCorrelator.Event("rpt", target, startedAt, "RPT " + rpt.request.label, rpt));
        }
        return events;
    }

    private static RequestCorrelator.ServerRequest uniqueTimed(List<RequestCorrelator.Event> events,
                                                               RequestCorrelator.ServerRequest untimed) {
        RequestCorrelator.ServerRequest found = null;
        for (RequestCorrelator.Event event : events) {
            RequestCorrelator.ServerRequest request = event.request();
            if (request != null && request.timed() && request.method().equals(untimed.method())
                    && request.path().equals(untimed.path())) {
                if (found != null && !found.equals(request)) {
                    return null;
                }
                found = request;
            }
        }
        return found;
    }

    static RequestCorrelator.ServerRequest server(RequestKey key) {
        return key == null ? null : new RequestCorrelator.ServerRequest(key.method(), key.uri(), key.startMillis());
    }

    static boolean sameRequest(RequestCorrelator.BrowserRequest browser, RecentRequestsClient.RecentRequest request) {
        String method = request.method == null ? "GET" : request.method.toUpperCase(Locale.ROOT);
        return browser.method().equals(method)
                && browser.path().equals(RequestCorrelator.normalizePath(request.pathOnly()));
    }

    /** Browser requests of a render; ids are "n" + index into {@link RenderResult#network()}. */
    static List<RequestCorrelator.BrowserRequest> browserRequests(RenderResult render) {
        List<RequestCorrelator.BrowserRequest> out = new ArrayList<>();
        if (render == null) {
            return out;
        }
        List<NetworkEntry> network = render.network();
        for (int i = 0; i < network.size(); i++) {
            NetworkEntry entry = network.get(i);
            out.add(new RequestCorrelator.BrowserRequest("n" + i, entry.method(), entry.url(), entry.startedAt()));
        }
        return out;
    }

    /** Requests worth a Request Progress Tracker lookup: the document, failed requests, then XHR/fetch. */
    static List<RequestCorrelator.BrowserRequest> rptCandidates(RenderResult render,
                                                                List<RequestCorrelator.BrowserRequest> all) {
        List<RequestCorrelator.BrowserRequest> out = new ArrayList<>();
        if (render == null) {
            return out;
        }
        List<NetworkEntry> network = render.network();
        NetworkEntry document = render.documentRequest();
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < network.size() && i < all.size(); i++) {
                NetworkEntry entry = network.get(i);
                boolean pick;
                if (pass == 0) {
                    pick = entry == document;
                } else if (pass == 1) {
                    pick = entry.failed() || entry.status() >= 400;
                } else {
                    pick = "xhr".equals(entry.resourceType()) || "fetch".equals(entry.resourceType());
                }
                if (pick && !out.contains(all.get(i))) {
                    out.add(all.get(i));
                }
            }
        }
        return out;
    }

    static NetworkEntry entryFor(RequestCorrelator.BrowserRequest browser, List<NetworkEntry> network) {
        if (browser == null || browser.id() == null || !browser.id().startsWith("n") || network == null) {
            return null;
        }
        try {
            int index = Integer.parseInt(browser.id().substring(1));
            return index >= 0 && index < network.size() ? network.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
