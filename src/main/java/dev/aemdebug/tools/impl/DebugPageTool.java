package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.PageTarget;
import dev.aemdebug.browser.ImageUtil;
import dev.aemdebug.browser.NetworkEntry;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.mapping.ComponentNode;
import dev.aemdebug.mapping.ComponentReport;
import dev.aemdebug.rpt.RecentRequestsClient;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** debug_page: render + component map + server-side capture correlated per request, in one call. */
public final class DebugPageTool extends AbstractTool {

    private static final int MAX_LISTED_PROXIED = 8;

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
                        + "screenshots, logs, RPT, snapshots, report.md). Also works for a page of a local front-end "
                        + "dev server (path=http://localhost:3000/...): the browser data comes from the dev server and "
                        + "the server-side data from the AEM instance it proxies to. JVM capture needs the JDWP port to "
                        + "be free (detach IntelliJ first); without it the report contains everything else. Temporary "
                        + "watches and breakpoints are removed afterwards; the JDWP connection stays until "
                        + "jvm_connection action=detach, debug_cleanup or the idle timeout.",
                PageRenderTool.commonRenderSchema()
                        .strArray("breakpoints", "Temporary snapshot breakpoints for this render, e.g. "
                                + "com.mysite.core.models.HeroModel:41 or 'HeroModel.java:41 if title == null'")
                        .strArray("packages", "Throw-site packages for the exception watch (default: project "
                                + "packages + Sling Models + HTL)")
                        .bool("jvm", "Capture inside the JVM over JDWP (default true; attaches if the port is free)")
                        .bool("watchExceptions", "Watch exceptions while rendering (default true)")
                        .bool("rpt", "Read the Request Progress Tracker from the Recent Requests console (default true)")
                        .bool("components", "Map the page to AEM components (default true for AEM pages, false for "
                                + "dev-server pages: set it when the dev server proxies the page itself to AEM)")
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
    public ToolResult call(ToolContext context, Args args) throws IOException {
        PageTarget target = PageRenderTool.target(s, args);
        AemInstance instance = target.instance();
        String pagePath = PageComponentMapTool.pagePath(target);
        boolean mapComponents = pagePath != null && args.bool("components", !target.devServer());
        RenderRequest request = PageRenderTool.buildRequest(s, context, args, target).fullPage(true);

        DebugRun.Options options = new DebugRun.Options();
        options.jvm = args.bool("jvm", true);
        options.watchExceptions = args.bool("watchExceptions", true);
        options.rpt = args.bool("rpt", true);
        options.packages = JvmTools.strings(args.strList("packages"));
        options.breakpoints = JvmTools.strings(args.strList("breakpoints"));

        context.progress("Arming server-side capture on " + instance.name());
        DebugRun run = DebugRun.start(s, context, instance, options);
        ComponentMapper.Result mapping;
        List<RequestCorrelator.BrowserRequest> browser;
        List<RequestCorrelator.BrowserRequest> candidates;
        try {
            if (mapComponents) {
                context.progress("Rendering and mapping " + request.url());
                mapping = s.mapper.mapAndRender(context, instance, pagePath, request);
            } else {
                context.progress("Rendering " + request.url());
                mapping = new ComponentMapper.Result();
                mapping.pagePath = pagePath;
                mapping.render = s.browser.render(request);
            }
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
        if (mapComponents) {
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
        }
        DebugReport.saveRunArtifacts(s, reportId, saved, run);
        saved.put("report.md", s.artifacts.uri(reportId, "report.md"));

        Set<String> withEvents = withServerEvents(correlation);
        boolean documentReachedAem = document != null && reachedInstance(run, browser, network, document, withEvents);
        List<String> hints = DebugReport.pageHints(run, mapping, correlation,
                target.devServer() && !documentReachedAem ? null : document,
                PageRenderTool.accessHint(target, request, render));
        if (document != null && run.rptAvailable() && !candidates.isEmpty() && !run.hasRptFor(candidates.get(0))
                && (!target.devServer() || documentReachedAem)) {
            hints.add("The Recent Requests console kept no entry for the document request (it keeps 20 requests by "
                    + "default and the page's assets push it out). With the user's consent, aem_debug_toggle "
                    + "toggle=recent-requests" + (instance.isAuthor() ? "" : " instance=" + instance.name())
                    + " keeps more.");
        }
        if (target.devServer() && documentReachedAem && !mapComponents && pagePath != null) {
            hints.add("The dev server proxied the page itself to " + instance.name() + ": components=true maps its "
                    + "components.");
        }

        StringBuilder head = new StringBuilder();
        head.append("## Debug report ").append(reportId).append("  ").append(request.url()).append('\n');
        if (render != null) {
            head.append(PageRenderTool.headline(target, request, render));
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
        if (target.devServer()) {
            head.append(devServerSection(target, run, browser, network, document, documentReachedAem, withEvents));
        }
        head.append("\n### Components\n");
        if (mapComponents) {
            int maxRows = args.integer("maxComponents", 40, 5, 500);
            boolean onlyProblems = args.has("onlyProblems") ? args.bool("onlyProblems", false)
                    : mapping.components.size() > maxRows;
            head.append(ComponentReport.sources(mapping))
                    .append(ComponentReport.table(mapping, maxRows, onlyProblems)).append('\n');
        } else if (pagePath == null) {
            head.append("Not mapped: ").append(target.requestPath()).append(" is not an AEM page under /content.\n");
        } else if (target.devServer() && !args.has("components")) {
            head.append("Not mapped: the page was loaded from the dev server (pass components=true if it proxies "
                    + "this page itself to ").append(instance.name()).append(").\n");
        } else {
            head.append("Not mapped (components=false).\n");
        }

        String artifacts = RenderReport.artifactsBlock(s.artifacts, reportId, saved);
        String text = head + DebugReport.pageServerSide(run, correlation, network, false) + artifacts;
        String full = head + DebugReport.pageServerSide(run, correlation, network, true) + artifacts;
        s.artifacts.write(reportId, "report.md", SecretMasker.maskText(full));

        ToolResult result = ToolResult.of(text);
        if (args.bool("screenshot", true)) {
            byte[] image = mapComponents ? ComponentMapper.annotated(mapping, 40, false, 1280)
                    : render != null && render.viewportJpeg() != null
                    ? ImageUtil.toJpeg(render.viewportJpeg(), 1280, 0.75f) : null;
            if (image != null) {
                result.image(image, "image/jpeg");
            }
        }
        return result;
    }

    /**
     * Which browser requests of a dev-server page reached the AEM instance behind it: those found among the
     * instance's new Recent Requests or with server events on its request threads (same method and path).
     */
    static String devServerSection(PageTarget target, DebugRun run, List<RequestCorrelator.BrowserRequest> browser,
                                   List<NetworkEntry> network, NetworkEntry document, boolean documentReachedAem,
                                   Set<String> withEvents) {
        String name = target.instance().name();
        boolean requestsKnown = run.rptAvailable();
        boolean eventsKnown = run.logSource != null || run.jvmActive;
        StringBuilder sb = new StringBuilder("\n### Dev server\n");
        sb.append("- ").append(target.describe()).append(". The server side below comes from ").append(name)
                .append(" (error.log, Recent Requests, JVM), matched to the browser requests by method and path.\n");
        if (document != null) {
            sb.append("- Document: ");
            if (documentReachedAem) {
                sb.append("proxied to ").append(name).append(" (seen there).");
            } else if (requestsKnown) {
                sb.append("served by the dev server itself (not among ").append(name).append("'s requests), so the "
                        + "AEM document checks, the component map and the document's Request Progress Tracker do "
                        + "not apply.");
            } else {
                sb.append("not seen on ").append(name).append(" (its Recent Requests could not be read). Dev servers "
                        + "usually answer page URLs with their own app shell; then the component map and the "
                        + "document's Request Progress Tracker do not apply.");
            }
            sb.append('\n');
        }
        if (!requestsKnown && !eventsKnown) {
            sb.append("- Requests that reached ").append(name).append(": unknown (its Recent Requests, error.log and "
                    + "JVM were not read).\n");
            return sb.toString();
        }
        List<String> reached = new ArrayList<>();
        int local = 0;
        for (int i = 0; i < network.size() && i < browser.size(); i++) {
            NetworkEntry entry = network.get(i);
            if (!HostPolicy.isLocalHost(entry.host())) {
                continue;
            }
            local++;
            if (entry != document && reachedInstance(run, browser.get(i), withEvents)) {
                reached.add(entry.method() + " " + Text.truncate(entry.path(), 140) + " (HTTP "
                        + (entry.status() < 0 ? "-" : String.valueOf(entry.status())) + ", "
                        + Text.firstNonBlank(entry.resourceType(), "request") + ")");
            }
        }
        sb.append("- Requests that reached ").append(name).append(": ").append(reached.size()).append(" of ")
                .append(Text.plural(local, "local request")).append(requestsKnown
                        ? " (seen in its Recent Requests console, which keeps only the latest requests, or on its "
                        + "request threads)"
                        : " (seen on its request threads; Recent Requests not read, so requests without log lines or "
                        + "exceptions do not show up)")
                .append(reached.isEmpty() ? ".\n" : ":\n");
        for (int i = 0; i < Math.min(MAX_LISTED_PROXIED, reached.size()); i++) {
            sb.append("  - ").append(reached.get(i)).append('\n');
        }
        if (reached.size() > MAX_LISTED_PROXIED) {
            sb.append("  - ... ").append(reached.size() - MAX_LISTED_PROXIED).append(" more (network.txt)\n");
        }
        return sb.toString();
    }

    private static boolean reachedInstance(DebugRun run, List<RequestCorrelator.BrowserRequest> browser,
                                           List<NetworkEntry> network, NetworkEntry entry, Set<String> withEvents) {
        int index = network.indexOf(entry);
        return index >= 0 && index < browser.size() && reachedInstance(run, browser.get(index), withEvents);
    }

    private static boolean reachedInstance(DebugRun run, RequestCorrelator.BrowserRequest request,
                                           Set<String> withEvents) {
        if (withEvents.contains(request.id())) {
            return true;
        }
        for (RecentRequestsClient.RecentRequest recent : run.newRequests) {
            if (DebugRun.sameRequest(request, recent)) {
                return true;
            }
        }
        return false;
    }

    /** Ids of browser requests with server events in a correlation result. */
    static Set<String> withServerEvents(RequestCorrelator.Result correlation) {
        Set<String> ids = new HashSet<>();
        for (RequestCorrelator.Group group : correlation.requests()) {
            if (group.browser() != null) {
                ids.add(group.browser().id());
            }
        }
        return ids;
    }
}
