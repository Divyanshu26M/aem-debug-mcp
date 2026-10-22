package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.List;
import java.util.Map;

/** Compact, indented rendering of Sling GET JSON (properties first, then child nodes). */
public final class ResourceTree {

    private ResourceTree() {
    }

    /**
     * @param propertyFilter property names to show; entries ending with '*' are prefixes; empty = all
     */
    public static String render(String path, Object json, int maxLines, List<String> propertyFilter) {
        StringBuilder sb = new StringBuilder();
        int[] lines = {0};
        Object masked = SecretMasker.maskTree(json);
        renderNode(path, Json.asMap(masked), 0, sb, lines, maxLines, propertyFilter);
        if (lines[0] >= maxLines) {
            sb.append("... (truncated at ").append(maxLines).append(" lines)\n");
        }
        return sb.toString();
    }

    private static void renderNode(String name, Map<String, Object> node, int depth, StringBuilder sb, int[] lines,
                                   int maxLines, List<String> filter) {
        if (lines[0]++ >= maxLines) {
            return;
        }
        String indent = "  ".repeat(depth);
        sb.append(indent).append(name);
        String primary = Json.asString(node.get("jcr:primaryType"));
        String type = Json.asString(node.get("sling:resourceType"));
        if (primary != null || type != null) {
            sb.append("  [").append(primary == null ? "" : primary);
            if (type != null) {
                sb.append(primary == null ? "" : ", ").append(type);
            }
            sb.append(']');
        }
        sb.append('\n');
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) {
                continue;
            }
            String key = entry.getKey();
            if (key.equals("jcr:primaryType") || key.equals("sling:resourceType") || !matches(filter, key)) {
                continue;
            }
            if (lines[0]++ >= maxLines) {
                return;
            }
            sb.append(indent).append("  ").append(key).append(" = ").append(formatValue(entry.getValue())).append('\n');
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) {
                renderNode(entry.getKey() + "/", Json.asMap(entry.getValue()), depth + 1, sb, lines, maxLines, filter);
                if (lines[0] >= maxLines) {
                    return;
                }
            }
        }
    }

    private static boolean matches(List<String> filter, String key) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        for (String f : filter) {
            if (f.endsWith("*") ? key.startsWith(f.substring(0, f.length() - 1)) : key.equals(f)) {
                return true;
            }
        }
        return false;
    }

    public static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + Text.oneLine((String) value, 300) + "\"";
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size() && i < 12; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(formatValue(list.get(i)));
            }
            if (list.size() > 12) {
                sb.append(", ... ").append(list.size() - 12).append(" more");
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }
}
