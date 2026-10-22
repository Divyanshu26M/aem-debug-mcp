package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.ResourceTree;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

import java.util.List;

/** aem_get_resource: JCR content as JSON/tree. */
public final class AemGetResourceTool extends AbstractTool {

    private final Services s;

    public AemGetResourceTool(Services services) {
        super("aem_get_resource", "Get JCR resource",
                "Reads a JCR resource (page, component, template, policy, /conf, /apps...) from the local AEM through "
                        + "the Sling GET servlet at a given depth and shows it as a compact tree (or JSON). Secrets "
                        + "(password/token/key properties, encrypted values) are masked. Read-only.",
                Schema.object()
                        .requiredStr("path", "JCR path, e.g. /content/wknd/us/en/jcr:content/root/container/teaser")
                        .integer("depth", "Depth below the path (default 1; -1 = infinity, falls back if too large)", -1, 20)
                        .strArray("properties", "Only show these property names (prefix wildcards like cq:* allowed)")
                        .enumStr("format", "tree (default) or json", "tree", "json")
                        .integer("maxLines", "Maximum tree lines (default 300)", 10, 5000)
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String path = ContentService.normalizePath(args.requireStr("path"));
        int depth = args.integer("depth", 1, -1, 20);
        ContentService.ResourceJson resource = s.content.resource(context, instance, path, depth);
        StringBuilder out = new StringBuilder();
        out.append("### ").append(path).append(" (").append(instance.name()).append(", depth ")
                .append(depth < 0 ? "infinity" : String.valueOf(depth)).append(")\n");
        if (resource.note != null) {
            out.append("Note: ").append(resource.note).append('\n');
        }
        List<String> properties = args.strList("properties");
        String body;
        if ("json".equals(args.choice("format", "tree", "tree", "json"))) {
            body = ToolOutput.fenced("json", ToolOutput.maskedJson(resource.json));
        } else {
            body = ResourceTree.render(path, resource.json, args.integer("maxLines", 300, 10, 5000), properties);
        }
        out.append(ToolOutput.inlineOrArtifact(s, body, ToolOutput.INLINE_LIMIT, "resource.txt"));
        return ToolResult.of(out.toString());
    }
}
