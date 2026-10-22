package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.PageUrls;
import dev.aemdebug.browser.NetworkEntry;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.mapping.ComponentNode;
import dev.aemdebug.mapping.ComponentReport;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** debug_page: render + component map + server-side capture correlated per request, in one call. */
public final class DebugPageTool extends AbstractTool {

    private final Services s;

    public DebugPageTool(Services services) {
        super("debug_page", "Debug page",
                "One-call debugging of a local AEM page. Renders it in a headless browser (screenshot, HTTP status, "
                        + "failed requests, console errors), maps the page to components (numbered boxes #n, empty "
                        + "components, Sling Models and local source files) and, while rendering, captures what "
                        + "happened on the server for exactly these requests: exceptions at the throw site (caught or "
                        + "not) in project code, Sling Models and HTL, optional snapshot breakpoints, new error.log "
                        + "lines and the Request Progress Tracker - matched to the browser requests via the Sling "
                        + "request thread. Returns a Debug Report with hints and saves full artifacts (HTML, "
                        + "screenshots, logs, RPT, snapshots, report.md). JVM capture needs the JDWP port to be free "
                        + "(detach IntelliJ first); without it the report contains everything else. Temporary watches "
                        + "and breakpoints are removed afterwards; the JDWP connection stays until jvm_connection "
                        + "action=detach, debug_cleanup or the idle timeout.",
                PageRenderTool.commonRenderSchema()
                        .strArray("breakpoints", "Temporary snapshot breakpoints for this render, e.g. "
                                + "com.mysite.core.models.HeroModel:41 or 'HeroModel.java:41 if title == null'")
                        .strArray("packages", "Throw-site packages for the exception watch (default: project "
                                + "packages + Sling Models + HTL)")
                        .bool("jvm", "Capture inside the JVM over JDWP (default true; attaches if the port is free)")
                        .bool("watchExceptions", "Watch exceptions while rendering (default true)")
                        .bool("rpt", "Read the Request Progress Tracker from the Recent Requests console (default true)")
                        .bool("onlyProblems", "Component table: only components with hints (default: when many)")
                        .integer("maxComponents", "Maximum component table rows (default 40)", 5, 500)
                        .bool("screenshot", "Include the annotated screenshot (default true)"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        RenderRequest request = PageRenderTool.buildRequest(s, context, args, instance).fullPage(true);
        String pagePath = ContentService.pagePathOf(PageUrls.toRequestPath(args.requireStr("path")));

        DebugRun.Options options = new DebugRun.Options();
        options.jvm = args.bool("jvm", true);
        options.watchExceptions = args.bool("watchExceptions", true);
        options.rpt = args.bool("rpt", true);
        options.packages = JvmTools.strings(args.strList("packages"));
        options.breakpoints = JvmTools.strings(args.strList("breakpoints"));

        context.progress("Arming server-side capture");
        DebugRun run = DebugRun.start(s, context, instance, options);
        ComponentMapper.Result mapping;
        List<RequestCorrelator.BrowserRequest> browser;
        List<RequestCorrelator.BrowserRequest> candidates;
        try {
            context.progress("Rendering and mapping " + request.url());
            mapping = s.mapper.mapAndRender(context, instance, pagePath, request);
            browser = DebugRun.browserRequests(mapping.render);
            candidates = DebugRun.rptCandidates(mapping.render, browser);
            context.progress("Collecting server-side data");
            run.finish(context, candidates, DebugRun.SETTLE_MS, 4);
        } finally {
            run.release();
        }
        RenderResult render = mapping.render;
        List<NetworkEntry> network = render == null ? new ArrayList<>() : render.network();
        NetworkEntry document = render == null ? null : render.documentRequest();
        RequestCorrelator.Result correlation = RequestCorrelator.correlate(browser, run.events(false),
                RequestCorrelator.DEFAULT_TOLERANCE_MILLIS);

        String reportId = s.artifacts.newReport();
        Map<String, String> saved = RenderReport.saveArtifacts(s.artifacts, reportId, render);
        List<Object> components = new ArrayList<>();
        for (ComponentNode node : mapping.components) {
            components.add(node.toMap());
        }
        DebugReport.save(s, reportId, saved, "components.json", Json.pretty(components));
        byte[] annotatedFull = ComponentMapper.annotated(mapping, 80, true, 1600);
        if (annotatedFull != null) {
            s.artifacts.write(reportId, "screenshot-annotated-full.jpg", annotatedFull);
            saved.put("screenshot-annotated-full.jpg", s.artifacts.uri(reportId, "screenshot-annotated-full.jpg"));
        }
        DebugReport.saveRunArtifacts(s, reportId, saved, run);
        saved.put("report.md", s.artifacts.uri(reportId, "report.md"));

        List<String> hints = DebugReport.pageHints(run, mapping, correlation, document);
        if (document != null && run.rptAvailable() && !candidates.isEmpty() && !run.hasRptFor(candidates.get(0))) {
            hints.add("The Recent Requests console kept no entry for the document request (it keeps 20 requests by "
                    + "default and the page's assets push it out). With the user's consent, aem_debug_toggle "
                    + "toggle=recent-requests keeps more.");
        }

        StringBuilder head = new StringBuilder();
        head.append("## Debug report ").append(reportId).append("  ").append(request.url()).append('\n');
        if (render != null) {
            head.append(instance.name()).append(", ").append(request.width()).append('x').append(request.height())
                    .append(", browser ").append(render.channel()).append(" - HTTP ").append(render.status())
                    .append(" - load ").append(Text.formatDuration(render.loadedAt() - render.startedAt()));
            if (render.title() != null && !render.title().isBlank()) {
                head.append(" - title \"").append(Text.oneLine(render.title(), 100)).append('"');
            }
            head.append('\n');
        }
        if (!hints.isEmpty()) {
            head.append("\n### Hints\n");
            for (String hint : hints) {
                head.append("- ").append(hint).append('\n');
            }
        }
        if (render != null) {
            head.append("\n### Browser\n").append(RenderReport.summary(render, 10));
        }
        int maxRows = args.integer("maxComponents", 40, 5, 500);
        boolean onlyProblems = args.has("onlyProblems") ? args.bool("onlyProblems", false)
                : mapping.components.size() > maxRows;
        head.append("\n### Components\n").append(ComponentReport.sources(mapping))
                .append(ComponentReport.table(mapping, maxRows, onlyProblems)).append('\n');

        String artifacts = RenderReport.artifactsBlock(s.artifacts, reportId, saved);
        String text = head + DebugReport.pageServerSide(run, correlation, network, false) + artifacts;
        String full = head + DebugReport.pageServerSide(run, correlation, network, true) + artifacts;
        s.artifacts.write(reportId, "report.md", SecretMasker.maskText(full));

        ToolResult result = ToolResult.of(text);
        if (args.bool("screenshot", true)) {
            byte[] annotated = ComponentMapper.annotated(mapping, 40, false, 1280);
            if (annotated != null) {
                result.image(annotated, "image/jpeg");
            }
        }
        return result;
    }
}
