package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

/** aem_get_model_json: Sling Model exporter output. */
public final class AemGetModelJsonTool extends AbstractTool {

    private final Services s;

    public AemGetModelJsonTool(Services services) {
        super("aem_get_model_json", "Get Sling Model JSON",
                "Returns the Sling Model exporter output (path.model.json) for a page or component as AEM computes it "
                        + "at runtime - compare it with the JCR properties (aem_get_resource) to spot injection/mapping "
                        + "bugs such as a property that exists in JCR but is null in the model. Secrets are masked.",
                Schema.object()
                        .requiredStr("path", "Page or component path, e.g. /content/wknd/us/en or .../jcr:content/root/container/teaser")
                        .str("selector", "Exporter selector (default model)")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String path = args.requireStr("path");
        Object json = s.content.modelJson(context, instance, path, args.str("selector"));
        String body = ToolOutput.fenced("json", ToolOutput.maskedJson(json));
        return ToolResult.of("### Model JSON of " + path + " (" + instance.name() + ")\n"
                + ToolOutput.inlineOrArtifact(s, body, ToolOutput.INLINE_LIMIT, "model.json.md"));
    }
}
