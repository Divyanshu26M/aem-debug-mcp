package dev.aemdebug.mapping;

import dev.aemdebug.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flattens the Sling GET JSON of a page's jcr:content into the list of component resources. */
public final class JcrComponents {

    /** One resource with a sling:resourceType. */
    public static final class Resource {
        public String path;
        public String resourceType;
        public int depth;
        public final Map<String, Object> properties = new LinkedHashMap<>();
        public boolean fromTemplate;
    }

    private JcrComponents() {
    }

    /** Walks the tree; the root itself (page content) is included when it has a resource type. */
    public static List<Resource> flatten(String rootPath, Object json, boolean fromTemplate) {
        List<Resource> result = new ArrayList<>();
        walk(rootPath, Json.asMap(json), 0, result, fromTemplate);
        return result;
    }

    private static void walk(String path, Map<String, Object> node, int depth, List<Resource> out, boolean fromTemplate) {
        String type = Json.asString(node.get("sling:resourceType"));
        if (type != null) {
            Resource resource = new Resource();
            resource.path = path;
            resource.resourceType = type;
            resource.depth = depth;
            resource.fromTemplate = fromTemplate;
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?>)) {
                    resource.properties.put(entry.getKey(), entry.getValue());
                }
            }
            out.add(resource);
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> && !entry.getKey().startsWith("cq:responsive")
                    && !entry.getKey().equals("cq:styleIds")) {
                walk(path + "/" + entry.getKey(), Json.asMap(entry.getValue()), depth + 1, out, fromTemplate);
            }
        }
    }
}
