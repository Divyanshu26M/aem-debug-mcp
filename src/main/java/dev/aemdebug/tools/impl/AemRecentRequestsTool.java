package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.rpt.RecentRequestsClient;
import dev.aemdebug.rpt.RptParser;
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
import java.util.List;

/** aem_recent_requests: Sling Recent Requests with parsed Request Progress Tracker. */
public final class AemRecentRequestsTool extends AbstractTool {

    private final Services s;

    public AemRecentRequestsTool(Services services) {
        super("aem_recent_requests", "Recent requests (RPT)",
                "Lists the requests recorded by Sling's Recent Requests console and parses the Request Progress "
                        + "Tracker of one request: resolved resource and servlet, the include tree (which component "
                        + "path was rendered by which script and how long it took), and logged problems. The buffer is "
                        + "small (20 by default) - render the page right before. action=list (default) or detail.",
                Schema.object()
                        .enumStr("action", "list (default) or detail", "list", "detail")
                        .str("path", "Filter (list) or pick the newest request whose path contains this text (detail)")
                        .str("index", "Recent Requests index for detail")
                        .integer("maxTreeLines", "Lines of the timer tree in detail (default 60)", 10, 2000)
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String action = args.choice("action", "list", "list", "detail");
        String path = args.str("path");
        List<RecentRequestsClient.RecentRequest> requests = s.recentRequests.list(context, instance);
        if ("list".equals(action)) {
            StringBuilder out = new StringBuilder("### Recent requests (").append(instance.name()).append(", ")
                    .append(requests.size()).append(" recorded)\n");
            List<List<String>> rows = new ArrayList<>();
            for (RecentRequestsClient.RecentRequest request : requests) {
                if (path != null && (request.path == null || !request.path.contains(path))) {
                    continue;
                }
                rows.add(List.of(request.index, Text.nullToEmpty(request.method), Text.nullToEmpty(request.path),
                        Text.nullToEmpty(request.time), Text.nullToEmpty(request.user)));
            }
            if (rows.isEmpty()) {
                out.append("No matching requests. The buffer may have rotated; request the page again.\n");
            } else {
                out.append(Text.table(List.of("index", "method", "path", "time", "user"), rows, 140));
            }
            return ToolResult.of(out.toString());
        }
        String index = args.str("index");
        if (index == null && path != null) {
            for (RecentRequestsClient.RecentRequest request : requests) {
                if (request.path != null && request.path.contains(path)) {
                    index = request.index;
                    break;
                }
            }
        }
        if (index == null) {
            throw new ToolException("Give index (from action=list) or a path that matches a recorded request");
        }
        String rpt = s.recentRequests.rptText(context, instance, index);
        if (rpt == null) {
            throw new ToolException("Request " + index + " has no RPT (evicted from the buffer?)");
        }
        RptParser.Result parsed = RptParser.parse(rpt);
        String reportId = s.artifacts.newReport();
        s.artifacts.write(reportId, "rpt.txt", SecretMasker.maskText(rpt));
        return ToolResult.of(formatDetail(index, parsed, args.integer("maxTreeLines", 60, 10, 2000))
                + "Full RPT: " + s.artifacts.uri(reportId, "rpt.txt") + " = " + s.artifacts.resolve(reportId, "rpt.txt")
                + "\n");
    }

    static String formatDetail(String index, RptParser.Result parsed, int maxTreeLines) {
        StringBuilder out = new StringBuilder("### Request #").append(index).append(": ")
                .append(Text.nullToEmpty(parsed.method)).append(' ').append(Text.nullToEmpty(parsed.uri))
                .append(" - total ").append(parsed.formatDuration(parsed.total)).append('\n');
        out.append("- resource: ").append(parsed.resolvedPath).append(" [").append(parsed.resolvedType).append("]\n");
        out.append("- servlet: ").append(parsed.servlet).append('\n');
        if (!parsed.includes.isEmpty()) {
            out.append("- includes (").append(parsed.includes.size()).append("):\n");
            List<List<String>> rows = new ArrayList<>();
            for (RptParser.Include include : parsed.includes) {
                rows.add(List.of(include.resourcePath, include.resourceType, Text.nullToEmpty(include.script),
                        parsed.formatDuration(include.elapsed)));
                if (rows.size() >= 60) {
                    break;
                }
            }
            out.append(Text.table(List.of("resource", "type", "script", "time"), rows, 120));
        }
        if (!parsed.problems.isEmpty()) {
            out.append("- problems logged in RPT:\n");
            for (int i = 0; i < parsed.problems.size() && i < 20; i++) {
                out.append("  - ").append(Text.oneLine(parsed.problems.get(i), 300)).append('\n');
            }
        }
        out.append("\nTimer tree:\n").append(parsed.tree(maxTreeLines));
        return out.toString();
    }
}
