package dev.aemdebug.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.FelixConsole;
import dev.aemdebug.aem.SlingModelRegistry;
import dev.aemdebug.browser.BrowserEngine;
import dev.aemdebug.browser.ImageUtil;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectIndexService;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a rendered page to its components: the page's JCR tree (plus template structure) is the authoritative list;
 * elements are located in the browser DOM through Core Components ids, edit-mode editables, WCM debug comments or
 * class order; runtime Sling Models and the local project index add model classes and source files.
 */
public final class ComponentMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentMapper.class);

    static final String PROBE = String.join("\n",
            "(input) => {",
            "  const out = {};",
            "  const describe = (el, how) => {",
            "    const r = el.getBoundingClientRect();",
            "    const style = window.getComputedStyle(el);",
            "    const text = (el.innerText || '').trim();",
            "    return { found: true, how: how, x: r.left + window.scrollX, y: r.top + window.scrollY,",
            "      w: r.width, h: r.height, text: text.slice(0, 160), textLength: text.length,",
            "      images: el.querySelectorAll('img,picture,video,svg,canvas').length,",
            "      visible: r.width > 0 && r.height > 0 && style.visibility !== 'hidden' && style.display !== 'none',",
            "      tag: el.tagName.toLowerCase(), id: el.id || null, html: el.outerHTML.slice(0, 3000) };",
            "  };",
            "  for (const c of input.candidates) {",
            "    let el = null; let how = null;",
            "    if (c.coreId) { el = document.getElementById(c.coreId); how = 'core-id'; }",
            "    if (!el && c.selector) {",
            "      try { el = document.querySelector(c.selector); how = c.selectorSource; } catch (e) { el = null; }",
            "      if (el && c.classes && c.classes.length && !c.classes.some(k => el.classList.contains(k))) { el = null; }",
            "    }",
            "    if (!el && c.cls) {",
            "      const all = document.getElementsByClassName(c.cls);",
            "      if (all.length === c.clsTotal && all[c.clsIndex]) { el = all[c.clsIndex]; how = 'class-order'; }",
            "    }",
            "    out[c.key] = el ? describe(el, how) : { found: false };",
            "  }",
            "  const dataLayer = {};",
            "  document.querySelectorAll('[data-cmp-data-layer]').forEach(e => {",
            "    try { const j = JSON.parse(e.getAttribute('data-cmp-data-layer'));",
            "      for (const k in j) { dataLayer[k] = j[k]['@type'] || null; } } catch (x) { }",
            "  });",
            "  return { components: out, dataLayer: dataLayer, docHeight: document.documentElement.scrollHeight };",
            "}");

    /** Mapping plus the render it was made from. */
    public static final class Result {
        public String pagePath;
        public final List<ComponentNode> components = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final List<String> sources = new ArrayList<>();
        public RenderResult render;
        public Map<String, Object> pageProperties = new LinkedHashMap<>();

        public ComponentNode byPath(String path) {
            for (ComponentNode node : components) {
                if (node.path.equals(path)) {
                    return node;
                }
            }
            return null;
        }

        public ComponentNode byNumber(int number) {
            for (ComponentNode node : components) {
                if (node.number == number) {
                    return node;
                }
            }
            return null;
        }
    }

    private final AemClient aem;
    private final ContentService content;
    private final FelixConsole felix;
    private final BrowserEngine browser;
    private final ProjectIndexService projects;

    public ComponentMapper(AemClient aem, ContentService content, FelixConsole felix, BrowserEngine browser,
                           ProjectIndexService projects) {
        this.aem = aem;
        this.content = content;
        this.felix = felix;
        this.browser = browser;
        this.projects = projects;
    }

    /** Collects structure, renders the page with the DOM probe and joins everything. */
    public Result mapAndRender(ToolContext context, AemInstance instance, String pagePath, RenderRequest request) {
        Result result = new Result();
        result.pagePath = pagePath;
        context.progress("Reading the component structure of " + pagePath);
        List<ComponentNode> nodes = collectStructure(context, instance, pagePath, result);
        List<Map<String, Object>> candidates = candidates(nodes);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("candidates", candidates);
        request.probe(PROBE, input);
        context.progress("Rendering " + request.url());
        result.render = browser.render(request);
        if (result.render.probeError() != null) {
            result.notes.add("DOM probe failed: " + result.render.probeError());
        }
        applyProbe(nodes, result.render.probeResult());
        enrich(context, instance, nodes, result);
        number(nodes);
        result.components.addAll(nodes);
        return result;
    }

    // ------------------------------------------------------------------ structure

    List<ComponentNode> collectStructure(ToolContext context, AemInstance instance, String pagePath, Result result) {
        ContentService.ResourceJson page = content.resource(context, instance, pagePath + "/jcr:content", -1);
        if (page.note != null) {
            result.notes.add(page.note + " - components below that depth are not listed");
        }
        result.pageProperties = new LinkedHashMap<>(Json.asMap(page.json));
        result.pageProperties.values().removeIf(v -> v instanceof Map<?, ?>);
        List<JcrComponents.Resource> resources = new ArrayList<>(
                JcrComponents.flatten(pagePath + "/jcr:content", page.json, false));
        result.sources.add("jcr");
        String template = Json.asString(Json.asMap(page.json).get("cq:template"));
        if (template != null && template.startsWith("/conf/")) {
            try {
                ContentService.ResourceJson structure =
                        content.resource(context, instance, template + "/structure/jcr:content", -1);
                for (JcrComponents.Resource resource
                        : JcrComponents.flatten(template + "/structure/jcr:content", structure.json, true)) {
                    if (resource.depth > 0) {
                        resources.add(resource);
                    }
                }
                result.sources.add("template-structure");
            } catch (ToolException e) {
                result.notes.add("template structure not readable: " + e.getMessage());
            }
        }

        StructureParser.Structure markup = new StructureParser.Structure();
        try {
            String url = ContentService.encodePath(pagePath) + ".html" + (instance.isAuthor() ? "?wcmmode=edit" : "");
            AemResponse html = aem.get(context, instance, url);
            if (html.ok()) {
                markup = StructureParser.parse(html.text());
                if (!markup.editables.isEmpty()) {
                    result.sources.add("edit-markup");
                }
                if (!markup.debugComments.isEmpty()) {
                    result.sources.add("wcm-debug-comments");
                }
            } else {
                result.notes.add("structural HTML request answered HTTP " + html.status());
            }
        } catch (ToolException e) {
            result.notes.add("structural HTML not available: " + e.getMessage());
        }
        if (instance.isAuthor() && markup.editables.isEmpty()) {
            result.notes.add("no edit-mode editables found (cq data-path); mapping relies on Core Components ids");
        }

        List<ComponentNode> nodes = new ArrayList<>();
        Map<String, Integer> classCounts = new HashMap<>();
        for (JcrComponents.Resource resource : resources) {
            if (resource.depth == 0) {
                continue;
            }
            ComponentNode node = new ComponentNode();
            node.path = resource.path;
            node.resourceType = resource.resourceType;
            node.depth = resource.depth;
            node.fromTemplate = resource.fromTemplate;
            node.properties.putAll(resource.properties);
            Object explicitId = resource.properties.get("id");
            node.coreId = explicitId instanceof String && !((String) explicitId).isBlank()
                    ? (String) explicitId : CoreComponentIds.id(resource.resourceType, resource.path);
            StructureParser.Editable editable = markup.editables.get(resource.path);
            StructureParser.DebugComment comment = markup.debugComments.get(resource.path);
            if (editable != null && editable.selector != null) {
                node.selector = editable.selector;
                node.properties.put("_wrapperClasses", editable.wrapperClasses);
            } else if (comment != null && comment.selector != null) {
                node.selector = comment.selector;
            }
            if (comment != null) {
                node.script = comment.servlet;
                node.renderTime = comment.totalTime;
            }
            String cls = lastSegment(resource.resourceType);
            int index = classCounts.getOrDefault(cls, 0);
            classCounts.put(cls, index + 1);
            node.properties.put("_classIndex", index);
            nodes.add(node);
        }
        for (ComponentNode node : nodes) {
            node.parentPath = parentOf(node.path, nodes);
            node.properties.put("_classTotal", classCounts.getOrDefault(lastSegment(node.resourceType), 0));
        }
        return nodes;
    }

    private static String parentOf(String path, List<ComponentNode> nodes) {
        String best = null;
        for (ComponentNode other : nodes) {
            if (path.startsWith(other.path + "/") && (best == null || other.path.length() > best.length())) {
                best = other.path;
            }
        }
        return best;
    }

    static String lastSegment(String resourceType) {
        return resourceType.contains("/") ? resourceType.substring(resourceType.lastIndexOf('/') + 1) : resourceType;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> candidates(List<ComponentNode> nodes) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (ComponentNode node : nodes) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("key", node.path);
            candidate.put("coreId", node.coreId);
            candidate.put("selector", node.selector);
            candidate.put("selectorSource", node.properties.containsKey("_wrapperClasses") ? "edit-markup" : "wcm-debug");
            Object classes = node.properties.get("_wrapperClasses");
            candidate.put("classes", classes instanceof List<?> ? classes : List.of());
            candidate.put("cls", lastSegment(node.resourceType));
            candidate.put("clsIndex", node.properties.getOrDefault("_classIndex", 0));
            candidate.put("clsTotal", node.properties.getOrDefault("_classTotal", 0));
            candidates.add(candidate);
        }
        return candidates;
    }

    static void applyProbe(List<ComponentNode> nodes, Object probe) {
        Map<String, Object> result = Json.asMap(probe);
        Map<String, Object> components = Json.asMap(result.get("components"));
        Map<String, Object> dataLayer = Json.asMap(result.get("dataLayer"));
        for (ComponentNode node : nodes) {
            Map<String, Object> found = Json.asMap(components.get(node.path));
            node.found = Json.asBoolean(found.get("found"), false);
            if (node.found) {
                node.locatedBy = Json.asString(found.get("how"));
                node.x = number(found.get("x"));
                node.y = number(found.get("y"));
                node.width = number(found.get("w"));
                node.height = number(found.get("h"));
                node.visible = Json.asBoolean(found.get("visible"), false);
                node.textLength = (int) Json.asLong(found.get("textLength"), 0);
                node.text = Json.asString(found.get("text"));
                node.images = (int) Json.asLong(found.get("images"), 0);
                node.htmlSnippet = Json.asString(found.get("html"));
                String id = Json.asString(found.get("id"));
                if (id != null && dataLayer.containsKey(id)) {
                    node.dataLayerType = Json.asString(dataLayer.get(id));
                }
            }
            node.properties.remove("_classIndex");
            node.properties.remove("_classTotal");
            node.properties.remove("_wrapperClasses");
        }
    }

    private static double number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0;
    }

    // ------------------------------------------------------------------ enrichment

    void enrich(ToolContext context, AemInstance instance, List<ComponentNode> nodes, Result result) {
        SlingModelRegistry registry = null;
        try {
            registry = felix.slingModels(context, instance);
            result.sources.add("sling-models-registry");
        } catch (RuntimeException e) {
            result.notes.add("Sling Models registry not available: " + e.getMessage());
        }
        ProjectIndex index = null;
        try {
            index = projects.get(context);
            if (index != null) {
                result.sources.add("project-index");
            }
        } catch (RuntimeException e) {
            result.notes.add("project index not available: " + e.getMessage());
        }
        for (ComponentNode node : nodes) {
            enrichNode(node, registry, index);
            String hint = Hints.renderHint(node);
            if (hint != null) {
                node.hints.add(hint);
            }
        }
    }

    static void enrichNode(ComponentNode node, SlingModelRegistry registry, ProjectIndex index) {
        List<String> types = new ArrayList<>();
        types.add(node.resourceType);
        if (index != null) {
            String type = node.resourceType;
            for (int i = 0; i < 5 && type != null; i++) {
                ProjectModel.Component component = index.component(type);
                type = component == null ? null : component.superType;
                if (type != null && !types.contains(type)) {
                    types.add(type);
                }
            }
        }
        if (registry != null) {
            for (String type : types) {
                for (String model : registry.modelsFor(type)) {
                    if (!node.runtimeModels.contains(model)) {
                        node.runtimeModels.add(model);
                    }
                }
            }
        }
        if (index == null) {
            return;
        }
        for (String type : types) {
            for (ProjectModel.SlingModel model : index.modelsForResourceType(type)) {
                addModel(node, model, index);
            }
            for (ProjectModel.HtlScript script : index.scriptsFor(type)) {
                String relative = index.relative(script.file);
                if (!node.localScripts.contains(relative)) {
                    node.localScripts.add(relative);
                }
                for (String use : script.useObjects.values()) {
                    ProjectModel.SlingModel model = index.modelByClass(use);
                    if (model != null) {
                        addModel(node, model, index);
                    }
                }
            }
        }
        for (String runtime : node.runtimeModels) {
            ProjectModel.SlingModel model = index.modelByClass(runtime);
            if (model != null) {
                addModel(node, model, index);
            }
        }
    }

    private static void addModel(ComponentNode node, ProjectModel.SlingModel model, ProjectIndex index) {
        if (!node.localModels.contains(model.className)) {
            node.localModels.add(model.className);
            node.localSources.add(index.relative(model.file));
        }
    }

    /** Numbers components in visual order (top-to-bottom, left-to-right); unlocated ones last. */
    static void number(List<ComponentNode> nodes) {
        List<ComponentNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing((ComponentNode n) -> !n.found)
                .thenComparingDouble(n -> n.found ? Math.round(n.y / 4.0) : 0)
                .thenComparingDouble(n -> n.found ? n.x : 0)
                .thenComparingInt(n -> n.depth));
        int number = 1;
        for (ComponentNode node : sorted) {
            node.number = number++;
        }
        nodes.sort(Comparator.comparingInt(n -> n.number));
    }

    /** Screenshot with numbered boxes of located components (full page if available). */
    public static byte[] annotated(Result result, int maxBoxes, boolean fullPage, int maxWidth) {
        byte[] image = fullPage && result.render.fullPagePng() != null ? result.render.fullPagePng()
                : result.render.viewportJpeg();
        if (image == null) {
            return null;
        }
        List<ImageUtil.Box> boxes = new ArrayList<>();
        for (ComponentNode node : result.components) {
            if (node.found && node.visible && boxes.size() < maxBoxes) {
                boxes.add(new ImageUtil.Box(node.number, node.x, node.y, node.width, node.height));
            }
        }
        try {
            return ImageUtil.annotate(image, boxes, maxWidth, 0.75f);
        } catch (IOException e) {
            LOG.info("annotating screenshot failed: {}", e.getMessage());
            return null;
        }
    }
}
