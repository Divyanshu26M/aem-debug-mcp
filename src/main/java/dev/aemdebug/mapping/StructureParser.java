package dev.aemdebug.mapping;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import dev.aemdebug.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural signals in AEM HTML: edit-mode editables ({@code <cq data-path data-config>} placed inside each
 * component's decoration element) and WCM Debug Filter comments ({@code <!--cq{"type":..,"path":..}-->}).
 */
public final class StructureParser {

    /** An editable found in edit-mode markup. */
    public static final class Editable {
        public String path;
        public String resourceType;
        public String selector;
        public final List<String> wrapperClasses = new ArrayList<>();
        public String wrapperId;
        public boolean container;
        public int order;
    }

    /** A WCM Debug Filter comment. */
    public static final class DebugComment {
        public String path;
        public String resourceType;
        public String servlet;
        public Long totalTime;
        public Long selfTime;
        public String selector;
        public int order;
    }

    /** Parse result. */
    public static final class Structure {
        public final Map<String, Editable> editables = new LinkedHashMap<>();
        public final Map<String, DebugComment> debugComments = new LinkedHashMap<>();
    }

    private StructureParser() {
    }

    public static Structure parse(String html) {
        Structure structure = new Structure();
        if (html == null || html.isEmpty()) {
            return structure;
        }
        Document document = Jsoup.parse(html);
        int order = 0;
        for (Element cq : document.select("cq[data-path]")) {
            String path = cq.attr("data-path");
            if (path.isEmpty() || path.endsWith("/*") || structure.editables.containsKey(path)) {
                continue;
            }
            Editable editable = new Editable();
            editable.path = path;
            editable.order = order++;
            Map<String, Object> config = parseJson(cq.attr("data-config"));
            editable.resourceType = Json.asString(config.get("type"));
            editable.container = Json.asBoolean(config.get("isContainer"), false);
            Element wrapper = cq.parent();
            if (wrapper != null && !wrapper.tagName().equals("body")) {
                editable.selector = cssPath(wrapper);
                editable.wrapperClasses.addAll(wrapper.classNames());
                editable.wrapperId = wrapper.id().isEmpty() ? null : wrapper.id();
            }
            structure.editables.put(path, editable);
        }
        int[] commentOrder = {0};
        document.traverse((node, depth) -> {
            if (node instanceof Comment) {
                String data = ((Comment) node).getData().trim();
                if (data.startsWith("cq{")) {
                    Map<String, Object> json = parseJson(data.substring(2));
                    String path = Json.asString(json.get("path"));
                    if (path != null && !structure.debugComments.containsKey(path)) {
                        DebugComment comment = new DebugComment();
                        comment.path = path;
                        comment.resourceType = Json.asString(json.get("type"));
                        comment.servlet = Json.asString(json.get("servlet"));
                        comment.totalTime = json.get("totalTime") == null ? null : Json.asLong(json.get("totalTime"), 0);
                        comment.selfTime = json.get("selfTime") == null ? null : Json.asLong(json.get("selfTime"), 0);
                        Element element = previousElement(node);
                        if (element != null && !element.tagName().equals("body")) {
                            comment.selector = cssPath(element);
                        }
                        comment.order = commentOrder[0]++;
                        structure.debugComments.put(path, comment);
                    }
                }
            }
        });
        return structure;
    }

    private static Element previousElement(Node node) {
        Node sibling = node.previousSibling();
        while (sibling != null) {
            if (sibling instanceof Element && !((Element) sibling).tagName().equals("cq")) {
                return (Element) sibling;
            }
            sibling = sibling.previousSibling();
        }
        Node parent = node.parent();
        return parent instanceof Element ? (Element) parent : null;
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Json.parseObject(json);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    /**
     * CSS path from body using tag:nth-of-type, ignoring {@code <cq>} elements (which only exist in edit mode);
     * stops early at an element with an id.
     */
    public static String cssPath(Element element) {
        List<String> parts = new ArrayList<>();
        Element current = element;
        while (current != null && !current.tagName().equals("body") && !current.tagName().equals("html")) {
            if (!current.id().isEmpty() && current.id().matches("[A-Za-z][\\w-]*")) {
                parts.add(0, "#" + current.id());
                return String.join(" > ", parts);
            }
            int index = 1;
            for (Element sibling = current.previousElementSibling(); sibling != null;
                 sibling = sibling.previousElementSibling()) {
                if (sibling.tagName().equals(current.tagName())) {
                    index++;
                }
            }
            parts.add(0, current.tagName() + ":nth-of-type(" + index + ")");
            current = current.parent();
        }
        parts.add(0, "body");
        return String.join(" > ", parts);
    }
}
