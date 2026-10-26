package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.ResourceTree;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;

import java.util.Map;

/** aem_page_context: template, policies, CA config, language and replication status of a page. */
public final class AemPageContextTool extends AbstractTool {

    private final Services s;

    public AemPageContextTool(Services services) {
        super("aem_page_context", "Page context",
                "Summarizes the context a page renders in: template, page component, content policy of the page and "
                        + "(optionally) of one component, context-aware configuration (sling:configRef/cq:conf and the "
                        + "available CA configs), language, and replication status. Use it for policy/style-system "
                        + "issues and 'works on one site but not another' problems. Read-only.",
                Schema.object()
                        .requiredStr("path", "Page path or URL, e.g. /content/wknd/us/en (page editor URLs work too)")
                        .str("component", "Optional component path inside the page to resolve its policy")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        ContentService.PageContext pc = s.content.pageContext(context, instance, args.requireStr("path"),
                args.str("component"));
        Map<String, Object> p = pc.pageProperties;
        StringBuilder out = new StringBuilder("### Page context ").append(pc.pagePath).append(" (")
                .append(instance.name()).append(")\n");
        out.append("- title: ").append(ResourceTree.formatValue(p.get("jcr:title"))).append('\n');
        out.append("- page component: ").append(pc.pageResourceType).append('\n');
        out.append("- template: ").append(pc.template == null ? "(none)" : pc.template).append('\n');
        out.append("- page policy: ").append(pc.pagePolicy == null ? "(none)" : pc.pagePolicy).append('\n');
        out.append("- language: ").append(pc.language == null ? "?" : pc.language)
                .append(pc.languageSource == null ? "" : " (from " + pc.languageSource + ")").append('\n');
        out.append("- context-aware config: ").append(pc.configRef == null ? "(none found)" : pc.configRef)
                .append(pc.configRefSource == null ? "" : " (set on " + pc.configRefSource + ")").append('\n');
        if (!pc.caConfigs.isEmpty()) {
            out.append("  - sling:configs: ").append(String.join(", ", pc.caConfigs)).append('\n');
        }
        out.append("- last modified: ").append(ResourceTree.formatValue(p.get("cq:lastModified"))).append(" by ")
                .append(ResourceTree.formatValue(p.get("cq:lastModifiedBy"))).append('\n');
        Object replicated = p.get("cq:lastReplicated");
        out.append("- replication: ").append(replicated == null ? "never replicated"
                : Json.asString(p.get("cq:lastReplicationAction")) + " at " + replicated + " by "
                + Json.asString(p.get("cq:lastReplicatedBy"))).append('\n');
        if (pc.componentPath != null) {
            out.append("\n#### Component ").append(pc.componentPath).append('\n');
            out.append("- resource type: ").append(pc.componentResourceType).append('\n');
            out.append("- policy: ").append(pc.componentPolicy == null ? "(none mapped)" : pc.componentPolicy).append('\n');
            if (!pc.componentPolicyProperties.isEmpty()) {
                out.append(ToolOutput.fenced("json", ToolOutput.maskedJson(pc.componentPolicyProperties)));
            }
        }
        if (!pc.notes.isEmpty()) {
            out.append("\nNotes:\n");
            for (String note : pc.notes) {
                out.append("- ").append(note).append('\n');
            }
        }
        Object maskedProps = SecretMasker.maskTree(p);
        out.append("\nPage properties: ").append(Json.asMap(maskedProps).keySet()).append('\n');
        return ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), ToolOutput.INLINE_LIMIT, "page-context.md"));
    }
}
