package dev.aemdebug.aem;

import dev.aemdebug.tools.ToolException;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Normalizes page paths/URLs given by the agent into request paths on a local instance. */
public final class PageUrls {

    /** AEM authoring UIs that wrap a content path, e.g. /editor.html/content/site/en.html. */
    private static final List<String> AUTHORING_PREFIXES = List.of("/editor.html", "/sites.html", "/cf#");

    private PageUrls() {
    }

    /**
     * Accepts "/content/site/en", "/content/site/en.html", "content/site/en.html?x=1", a full local URL, or the URL of
     * the page editor, the Sites console or the classic UI (cf#) showing the page, and returns a path with query
     * starting with "/". Pages without extension under /content get ".html".
     */
    public static String toRequestPath(String input) {
        if (input == null || input.isBlank()) {
            throw new ToolException("A page path is required, e.g. /content/mysite/us/en.html");
        }
        String value = input.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            URI uri = URI.create(value);
            dev.aemdebug.config.HostPolicy.requireLocal(uri);
            String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
            if ("/cf".equals(rawPath) && uri.getRawFragment() != null) {
                value = "/cf#" + uri.getRawFragment();
            } else {
                value = rawPath + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            }
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        value = withoutAuthoringPrefix(value);
        String path = value;
        String query = "";
        int q = value.indexOf('?');
        if (q >= 0) {
            path = value.substring(0, q);
            query = value.substring(q);
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("/content/") && !lastSegment.contains(".") && !path.endsWith("/")) {
            path = path + ".html";
        }
        return path + query;
    }

    /** "/editor.html/content/a.html" becomes "/content/a.html"; likewise for the Sites console and cf#. */
    static String withoutAuthoringPrefix(String path) {
        for (String prefix : AUTHORING_PREFIXES) {
            if (path.startsWith(prefix + "/")) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    /** True for full URLs and authoring UI paths that {@link #toRequestPath} has to unwrap. */
    public static boolean isUrlOrAuthoringPath(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return true;
        }
        String path = value.startsWith("/") ? value : "/" + value;
        return !withoutAuthoringPrefix(path).equals(path);
    }

    /** Adds or replaces the wcmmode query parameter; "none" leaves the URL unchanged. */
    public static String withWcmMode(String requestPath, String wcmMode) {
        if (wcmMode == null || wcmMode.isBlank() || "none".equalsIgnoreCase(wcmMode)) {
            return requestPath;
        }
        String mode = wcmMode.toLowerCase(Locale.ROOT);
        String path = requestPath;
        String query = "";
        int q = requestPath.indexOf('?');
        if (q >= 0) {
            path = requestPath.substring(0, q);
            query = requestPath.substring(q + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isEmpty() || part.toLowerCase(Locale.ROOT).startsWith("wcmmode=")) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : "&").append(part);
        }
        sb.append(sb.length() == 0 ? "" : "&").append("wcmmode=").append(mode);
        return path + "?" + sb;
    }

    /** The path portion without query, e.g. /content/site/en.html. */
    public static String pathOnly(String requestPath) {
        int q = requestPath.indexOf('?');
        return q >= 0 ? requestPath.substring(0, q) : requestPath;
    }

    /** Content path of the page (no selectors/extension), e.g. /content/site/en. */
    public static String contentPath(String requestPath) {
        String path = pathOnly(requestPath);
        int slash = path.lastIndexOf('/');
        int dot = path.indexOf('.', Math.max(0, slash));
        return dot > 0 ? path.substring(0, dot) : path;
    }
}
