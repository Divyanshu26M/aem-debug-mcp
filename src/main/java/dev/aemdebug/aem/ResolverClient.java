package dev.aemdebug.aem;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Servlet/script resolution via the Sling Servlet Resolver console plus resource lookup. */
public final class ResolverClient {

    public static final String CONSOLE = "/system/console/servletresolver";
    private static final Pattern DECOMPOSED = Pattern.compile(
            "(?i)\\b(Path|Selectors|Extension|Suffix|Method)\\s*[:=]?\\s*([^\\n]*)");

    /** Result of a resolution. */
    public static final class Resolution {
        public String url;
        public String method;
        public final Map<String, String> decomposed = new LinkedHashMap<>();
        public final List<String> candidates = new ArrayList<>();
        public String consoleNote;
        public String resourcePath;
        public String resourceType;
        public String resourceSuperType;
        public String primaryType;
    }

    private final AemClient aem;
    private final ContentService content;

    public ResolverClient(AemClient aem, ContentService content) {
        this.aem = aem;
        this.content = content;
    }

    public Resolution resolve(ToolContext context, AemInstance instance, String url, String method) {
        Resolution resolution = new Resolution();
        resolution.url = url;
        resolution.method = method;
        AemResponse response = aem.get(context, instance, CONSOLE + "?url=" + Text.urlEncode(url) + "&method="
                + Text.urlEncode(method));
        if (response.ok()) {
            parseConsole(response.text(), resolution);
        } else {
            resolution.consoleNote = "servlet resolver console answered HTTP " + response.status();
        }
        String candidatePath = PageUrls.contentPath(url);
        Map<String, Object> props = content.propertiesOrEmpty(context, instance, candidatePath);
        if (!props.isEmpty()) {
            resolution.resourcePath = candidatePath;
            resolution.primaryType = Json.asString(props.get("jcr:primaryType"));
            resolution.resourceType = Json.asString(props.get("sling:resourceType"));
            resolution.resourceSuperType = Json.asString(props.get("sling:resourceSuperType"));
            if ("cq:Page".equals(resolution.primaryType)) {
                Map<String, Object> pageContent = content.propertiesOrEmpty(context, instance, candidatePath + "/jcr:content");
                if (!pageContent.isEmpty()) {
                    resolution.resourcePath = candidatePath + "/jcr:content";
                    resolution.resourceType = Json.asString(pageContent.get("sling:resourceType"));
                    resolution.resourceSuperType = Json.asString(pageContent.get("sling:resourceSuperType"));
                }
            }
        }
        return resolution;
    }

    static void parseConsole(String html, Resolution resolution) {
        Document document = Jsoup.parse(html);
        for (Element row : document.select("tr")) {
            List<Element> cells = row.select("td,th");
            if (cells.size() == 2) {
                String key = cells.get(0).text().replace(":", "").trim();
                if (key.matches("(?i)path|selectors|extension|suffix|method")) {
                    resolution.decomposed.put(key, cells.get(1).text().trim());
                }
            }
        }
        if (resolution.decomposed.isEmpty()) {
            Matcher m = DECOMPOSED.matcher(document.body() == null ? "" : document.body().wholeText());
            while (m.find()) {
                resolution.decomposed.putIfAbsent(m.group(1), m.group(2).trim());
            }
        }
        for (Element item : document.select("li")) {
            String text = item.text().trim();
            if (text.startsWith("/") || text.contains(".") && (text.contains("/apps/") || text.contains("/libs/")
                    || text.contains("Servlet") || text.contains("servlet"))) {
                resolution.candidates.add(text);
            }
        }
        if (resolution.candidates.isEmpty()) {
            String body = document.body() == null ? "" : document.body().text();
            if (body.toLowerCase().contains("no servlet")) {
                resolution.consoleNote = "the console reports no matching servlet";
            }
        }
    }
}
