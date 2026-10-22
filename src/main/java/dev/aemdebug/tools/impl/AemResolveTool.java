package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.PageUrls;
import dev.aemdebug.aem.ResolverClient;
import dev.aemdebug.rpt.RecentRequestsClient;
import dev.aemdebug.rpt.RptParser;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.util.List;
import java.util.Map;

/** aem_resolve: which resource and which servlet/script handle a URL. */
public final class AemResolveTool extends AbstractTool {

    private final Services s;

    public AemResolveTool(Services services) {
        super("aem_resolve", "Resolve URL",
                "Explains how the local AEM resolves a URL: decomposition (path/selectors/extension/suffix), the "
                        + "resource and its sling:resourceType/superType, and the candidate servlets/scripts in order "
                        + "of preference (Sling Servlet Resolver console). If the request is in Recent Requests, the "
                        + "script actually used and the included components are added. Read-only.",
                Schema.object()
                        .requiredStr("url", "Request path, e.g. /content/wknd/us/en.html or /content/x.model.json")
                        .enumStr("method", "HTTP method (default GET)", "GET", "POST", "HEAD")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String url = PageUrls.pathOnly(PageUrls.toRequestPath(args.requireStr("url")));
        String method = args.choice("method", "GET", "GET", "POST", "HEAD").toUpperCase();
        ResolverClient.Resolution r = s.resolver.resolve(context, instance, url, method);
        StringBuilder out = new StringBuilder("### Resolution of ").append(method).append(' ').append(url)
                .append(" (").append(instance.name()).append(")\n");
        if (!r.decomposed.isEmpty()) {
            out.append("- decomposed: ").append(r.decomposed).append('\n');
        }
        out.append("- resource: ").append(r.resourcePath == null ? "(not found or not readable)" : r.resourcePath);
        if (r.resourceType != null) {
            out.append(" [").append(r.resourceType);
            if (r.resourceSuperType != null) {
                out.append(" -> super ").append(r.resourceSuperType);
            }
            out.append(']');
        }
        if (r.primaryType != null) {
            out.append(" (").append(r.primaryType).append(')');
        }
        out.append('\n');
        if (!r.candidates.isEmpty()) {
            out.append("- candidate servlets/scripts (first wins):\n");
            for (int i = 0; i < r.candidates.size() && i < 15; i++) {
                out.append("  ").append(i + 1).append(". ").append(Text.oneLine(r.candidates.get(i), 200)).append('\n');
            }
        } else {
            out.append("- candidates: none listed").append(r.consoleNote == null ? "" : " (" + r.consoleNote + ")")
                    .append('\n');
        }
        appendRecentRequest(context, instance, url, out);
        return ToolResult.of(out.toString());
    }

    private void appendRecentRequest(ToolContext context, AemInstance instance, String url, StringBuilder out) {
        try {
            List<RecentRequestsClient.RecentRequest> recent = s.recentRequests.list(context, instance);
            for (RecentRequestsClient.RecentRequest request : recent) {
                if (url.equals(request.pathOnly())) {
                    String rpt = s.recentRequests.rptText(context, instance, request.index);
                    if (rpt == null) {
                        return;
                    }
                    RptParser.Result parsed = RptParser.parse(rpt);
                    out.append("- last recorded request (Recent Requests #").append(request.index).append("): servlet ")
                            .append(parsed.servlet).append(", total ").append(parsed.formatDuration(parsed.total))
                            .append('\n');
                    Map<String, String> scripts = parsed.scriptsByPath();
                    int shown = 0;
                    for (Map.Entry<String, String> entry : scripts.entrySet()) {
                        if (shown++ >= 15) {
                            out.append("  ... more in aem_recent_requests\n");
                            break;
                        }
                        out.append("  include ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n');
                    }
                    return;
                }
            }
            out.append("- not in Recent Requests; request it (page_render) and run aem_recent_requests for the RPT\n");
        } catch (RuntimeException e) {
            out.append("- Recent Requests unavailable: ").append(Text.oneLine(e.getMessage(), 200)).append('\n');
        }
    }
}
