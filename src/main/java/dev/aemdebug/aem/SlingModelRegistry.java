package dev.aemdebug.aem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sling Models registrations as printed by the Sling Models status printer: model class to resource type
 * bindings for resources and requests.
 */
public final class SlingModelRegistry {

    /** resourceType -> model classes bound for Resource adaptables. */
    public final Map<String, List<String>> forResources = new LinkedHashMap<>();
    /** resourceType -> model classes bound for SlingHttpServletRequest adaptables. */
    public final Map<String, List<String>> forRequests = new LinkedHashMap<>();
    public final List<String> exporters = new ArrayList<>();
    public int injectorCount;

    public List<String> modelsFor(String resourceType) {
        List<String> result = new ArrayList<>();
        addAll(result, forRequests.get(resourceType));
        addAll(result, forResources.get(resourceType));
        return result;
    }

    public int bindingCount() {
        int count = 0;
        for (List<String> list : forResources.values()) {
            count += list.size();
        }
        for (List<String> list : forRequests.values()) {
            count += list.size();
        }
        return count;
    }

    /** Resource types whose bound model class matches the given (simple or fully qualified) class name. */
    public List<String> resourceTypesFor(String className) {
        List<String> result = new ArrayList<>();
        collect(result, forRequests, className);
        collect(result, forResources, className);
        return result;
    }

    private static void collect(List<String> result, Map<String, List<String>> map, String className) {
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            for (String model : entry.getValue()) {
                if (model.equals(className) || model.endsWith("." + className)) {
                    if (!result.contains(entry.getKey())) {
                        result.add(entry.getKey());
                    }
                }
            }
        }
    }

    private static void addAll(List<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String value : source) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    /** Parses the text output of /system/console/status-slingmodels.txt. */
    public static SlingModelRegistry parse(String text) {
        SlingModelRegistry registry = new SlingModelRegistry();
        String section = "";
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.endsWith(":") && line.toLowerCase().startsWith("sling models")) {
                section = line.toLowerCase();
                continue;
            }
            int sep = line.indexOf(" - ");
            if (sep <= 0) {
                continue;
            }
            String left = line.substring(0, sep).trim();
            String right = line.substring(sep + 3).trim();
            if (section.contains("bound to resource types") && section.contains("for requests")) {
                registry.forRequests.computeIfAbsent(right, k -> new ArrayList<>()).add(left);
            } else if (section.contains("bound to resource types")) {
                registry.forResources.computeIfAbsent(right, k -> new ArrayList<>()).add(left);
            } else if (section.contains("exporter")) {
                registry.exporters.add(line);
            } else if (section.contains("injectors")) {
                registry.injectorCount++;
            }
        }
        return registry;
    }
}
