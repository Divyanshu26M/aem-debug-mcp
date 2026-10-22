package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.ResourceTree;
import dev.aemdebug.aem.SlingModelRegistry;
import dev.aemdebug.browser.ImageUtil;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.logs.LogEntry;
import dev.aemdebug.logs.LogService;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.mapping.ComponentNode;
import dev.aemdebug.mapping.Hints;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** component_inspect: deep dive into one component instance. */
public final class ComponentInspectTool extends AbstractTool {

    private final Services s;

    public ComponentInspectTool(Services services) {
        super("component_inspect", "Inspect component",
                "Deep dive into one component instance (by resource path): JCR properties, Sling Model JSON as AEM "
                        + "computes it, the model classes bound to its resource type (runtime) and their local source "
                        + "with injected fields compared against the JCR properties, HTL scripts with data-sly-use, "
                        + "content policy, the rendered HTML fragment and a cropped screenshot of the element, plus "
                        + "related error.log entries. Produces concrete hints (e.g. 'JCR has jcr:title but field "
                        + "title injects title').",
                Schema.object()
                        .requiredStr("path", "Component resource path, e.g. /content/wknd/us/en/jcr:content/root/container/teaser")
                        .bool("render", "Render the page to get the HTML fragment and element screenshot (default true)")
                        .integer("logMinutes", "Look for related log entries in the last N minutes (default 60)", 1, 1440)
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) throws IOException {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        String path = ContentService.normalizePath(args.requireStr("path"));
        if (!path.contains("/jcr:content")) {
            throw new ToolException("Give the component resource path (inside a page's jcr:content), e.g. "
                    + "/content/site/en/jcr:content/root/container/teaser");
        }
        String pagePath = ContentService.pagePathOf(path);
        Object json = s.content.resource(context, instance, path, 1).json;
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Json.asMap(json).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                properties.put(entry.getKey(), entry.getValue());
            }
        }
        String resourceType = Json.asString(properties.get("sling:resourceType"));
        StringBuilder out = new StringBuilder("## Component ").append(path).append(" (").append(instance.name())
                .append(")\n");
        out.append("resourceType: ").append(resourceType).append('\n');
        List<String> hints = new ArrayList<>();

        out.append("\n### JCR\n").append(ResourceTree.render(path, json, 80, null));

        Map<String, Object> modelJson = null;
        out.append("\n### Model JSON (").append(path).append(".model.json)\n");
        try {
            Object model = s.content.modelJson(context, instance, path, null);
            modelJson = Json.asMap(model);
            out.append(ToolOutput.fenced("json", Text.truncate(ToolOutput.maskedJson(model), 4000)));
        } catch (ToolException e) {
            out.append(e.getMessage()).append('\n');
        }

        out.append("\n### Sling Models\n");
        List<String> runtime = new ArrayList<>();
        try {
            SlingModelRegistry registry = s.felix.slingModels(context, instance);
            runtime.addAll(registry.modelsFor(resourceType));
            out.append("- runtime bindings for ").append(resourceType).append(": ")
                    .append(runtime.isEmpty() ? "none" : String.join(", ", runtime)).append('\n');
        } catch (RuntimeException e) {
            out.append("- runtime registry unavailable: ").append(Text.oneLine(e.getMessage(), 160)).append('\n');
        }
        ProjectIndex index = s.projects.get(context);
        List<ProjectModel.SlingModel> localModels = new ArrayList<>();
        if (index != null && resourceType != null) {
            for (ProjectModel.SlingModel model : index.modelsForResourceType(resourceType)) {
                localModels.add(model);
            }
            for (String runtimeModel : runtime) {
                ProjectModel.SlingModel model = index.modelByClass(runtimeModel);
                if (model != null && !localModels.contains(model)) {
                    localModels.add(model);
                }
            }
            for (ProjectModel.HtlScript script : index.scriptsFor(resourceType)) {
                out.append("- HTL ").append(index.relative(script.file)).append(" uses ").append(script.useObjects)
                        .append('\n');
                for (String use : script.useObjects.values()) {
                    ProjectModel.SlingModel model = index.modelByClass(use);
                    if (model != null && !localModels.contains(model)) {
                        localModels.add(model);
                    }
                }
            }
            ProjectModel.Component component = index.component(resourceType);
            if (component != null && component.superType != null) {
                out.append("- component ").append(resourceType).append(" -> super ").append(component.superType)
                        .append('\n');
            }
            for (ProjectModel.SlingModel model : localModels) {
                out.append("- local model ").append(model.className).append(" (").append(index.relative(model.file))
                        .append(':').append(model.modelAnnotationLine).append(")\n");
                for (ProjectModel.InjectedField field : model.fields) {
                    Object jcrValue = properties.get(field.lookupName());
                    out.append("    - ").append(field.name).append(" <- @").append(field.annotation).append(" '")
                            .append(field.lookupName()).append("' line ").append(field.line).append(": JCR ")
                            .append(jcrValue == null ? "(missing)" : ResourceTree.formatValue(SecretMasker.maskTree(jcrValue)))
                            .append(field.optional ? "" : " [required]").append('\n');
                }
                hints.addAll(Hints.modelVsContent(model, properties));
            }
        } else if (index == null) {
            out.append("- no local project indexed (set AEM_PROJECT_DIR or run project_index)\n");
        }
        hints.addAll(Hints.modelJsonVsContent(modelJson, properties));

