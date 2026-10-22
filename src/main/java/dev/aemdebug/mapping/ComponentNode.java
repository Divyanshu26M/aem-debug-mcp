package dev.aemdebug.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A component instance on a rendered page, joined across JCR, markup, browser DOM, runtime and local source. */
public final class ComponentNode {

    public int number;
    public String path;
    public String resourceType;
    public String parentPath;
    public int depth;
    public boolean fromTemplate;
    public final Map<String, Object> properties = new LinkedHashMap<>();

    /** How the DOM element was located: core-id, edit-markup, wcm-debug, class-order, or null if not found. */
    public String locatedBy;
    public String coreId;
    public String selector;
    public boolean found;
    public boolean visible;
    public double x;
    public double y;
    public double width;
    public double height;
    public int textLength;
    public String text;
    public int images;
    public String htmlSnippet;
    public String dataLayerType;

    public String script;
    public Long renderTime;
    public final List<String> runtimeModels = new ArrayList<>();
    public final List<String> localModels = new ArrayList<>();
    public final List<String> localSources = new ArrayList<>();
    public final List<String> localScripts = new ArrayList<>();
    public final List<String> hints = new ArrayList<>();

    public boolean renderedEmpty() {
        return found && (height < 2 || (textLength == 0 && images == 0));
    }

    /** Path relative to the page's jcr:content for compact output. */
    public String shortPath(String pagePath) {
        String prefix = pagePath + "/jcr:content/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    public String box() {
        if (!found) {
            return "-";
        }
        return Math.round(x) + "," + Math.round(y) + " " + Math.round(width) + "x" + Math.round(height);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("number", number);
        map.put("path", path);
        map.put("resourceType", resourceType);
        map.put("parentPath", parentPath);
        map.put("fromTemplate", fromTemplate);
        map.put("locatedBy", locatedBy);
        map.put("coreId", coreId);
        map.put("selector", selector);
        map.put("found", found);
        map.put("visible", visible);
        map.put("box", found ? List.of(x, y, width, height) : null);
        map.put("textLength", textLength);
        map.put("images", images);
        map.put("renderedEmpty", renderedEmpty());
        map.put("dataLayerType", dataLayerType);
        map.put("script", script);
        map.put("renderTime", renderTime);
        map.put("runtimeModels", runtimeModels);
        map.put("localModels", localModels);
        map.put("localSources", localSources);
        map.put("localScripts", localScripts);
        map.put("hints", hints);
        return map;
    }
}
