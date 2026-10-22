package dev.aemdebug.mapping;

import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Heuristic diagnoses that turn joined data into actionable hints for the agent. */
public final class Hints {

    private Hints() {
    }

    /** Hints from JCR properties vs. the injected fields of the local model source. */
    public static List<String> modelVsContent(ProjectModel.SlingModel model, Map<String, Object> jcrProperties) {
        List<String> hints = new ArrayList<>();
        if (model == null) {
            return hints;
        }
        for (ProjectModel.InjectedField field : model.fields) {
            if (!"ValueMapValue".equals(field.annotation) && !("Inject".equals(field.annotation)
                    && isSimpleType(field.type))) {
                continue;
            }
            String lookup = field.lookupName();
            if (jcrProperties.containsKey(lookup)) {
                continue;
            }
            String similar = similarProperty(lookup, jcrProperties);
            String location = simpleName(model.className) + ".java:" + field.line;
            if (similar != null) {
                hints.add("JCR has '" + similar + "' but field '" + field.name + "' injects '" + lookup + "' ("
                        + location + ") -> use @ValueMapValue(name = \"" + similar + "\")");
            } else if (!field.optional && !field.hasDefault) {
                hints.add("required field '" + field.name + "' (" + location + ") has no JCR property '" + lookup
                        + "' -> the model fails to adapt (MissingElementsException) unless it is optional");
            }
        }
        return hints;
    }

    /** Hints from model JSON vs. JCR properties (property present in JCR but null/empty in the model output). */
    public static List<String> modelJsonVsContent(Map<String, Object> modelJson, Map<String, Object> jcrProperties) {
        List<String> hints = new ArrayList<>();
        if (modelJson == null) {
            return hints;
        }
        for (Map.Entry<String, Object> entry : jcrProperties.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("jcr:primaryType") || key.startsWith("sling:") || key.startsWith("cq:")
                    || key.equals("jcr:created") || key.equals("jcr:createdBy") || key.equals("jcr:lastModified")
                    || key.equals("jcr:lastModifiedBy") || !(entry.getValue() instanceof String)
                    || ((String) entry.getValue()).isBlank()) {
                continue;
            }
            String bare = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (modelJson.containsKey(bare)) {
                Object value = modelJson.get(bare);
                if (value == null || (value instanceof String && ((String) value).isBlank())) {
                    hints.add("JCR " + key + "=\"" + shorten(String.valueOf(entry.getValue())) + "\" but model JSON "
                            + bare + "=" + Json.write(value) + " -> check the model's injection/getter for '" + bare + "'");
                }
            }
        }
        return hints;
    }

    public static String renderHint(ComponentNode node) {
        if (!node.found) {
            return "not located in the rendered DOM (not rendered, hidden by its script, or markup without a stable id)";
        }
        if (!node.visible) {
            return "rendered but not visible (display:none/visibility:hidden or zero size)";
        }
        if (node.renderedEmpty()) {
            return "rendered empty (no text or media)";
        }
        return null;
    }

    private static boolean isSimpleType(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.equals("string") || t.equals("boolean") || t.equals("int") || t.equals("integer") || t.equals("long")
                || t.equals("calendar") || t.equals("date") || t.equals("string[]");
    }

    static String similarProperty(String lookup, Map<String, Object> properties) {
        String bare = lookup.contains(":") ? lookup.substring(lookup.indexOf(':') + 1) : lookup;
        for (String key : properties.keySet()) {
            String keyBare = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (!key.equals(lookup) && keyBare.equalsIgnoreCase(bare)) {
                return key;
            }
        }
        return null;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String shorten(String value) {
        return value.length() > 60 ? value.substring(0, 57) + "..." : value;
    }
}
