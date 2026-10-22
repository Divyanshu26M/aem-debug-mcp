package dev.aemdebug.mapping;

import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.List;

/** Compact text rendering of a component map. */
public final class ComponentReport {

    private ComponentReport() {
    }

    public static String table(ComponentMapper.Result result, int maxRows, boolean onlyProblems) {
        List<List<String>> rows = new ArrayList<>();
        int skipped = 0;
        for (ComponentNode node : result.components) {
            boolean problem = !node.hints.isEmpty();
            if (onlyProblems && !problem) {
                continue;
            }
            if (rows.size() >= maxRows) {
                skipped++;
                continue;
            }
            rows.add(List.of("#" + node.number, node.shortPath(result.pagePath) + (node.fromTemplate ? " (template)" : ""),
                    node.resourceType, node.found ? Text.nullToEmpty(node.locatedBy) : "not found", node.box(),
                    content(node), models(node), String.join("; ", node.hints)));
        }
        StringBuilder sb = new StringBuilder();
        if (!rows.isEmpty()) {
            sb.append(Text.table(List.of("#", "component", "resourceType", "located by", "box", "content", "model/source",
                    "hints"), rows, 90));
        }
        if (skipped > 0) {
            sb.append("... ").append(skipped).append(" more components in components.json\n");
        }
        return sb.toString();
    }

    static String content(ComponentNode node) {
        if (!node.found) {
            return "";
        }
        if (node.renderedEmpty()) {
            return "EMPTY";
        }
        String text = node.text == null ? "" : Text.oneLine(node.text, 40);
        return (node.images > 0 ? node.images + " media; " : "") + "\"" + text + "\"";
    }

    static String models(ComponentNode node) {
        if (!node.localSources.isEmpty()) {
            return simple(node.localModels.get(0)) + " (" + node.localSources.get(0) + ")";
        }
        if (!node.runtimeModels.isEmpty()) {
            return simple(node.runtimeModels.get(0)) + (node.runtimeModels.size() > 1 ? " +" + (node.runtimeModels.size() - 1) : "");
        }
        return "";
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Short legend of where the mapping came from. */
    public static String sources(ComponentMapper.Result result) {
        StringBuilder sb = new StringBuilder("Mapping sources: ").append(String.join(", ", result.sources)).append('\n');
        for (String note : result.notes) {
            sb.append("Note: ").append(note).append('\n');
        }
        int found = 0;
        int empty = 0;
        for (ComponentNode node : result.components) {
            if (node.found) {
                found++;
            }
            if (node.renderedEmpty()) {
                empty++;
            }
        }
        sb.append("Components: ").append(result.components.size()).append(" in JCR, ").append(found)
                .append(" located in the rendered page, ").append(empty).append(" rendered empty\n");
        return sb.toString();
    }
}
