package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** aem_query: bounded QueryBuilder search. */
public final class AemQueryTool extends AbstractTool {

    private final Services s;

    public AemQueryTool(Services services) {
        super("aem_query", "Query content",
                "Searches the local AEM repository with QueryBuilder (read-only, bounded results). Either pass raw "
                        + "QueryBuilder predicates in 'predicates' (e.g. {\"path\":\"/content/wknd\",\"type\":\"cq:Page\","
                        + "\"property\":\"jcr:content/sling:resourceType\",\"property.value\":\"wknd/components/page\"}) "
                        + "or use the shortcuts path/type/property/value/fulltext.",
                Schema.object()
                        .freeObject("predicates", "Raw QueryBuilder predicates (string values)")
                        .str("path", "Search root, e.g. /content/wknd")
                        .str("type", "Node type, e.g. cq:Page or nt:unstructured")
                        .str("property", "Property name (relative paths allowed, e.g. jcr:content/sling:resourceType)")
                        .str("value", "Property value for 'property'")
                        .str("fulltext", "Full-text search term")
                        .strArray("show", "Properties to return per hit (default jcr:path, jcr:title, sling:resourceType)")
                        .integer("limit", "Maximum hits (default 20, max 200)", 1, 200)
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        Map<String, String> predicates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.map("predicates").entrySet()) {
            predicates.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        putIfPresent(predicates, "path", args.str("path"));
        putIfPresent(predicates, "type", args.str("type"));
        putIfPresent(predicates, "property", args.str("property"));
        putIfPresent(predicates, "property.value", args.str("value"));
        putIfPresent(predicates, "fulltext", args.str("fulltext"));
        if (predicates.isEmpty()) {
            throw new ToolException("Give at least one predicate (path, type, property/value, fulltext or predicates)");
        }
        if (!predicates.containsKey("path")) {
            predicates.put("path", "/content");
        }
        List<String> show = args.strList("show");
        if (show.isEmpty()) {
            show = List.of("jcr:path", "jcr:title", "jcr:content/jcr:title", "sling:resourceType",
                    "jcr:content/sling:resourceType", "jcr:primaryType");
        }
        predicates.putIfAbsent("p.hits", "selective");
        predicates.putIfAbsent("p.properties", String.join(" ", show));
        int limit = args.integer("limit", 20, 1, 200);
        Map<String, Object> result = s.content.query(context, instance, predicates, limit);

        StringBuilder out = new StringBuilder("### QueryBuilder ").append(predicates).append('\n');
        out.append("hits: ").append(Json.asList(result.get("hits")).size()).append(", total: ")
                .append(result.get("total")).append(Json.asBoolean(result.get("more"), false) ? " (more available)" : "")
                .append('\n');
        List<List<String>> rows = new ArrayList<>();
        for (Object hit : Json.asList(result.get("hits"))) {
            Map<String, Object> h = Json.asMap(dev.aemdebug.util.SecretMasker.maskTree(hit));
            List<String> row = new ArrayList<>();
            for (String column : show) {
                row.add(Text.nullToEmpty(Json.asString(valueAt(h, column))));
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            out.append(Text.table(show, rows, 120));
        }
        return ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), ToolOutput.INLINE_LIMIT, "query.md"));
    }

    private static Object valueAt(Map<String, Object> hit, String column) {
        if (hit.containsKey(column)) {
            return hit.get(column);
        }
        Object current = hit;
        for (String segment : column.split("/")) {
            current = Json.asMap(current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
