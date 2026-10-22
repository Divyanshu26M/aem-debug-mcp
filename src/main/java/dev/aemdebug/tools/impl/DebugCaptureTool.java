package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** debug_capture: record server-side activity while the user reproduces a problem. */
public final class DebugCaptureTool extends AbstractTool {

    private final Services s;
    private DebugRun active;

    public DebugCaptureTool(Services services) {
        super("debug_capture", "Capture server-side activity",
                "Records what happens inside AEM while the user reproduces a problem in their own browser or in the "
                        + "AEM author UI (dialog save, workflow, XHR, a page that only fails for them). action=start "
                        + "arms a temporary exception watch (project packages + Sling Models + HTL by default) and "
                        + "optional snapshot breakpoints, and marks error.log and the Recent Requests console; "
                        + "action=stop collects exceptions, snapshots, new log lines and Request Progress Tracker "
                        + "entries and groups them per Sling request (method, URI, start time); action=status shows the "
                        + "running capture. One capture at a time; stop removes the temporary watch and breakpoints. "
                        + "Prefer debug_page when rendering a page reproduces the problem.",
                Schema.object()
                        .requiredEnum("action", "start, stop or status", "start", "stop", "status")
                        .enumStr("instance", "author (default) or publish", "author", "publish")
                        .strArray("breakpoints", "Temporary snapshot breakpoints, e.g. "
                                + "com.mysite.core.servlets.FormServlet:57 or 'FormServlet.java:57 if name == null'")
                        .strArray("packages", "Throw-site packages for the exception watch (default: project "
                                + "packages + Sling Models + HTL)")
                        .bool("jvm", "Capture inside the JVM over JDWP (default true)")
                        .bool("watchExceptions", "Watch exceptions (default true)")
                        .bool("allThreads", "Also capture exceptions on non-request threads such as jobs, workflows "
                                + "and schedulers (default false)")
                        .str("request", "action=stop: only report requests whose URI contains this text"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String action = args.choice("action", "status", "start", "stop", "status");
        switch (action) {
            case "start":
                return start(context, args);
            case "stop":
                return stop(context, args);
            default:
                return status();
        }
    }

    private synchronized ToolResult start(ToolContext context, Args args) {
        if (active != null) {
            throw new ToolException("A capture is already running since " + Text.formatTime(active.startedAt) + " on "
                    + active.instance.name() + ". Call debug_capture action=stop first.");
        }
        AemInstance instance = s.instances.resolve(args.str("instance"));
        if (instance.isAuthor()) {
            s.auth.ensureSession(context, instance);
        }
        DebugRun.Options options = new DebugRun.Options();
        options.jvm = args.bool("jvm", true);
        options.watchExceptions = args.bool("watchExceptions", true);
        options.requestThreadsOnly = !args.bool("allThreads", false);
        options.packages = JvmTools.strings(args.strList("packages"));
        options.breakpoints = JvmTools.strings(args.strList("breakpoints"));
        DebugRun run = DebugRun.start(s, context, instance, options);
        active = run;

        StringBuilder out = new StringBuilder("## Capture started on ").append(instance.name()).append(" at ")
                .append(Text.formatTime(run.startedAt)).append('\n');
        for (String note : run.jvmNotes) {
            out.append("- JVM: ").append(note).append('\n');
        }
        for (String note : run.notes) {
            out.append("- note: ").append(note).append('\n');
        }
        out.append("Ask the user to reproduce the problem now (their browser or the AEM UI), then call debug_capture "
                + "action=stop.\n");
        return ToolResult.of(out.toString());
    }

    private synchronized ToolResult stop(ToolContext context, Args args) {
        DebugRun run = active;
        if (run == null) {
            throw new ToolException("No capture is running. Start one with debug_capture action=start.");
        }
        active = null;
        try {
            context.progress("Collecting server-side data");
            run.finish(context, new ArrayList<>(), 300, 8);
        } finally {
            run.release();
        }
        String filter = JvmTools.blankToNull(args.str("request"));
        List<RequestCorrelator.Event> events = new ArrayList<>();
        for (RequestCorrelator.Event event : run.events(true)) {
            if (filter == null || (event.request() != null && event.request().uri().contains(filter))) {
                events.add(event);
            }
        }
        RequestCorrelator.Result grouped = RequestCorrelator.group(events);

        String reportId = s.artifacts.newReport();
        Map<String, String> saved = new LinkedHashMap<>();
        DebugReport.saveRunArtifacts(s, reportId, saved, run);
        saved.put("report.md", s.artifacts.uri(reportId, "report.md"));
        String head = "## Capture " + reportId + " on " + run.instance.name() + ", "
                + Text.formatTime(run.startedAt) + " - " + Text.formatTime(run.finishedAt) + " ("
                + Text.formatDuration(run.finishedAt - run.startedAt) + ")\n";
        String artifacts = RenderReport.artifactsBlock(s.artifacts, reportId, saved);
        s.artifacts.write(reportId, "report.md",
                SecretMasker.maskText(head + DebugReport.captureBody(run, grouped, filter, true) + artifacts));
        return ToolResult.of(head + DebugReport.captureBody(run, grouped, filter, false) + artifacts);
    }

    private synchronized ToolResult status() {
        if (active == null) {
            return ToolResult.of("No capture running. debug_capture action=start begins one.");
        }
        StringBuilder out = new StringBuilder("Capture running on ").append(active.instance.name()).append(" since ")
                .append(Text.formatTime(active.startedAt)).append(" (")
                .append(Text.formatDuration(System.currentTimeMillis() - active.startedAt)).append(")\n");
        for (String note : active.jvmNotes) {
            out.append("- JVM: ").append(note).append('\n');
        }
        if (s.jdi != null && active.jvmActive) {
            out.append("- snapshots so far: ").append(s.jdi.snapshots().since(active.startedAt).size()).append('\n');
        }
        return ToolResult.of(out.toString());
    }

    /** Stops a running capture without a report (debug_cleanup); returns a note or null. */
    synchronized String discard() {
        DebugRun run = active;
        if (run == null) {
            return null;
        }
        active = null;
        run.release();
        return "Stopped the running debug_capture (started " + Text.formatTime(run.startedAt) + ") without a report.";
    }
}
