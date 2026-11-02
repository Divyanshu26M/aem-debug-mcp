package dev.aemdebug.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Only local AEM instances may be contacted: localhost, 127.0.0.1 and ::1. Remote hosts are rejected for
 * AEM HTTP access, browser navigation to AEM and JDWP.
 */
public final class HostPolicy {

    private static final Set<String> LOCAL_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]");

    private HostPolicy() {
    }

    public static boolean isLocalHost(String host) {
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** Parses and validates an http(s) base URL that must point to a local host. Trailing slashes are removed. */
    public static URI requireLocalBaseUrl(String raw, String settingName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(settingName + " is empty");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(settingName + " is not a valid URL: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(settingName + " must use http or https: " + raw);
        }
        if (!isLocalHost(uri.getHost())) {
            throw new IllegalArgumentException(settingName + " points to host '" + uri.getHost()
                    + "'. Only local AEM instances are allowed (localhost, 127.0.0.1, ::1).");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path.isEmpty() ? null : path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(settingName + " is not a valid URL: " + raw);
        }
    }

    public static void requireLocal(URI uri) {
        if (uri == null || !isLocalHost(uri.getHost())) {
            throw new SecurityException("Blocked request to non-local host: " + (uri == null ? "null" : uri.getHost()));
        }
    }

    /**
     * Comparable origin of a URL, e.g. "http://localhost:4503". Loopback names (127.0.0.1, ::1) count as localhost and
     * a missing port becomes the scheme's default, so the same local server always gets the same key.
     */
    public static String originKey(URI uri) {
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (isLocalHost(host)) {
            host = "localhost";
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        return scheme + "://" + host + ":" + port;
    }
}