        try {
            ContentService.PageContext pc = s.content.pageContext(context, instance, pagePath, path);
            out.append("\n### Policy\n- ").append(pc.componentPolicy == null ? "no policy mapped" : pc.componentPolicy)
                    .append('\n');
            if (!pc.componentPolicyProperties.isEmpty()) {
                out.append(ToolOutput.fenced("json", Text.truncate(ToolOutput.maskedJson(pc.componentPolicyProperties), 2000)));
            }
        } catch (ToolException e) {
            out.append("\n### Policy\n- unavailable: ").append(e.getMessage()).append('\n');
        }

        byte[] elementShot = null;
        if (args.bool("render", true)) {
            out.append("\n### Rendered\n");
            try {
                Args renderArgs = Args.of("path", pagePath, "instance", instance.name());
                RenderRequest request = PageRenderTool.buildRequest(s, context, renderArgs, instance).fullPage(true);
                ComponentMapper.Result map = s.mapper.mapAndRender(context, instance, pagePath, request);
                ComponentNode node = map.byPath(path);
                if (node == null) {
                    out.append("- not part of the page's component list\n");
                } else {
                    out.append("- located by ").append(node.found ? node.locatedBy : "nothing (not found)")
                            .append(", box ").append(node.box()).append(", text ").append(node.textLength)
                            .append(" chars, ").append(node.images).append(" media\n");
                    hints.addAll(node.hints);
                    if (node.htmlSnippet != null) {
                        out.append(ToolOutput.fenced("html", Text.truncate(node.htmlSnippet, 2500)));
                    }
                    if (node.found && node.width > 1 && node.height > 1 && map.render.fullPagePng() != null) {
                        try {
                            elementShot = ImageUtil.crop(map.render.fullPagePng(), node.x, node.y, node.width,
                                    node.height, 8, 1000);
                        } catch (IOException e) {
                            out.append("- element screenshot failed: ").append(e.getMessage()).append('\n');
                        }
                    }
                }
            } catch (ToolException e) {
                out.append("- render failed: ").append(e.getMessage()).append('\n');
            }
        }

        out.append("\n### Related log entries (last ").append(args.integer("logMinutes", 60, 1, 1440)).append(" min)\n");
        try {
            LogService.Query query = new LogService.Query();
            query.minSeverity = LogEntry.severity("WARN");
            query.sinceMillis = System.currentTimeMillis() - args.integer("logMinutes", 60, 1, 1440) * 60_000L;
            query.limit = 15;
            List<String> needles = new ArrayList<>();
            needles.add(Pattern.quote(path));
            for (ProjectModel.SlingModel model : localModels) {
                needles.add(Pattern.quote(model.className));
            }
            for (String model : runtime) {
                needles.add(Pattern.quote(model));
            }
            query.grep = Pattern.compile(String.join("|", needles));
            LogService.Result logs = s.logs.read(context, instance, query, 8 * 1024 * 1024);
            if (logs.entries.isEmpty()) {
                out.append("none\n");
            } else {
                out.append(SecretMasker.maskText(LogService.format(logs.entries, 6)));
                for (LogEntry entry : logs.entries) {
                    if (entry.message().contains("MissingElementsException") || entry.fullText().contains("Could not inject")) {
                        hints.add("error.log reports a model injection failure: " + Text.oneLine(entry.message(), 160));
                        break;
                    }
                }
            }
        } catch (ToolException e) {
            out.append("unavailable: ").append(e.getMessage()).append('\n');
        }

        if (!hints.isEmpty()) {
            out.append("\n### Hints\n");
            for (String hint : hints) {
                out.append("- ").append(hint).append('\n');
            }
        }
        ToolResult result = ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), 20_000, "component-inspect.md"));
        if (elementShot != null) {
            result.image(elementShot, "image/jpeg");
        }
        return result;
    }
}
