package dev.aemdebug.aem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cookie store shared by the HTTP client and the browser. Keyed by instance origin
 * (scheme://host:port) because author and publish both live on localhost. Never persisted.
 */
public final class CookieJar {

    private final Map<String, Map<String, String>> cookies = new ConcurrentHashMap<>();

    public void put(String origin, String name, String value) {
        cookies.computeIfAbsent(origin, k -> new ConcurrentHashMap<>()).put(name, value);
    }

    public void remove(String origin, String name) {
        Map<String, String> map = cookies.get(origin);
        if (map != null) {
            map.remove(name);
        }
    }

    public String get(String origin, String name) {
        Map<String, String> map = cookies.get(origin);
        return map == null ? null : map.get(name);
    }

    public boolean has(String origin, String name) {
        return get(origin, name) != null;
    }

    public void clear(String origin) {
        cookies.remove(origin);
    }

    public void clearAll() {
        cookies.clear();
    }

    public Map<String, String> snapshot(String origin) {
        Map<String, String> map = cookies.get(origin);
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** Builds a Cookie header value from the stored session cookies plus request specific extras. */
    public String cookieHeader(String origin, Map<String, String> extra, boolean includeSession) {
        Map<String, String> all = new LinkedHashMap<>();
        if (includeSession) {
            all.putAll(snapshot(origin));
        }
        if (extra != null) {
            all.putAll(extra);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
