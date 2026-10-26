package dev.aemdebug.aem;

import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only content access: Sling GET JSON, QueryBuilder, Sling Model exporter and page context. */
public final class ContentService {

    /** JSON of a resource plus how it was obtained. */
    public static final class ResourceJson {
        public final Object json;
        public final String url;
        public final String note;

        ResourceJson(Object json, String url, String note) {
            this.json = json;
            this.url = url;
            this.note = note;
        }
    }

    private final AemClient aem;

    public ContentService(AemClient aem) {
        this.aem = aem;
    }

    /** Percent-encodes a JCR path for use in a URL while keeping '/' and ':' readable. */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~:/@!$&'()*+,;=".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ToolException("A JCR path is required, e.g. /content/mysite/us/en/jcr:content");
        }
        String value = path.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.contains("..")) {
            throw new ToolException("Relative path segments are not allowed: " + path);
        }
        return value;
    }

    /** Sling GET servlet JSON at a depth (-1 = infinity, falls back automatically when too large). */
    public ResourceJson resource(ToolContext context, AemInstance instance, String path, int depth) {
        String normalized = normalizePath(path);
        String selector = depth < 0 ? "infinity" : String.valueOf(depth);
        String url = encodePath(normalized) + "." + selector + ".json";
        AemResponse response = aem.get(context, instance, url);
        String note = null;
        if (response.status() == 300) {
            List<Object> alternatives = Json.asList(safeJson(response));
            if (alternatives.isEmpty()) {
                throw new ToolException("AEM refused depth " + selector + " for " + normalized
                        + " (too many nodes); use a smaller depth");
            }
            String alternative = String.valueOf(alternatives.get(0));
            note = "depth " + selector + " was too large for the Sling GET servlet; used " + alternative;
            url = alternative;
            response = aem.get(context, instance, alternative);
        }
        if (response.status() == 404) {
            throw new ToolException("No resource at " + normalized + " on " + instance.name()
                    + " (or the current user cannot read it)");
        }
        AemClient.requireOk(response, url);
        return new ResourceJson(safeJson(response), url, note);
    }

    private static Object safeJson(AemResponse response) {
        try {
            return response.json();
        } catch (IllegalArgumentException e) {
            throw new ToolException("AEM returned non-JSON content for " + response.uri().getPath() + ": "
                    + response.snippet(200));
        }
    }

    /** Properties of a resource (depth 0) or an empty map when it does not exist. */
    public Map<String, Object> propertiesOrEmpty(ToolContext context, AemInstance instance, String path) {
        AemResponse response = aem.get(context, instance, encodePath(normalizePath(path)) + ".json");
        if (!response.ok()) {
            return Map.of();
        }
        try {
            return response.jsonObject();
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    /** QueryBuilder search. Adds p.limit and p.guessTotal unless given. */
    public Map<String, Object> query(ToolContext context, AemInstance instance, Map<String, String> params, int limit) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.putIfAbsent("p.limit", String.valueOf(limit));
        all.putIfAbsent("p.guessTotal", "true");
        StringBuilder query = new StringBuilder("/bin/querybuilder.json?");
        boolean first = true;
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (!first) {
                query.append('&');
            }
            first = false;
            query.append(Text.urlEncode(entry.getKey())).append('=').append(Text.urlEncode(entry.getValue()));
        }
        AemResponse response = aem.get(context, instance, query.toString());
        AemClient.requireOk(response, "/bin/querybuilder.json");
        try {
            return response.jsonObject();
        } catch (IllegalArgumentException e) {
            throw new ToolException("QueryBuilder returned non-JSON content: " + response.snippet(200));
        }
    }

    /** Sling Model exporter output (path.model.json, optionally with an extra selector). */
    public Object modelJson(ToolContext context, AemInstance instance, String path, String selector) {
        String normalized = normalizePath(path);
        if (normalized.endsWith(".html")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        String url = encodePath(normalized) + "." + (selector == null || selector.isBlank() ? "model" : selector)
                + ".json";
        AemResponse response = aem.get(context, instance, url);
        if (response.status() == 404) {
            throw new ToolException("No model JSON at " + url + ": the resource does not exist, or its Sling Model "
                    + "is not exported (@Exporter(name=\"jackson\", extensions=\"json\")), or the model failed to adapt "
                    + "(check aem_logs for ModelAdapterFactory messages).");
        }
        if (response.status() >= 500) {
            throw new ToolException("Model export failed with HTTP " + response.status() + " for " + url
                    + "; the server-side exception is in aem_logs (or capture it with jvm_exception_watch). Body: "
                    + response.snippet(300));
        }
        AemClient.requireOk(response, url);
        return safeJson(response);
    }

    // ------------------------------------------------------------------ page context

    /** Aggregated context of a page (template, policies, CA config, language, replication). */
    public static final class PageContext {
        public String pagePath;
        public Map<String, Object> pageProperties = new LinkedHashMap<>();
        public String template;
        public String pageResourceType;
        public String pagePolicy;
        public Map<String, Object> policyMapping = new LinkedHashMap<>();
        public String language;
        public String languageSource;
        public String configRef;
        public String configRefSource;
        public List<String> caConfigs = new ArrayList<>();
        public String componentPath;
        public String componentResourceType;
        public String componentPolicy;
        public Map<String, Object> componentPolicyProperties = new LinkedHashMap<>();
        public final List<String> notes = new ArrayList<>();
    }

    public PageContext pageContext(ToolContext context, AemInstance instance, String page, String componentPath) {
        PageContext pc = new PageContext();
        pc.pagePath = pagePathOf(page);
        Map<String, Object> props = propertiesOrEmpty(context, instance, pc.pagePath + "/jcr:content");
        if (props.isEmpty()) {
            throw new ToolException("No page content at " + pc.pagePath + "/jcr:content on " + instance.name());
        }
        pc.pageProperties = props;
        pc.template = Json.asString(props.get("cq:template"));
        pc.pageResourceType = Json.asString(props.get("sling:resourceType"));
        pc.language = Json.asString(props.get("jcr:language"));
        pc.languageSource = pc.language == null ? null : pc.pagePath;
        String configRef = firstNonNull(props.get("sling:configRef"), props.get("cq:conf"));
        pc.configRef = configRef;
        pc.configRefSource = configRef == null ? null : pc.pagePath;

        String ancestor = parentPath(pc.pagePath);
        while (ancestor != null && ancestor.startsWith("/content/") && (pc.language == null || pc.configRef == null)) {
            Map<String, Object> ancestorProps = propertiesOrEmpty(context, instance, ancestor + "/jcr:content");
            if (pc.language == null && ancestorProps.get("jcr:language") != null) {
                pc.language = Json.asString(ancestorProps.get("jcr:language"));
                pc.languageSource = ancestor;
            }
            if (pc.configRef == null) {
                String ref = firstNonNull(ancestorProps.get("sling:configRef"), ancestorProps.get("cq:conf"));
                if (ref != null) {
                    pc.configRef = ref;
                    pc.configRefSource = ancestor;
                }
            }
            ancestor = parentPath(ancestor);
        }
        if (pc.language == null) {
            pc.language = languageFromPath(pc.pagePath);
            pc.languageSource = pc.language == null ? null : "page path";
        }
        if (pc.configRef != null) {
            try {
                Object configs = resource(context, instance, pc.configRef + "/sling:configs", 1).json;
                for (String key : Json.asMap(configs).keySet()) {
                    if (!key.startsWith("jcr:")) {
                        pc.caConfigs.add(key);
                    }
                }
            } catch (ToolException e) {
                pc.notes.add("no sling:configs under " + pc.configRef);
            }
        }
        if (pc.template != null && pc.template.startsWith("/conf/")) {
            try {
                pc.policyMapping = Json.asMap(resource(context, instance, pc.template + "/policies/jcr:content", -1).json);
                pc.pagePolicy = Json.asString(pc.policyMapping.get("cq:policy"));
            } catch (ToolException e) {
                pc.notes.add("template policy mapping not readable: " + e.getMessage());
            }
        } else if (pc.template != null) {
            pc.notes.add("static template (no editable template policies): " + pc.template);
        }
        if (componentPath != null && !componentPath.isBlank()) {
            resolveComponentPolicy(context, instance, pc, normalizePath(componentPath));
        }
        return pc;
    }

    private void resolveComponentPolicy(ToolContext context, AemInstance instance, PageContext pc, String component) {
        pc.componentPath = component;
        Map<String, Object> componentProps = propertiesOrEmpty(context, instance, component);
        pc.componentResourceType = Json.asString(componentProps.get("sling:resourceType"));
        String prefix = pc.pagePath + "/jcr:content/";
        if (!component.startsWith(prefix)) {
            pc.notes.add("component is not inside " + prefix + " (e.g. from a template structure or experience "
                    + "fragment); policy lookup skipped");
            return;
        }
        String relative = component.substring(prefix.length());
        String policy = policyAt(pc.policyMapping, relative);
        if (policy == null && pc.componentResourceType != null) {
            String parentRelative = relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : "";
            String lookup = parentRelative.isEmpty() ? pc.componentResourceType
                    : parentRelative + "/" + pc.componentResourceType;
            policy = policyAt(pc.policyMapping, lookup);
        }
        pc.componentPolicy = policy;
        if (policy != null) {
            String confRoot = pc.template.substring(0, pc.template.indexOf("/settings/wcm/templates/") > 0
                    ? pc.template.indexOf("/settings/wcm/templates/") : pc.template.length());
            String policyPath = confRoot + "/settings/wcm/policies/" + policy;
            pc.componentPolicyProperties = propertiesOrEmpty(context, instance, policyPath);
            if (pc.componentPolicyProperties.isEmpty()) {
                pc.notes.add("policy node " + policyPath + " not found");
            }
        } else if (pc.template != null && pc.template.startsWith("/conf/")) {
            pc.notes.add("no policy mapped for " + relative + " (component falls back to defaults)");
        }
    }

    static String policyAt(Map<String, Object> mapping, String relativePath) {
        Object node = mapping;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = Json.asMap(node).get(segment);
            if (node == null) {
                return null;
            }
        }
        return Json.asString(Json.asMap(node).get("cq:policy"));
    }

    /** Page path from a page URL/path (including editor URLs) or a path inside the page (jcr:content/...). */
    public static String pagePathOf(String input) {
        String path = normalizePath(PageUrls.isUrlOrAuthoringPath(input) ? PageUrls.toRequestPath(input) : input);
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int content = path.indexOf("/jcr:content");
        if (content >= 0) {
            path = path.substring(0, content);
        }
        int slash = path.lastIndexOf('/');
        int dot = path.indexOf('.', slash);
        if (dot > 0) {
            path = path.substring(0, dot);
        }
        return path;
    }

    static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    static String languageFromPath(String pagePath) {
        String[] segments = pagePath.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (segment.matches("[a-z]{2}([_-][a-zA-Z]{2})?")) {
                return segment;
            }
        }
        return null;
    }

    private static String firstNonNull(Object a, Object b) {
        if (a != null) {
            return Json.asString(a);
        }
        return b == null ? null : Json.asString(b);
    }
}
