package dev.aemdebug.rpt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the Sling "Recent Requests" web console (/system/console/requests) and its RPT dumps. */
public final class RecentRequestsClient {

    public static final String CONSOLE = "/system/console/requests";
    private static final Pattern INDEX = Pattern.compile("[?&]index=([^&#'\"]+)");
    private static final Pattern METHOD_PATH = Pattern.compile("\\b(GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH)\\s+(\\S+)");
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\b");
    private static final Pattern USER = Pattern.compile("\\(([^()]+)\\)\\s*$");

    /** One entry of the recent requests list. */
    public static final class RecentRequest {
        public final String index;
        public final String label;
        public final String method;
        public final String path;
        public final String time;
        public final String user;

        RecentRequest(String index, String label, String method, String path, String time, String user) {
            this.index = index;
            this.label = label;
            this.method = method;
            this.path = path;
            this.time = time;
            this.user = user;
        }

        /** Path without query string. */
        public String pathOnly() {
            if (path == null) {
                return null;
            }
            int q = path.indexOf('?');
            return q >= 0 ? path.substring(0, q) : path;
        }
    }

    private final AemClient aem;

    public RecentRequestsClient(AemClient aem) {
        this.aem = aem;
    }

    public List<RecentRequest> list(ToolContext context, AemInstance instance) {
        AemResponse response = aem.get(context, instance, CONSOLE);
        if (response.status() == 404) {
            throw new ToolException("The Recent Requests console (" + CONSOLE + ") is not available on " + instance
                    + ". It may be disabled; aem_debug_toggle toggle=recent-requests can raise the buffer size.");
        }
        AemClient.requireOk(response, CONSOLE);
        return parseList(response.text());
    }

    /** RPT of one recorded request, or null when the entry has been evicted. */
    public String rptText(ToolContext context, AemInstance instance, String index) {
        AemResponse response = aem.get(context, instance, CONSOLE + "?index=" + Text.urlEncode(index));
        AemClient.requireOk(response, CONSOLE + "?index=" + index);
        return extractRpt(response.text());
    }

    public static List<RecentRequest> parseList(String html) {
        Document document = Jsoup.parse(html);
        List<RecentRequest> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Element link : document.select("a[href*=index=]")) {
            Matcher index = INDEX.matcher(link.attr("href"));
            if (!index.find()) {
                continue;
            }
            String key = index.group(1);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            String label = link.text().trim();
            Element row = link.closest("tr");
            String context = row != null ? row.text().trim() : label;
            Matcher methodPath = METHOD_PATH.matcher(context);
            String method = null;
            String path = null;
            if (methodPath.find()) {
                method = methodPath.group(1);
                path = methodPath.group(2);
            }
            Matcher time = TIME.matcher(context);
            Matcher user = USER.matcher(label);
            result.add(new RecentRequest(key, label, method, path, time.find() ? time.group(1) : null,
                    user.find() ? user.group(1) : null));
        }
        return result;
    }

    /** Returns the RPT text (the pre block with TIMER_START lines). */
    public static String extractRpt(String html) {
        Document document = Jsoup.parse(html);
        for (Element pre : document.select("pre")) {
            String text = pre.wholeText();
            if (text.contains("TIMER_START") || text.contains("TIMER_END")) {
                return text;
            }
        }
        String body = document.body() == null ? "" : document.body().wholeText();
        return body.contains("TIMER_START") ? body : null;
    }
}
