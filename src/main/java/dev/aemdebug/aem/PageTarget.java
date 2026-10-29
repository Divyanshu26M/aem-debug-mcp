package dev.aemdebug.aem;

/**
 * Where a page is loaded from: an AEM instance directly, or a local front-end dev server that proxies (part of) its
 * requests to an AEM instance. {@link #instance()} is always the AEM instance whose content, logs and JVM belong to
 * the page; the browser loads {@link #renderUrl()}.
 */
public final class PageTarget {

    private final AemInstance instance;
    private final String devServerOrigin;
    private final String instanceSource;
    private final String requestPath;
    private final String renderUrl;

    PageTarget(AemInstance instance, String devServerOrigin, String instanceSource, String requestPath,
               String renderUrl) {
        this.instance = instance;
        this.devServerOrigin = devServerOrigin;
        this.instanceSource = instanceSource;
        this.requestPath = requestPath;
        this.renderUrl = renderUrl;
    }

    /** The AEM instance that serves (or, behind a dev server, backs) the page. */
    public AemInstance instance() {
        return instance;
    }

    public boolean devServer() {
        return devServerOrigin != null;
    }

    /** Origin of the dev server, e.g. http://localhost:3000, or null for AEM targets. */
    public String devServerOrigin() {
        return devServerOrigin;
    }

    /** How the instance was chosen: url, argument, default, remembered or AEM_DEV_SERVERS. */
    public String instanceSource() {
        return instanceSource;
    }

    /** Path and query requested from the server that serves the page. */
    public String requestPath() {
        return requestPath;
    }

    /** Absolute URL for the browser (for author pages the tools add the wcmmode parameter). */
    public String renderUrl() {
        return renderUrl;
    }

    /** Origin whose cookies the browser sends for the page. */
    public String origin() {
        return devServer() ? devServerOrigin : instance.origin();
    }

    /** The AEM page path (no selectors or extension) when the request path is under /content, else null. */
    public String pagePath() {
        String path = PageUrls.pathOnly(requestPath);
        if (!path.startsWith("/content/")) {
            return null;
        }
        int hash = path.indexOf('#');
        return ContentService.pagePathOf(hash >= 0 ? path.substring(0, hash) : path);
    }

    /** E.g. "publish" or "dev server http://localhost:3000 -> publish (remembered mapping)". */
    public String describe() {
        if (!devServer()) {
            return instance.name();
        }
        String source;
        switch (instanceSource) {
            case "argument":
                source = "instance argument";
                break;
            case "remembered":
                source = "remembered mapping";
                break;
            default:
                source = "from " + instanceSource;
                break;
        }
        return "dev server " + devServerOrigin + " -> " + instance.name() + " (" + source + ")";
    }
}
