package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.PageTarget;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.mapping.ComponentNode;
import dev.aemdebug.mapping.ComponentReport;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** page_component_map: which component rendered which part of the page. */
public final class PageComponentMapTool extends AbstractTool {

    private final Services s;

    public PageComponentMapTool(Services services) {
        super("page_component_map", "Page component map",
                "Maps the rendered page to AEM components: for every component resource of the page (and its template "
                        + "structure) it locates the DOM element (Core Components id, edit-mode markup, WCM debug "
                        + "comments or class order) and reports its box, whether it rendered empty, the Sling Model(s) "
                        + "bound to its resource type and the local model/HTL source files. Returns a screenshot with "
                        + "numbered boxes (#n) that match the table. Use component_inspect on a path to dig deeper.",
                PageRenderTool.commonRenderSchema()
                        .bool("onlyProblems", "Only list components with hints (empty, not found, ...) (default false)")
                        .integer("maxRows", "Maximum table rows (default 60)", 5, 500)
                        .bool("screenshot", "Include the annotated screenshot (default true)"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        PageTarget target = PageRenderTool.target(s, args);
        AemInstance instance = target.instance();
        String pagePath = pagePath(target);
        if (pagePath == null) {
            throw new ToolException(target.renderUrl() + " is not an AEM page under /content, so there are no "
                    + "components to map. Use page_render or debug_page for it.");
        }
        RenderRequest request = PageRenderTool.buildRequest(s, context, args, target).fullPage(true);
        ComponentMapper.Result result = s.mapper.mapAndRender(context, instance, pagePath, request);
        String reportId = s.artifacts.newReport();
        Map<String, String> saved = RenderReport.saveArtifacts(s.artifacts, reportId, result.render);
        List<Object> json = new ArrayList<>();
        for (ComponentNode node : result.components) {
            json.add(node.toMap());
        }
        s.artifacts.write(reportId, "components.json", Json.pretty(json));
        saved.put("components.json", s.artifacts.uri(reportId, "components.json"));
        byte[] annotatedFull = ComponentMapper.annotated(result, 80, true, 1600);
        if (annotatedFull != null) {
            s.artifacts.write(reportId, "screenshot-annotated-full.jpg", annotatedFull);
            saved.put("screenshot-annotated-full.jpg", s.artifacts.uri(reportId, "screenshot-annotated-full.jpg"));
        }

        StringBuilder out = new StringBuilder("## Component map ").append(reportId).append("  ").append(pagePath)
                .append(" (").append(target.describe()).append(", HTTP ").append(result.render.status()).append(")\n");
        out.append("Session: ").append(request.sessionNote()).append('\n');
        if (target.devServer()) {
            out.append("Loaded from the dev server: components are only located when it proxies this page itself to "
                    + target.instance().name() + " (many dev servers answer page URLs with their own app shell).\n");
        }
        out.append(ComponentReport.sources(result));
        out.append(ComponentReport.table(result, args.integer("maxRows", 60, 5, 500), args.bool("onlyProblems", false)));
        out.append(RenderReport.artifactsBlock(s.artifacts, reportId, saved));
        ToolResult toolResult = ToolResult.of(out.toString());
        if (args.bool("screenshot", true)) {
            byte[] annotated = ComponentMapper.annotated(result, 40, false, 1280);
            if (annotated != null) {
                toolResult.image(annotated, "image/jpeg");
            }
        }
        return toolResult;
    }

    /** AEM page path of a target: any path on an AEM instance (as before), only /content pages behind a dev server. */
    static String pagePath(PageTarget target) {
        return target.devServer() ? target.pagePath() : ContentService.pagePathOf(target.requestPath());
    }
}
