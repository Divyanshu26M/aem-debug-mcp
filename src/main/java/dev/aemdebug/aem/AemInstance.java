package dev.aemdebug.aem;

import java.net.URI;
import java.util.Locale;

/** A configured local AEM instance (author or publish). */
public final class AemInstance {

    private final String name;
    private final URI baseUrl;

    public AemInstance(String name, URI baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public boolean isAuthor() {
        return "author".equals(name);
    }

    /** scheme://host:port, used to key cookies per instance (cookies are not port specific). */
    public String origin() {
        int port = baseUrl.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(baseUrl.getScheme()) ? 443 : 80;
        }
        return baseUrl.getScheme().toLowerCase(Locale.ROOT) + "://" + baseUrl.getHost() + ":" + port;
    }

    /** Absolute URL for a path (with optional query) on this instance. */
    public String url(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String base = baseUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    public URI uri(String pathAndQuery) {
        return URI.create(url(pathAndQuery));
    }

    @Override
    public String toString() {
        return name + " (" + baseUrl + ")";
    }
}
