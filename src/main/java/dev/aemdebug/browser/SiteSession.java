package dev.aemdebug.browser;

import com.microsoft.playwright.options.Cookie;
import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A logged-in browser session of a local site (for example a customer login through an external identity provider),
 * captured from the visible site-login window: cookies of local hosts plus local and session storage of local
 * origins. Cookies and storage of other hosts (the identity provider) are dropped. Kept in memory only and never
 * logged or returned; {@link #summary()} shows names and counts, never values.
 */
public final class SiteSession {

    private final List<Cookie> cookies;
    private final Map<String, Map<String, String>> localStorage;
    private final Map<String, Map<String, String>> sessionStorage;
    private final String startUrl;
    private final long capturedAt;
    private final boolean windowOpen;

    SiteSession(List<Cookie> cookies, Map<String, Map<String, String>> localStorage,
                Map<String, Map<String, String>> sessionStorage, String startUrl, long capturedAt, boolean windowOpen) {
        List<Cookie> local = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (isLocalDomain(cookie.domain)) {
                local.add(copy(cookie));
            }
        }
        this.cookies = Collections.unmodifiableList(local);
        this.localStorage = localOnly(localStorage);
        this.sessionStorage = localOnly(sessionStorage);
        this.startUrl = startUrl;
        this.capturedAt = capturedAt;
        this.windowOpen = windowOpen;
    }

    public boolean isEmpty() {
        return cookies.isEmpty() && localStorage.isEmpty() && sessionStorage.isEmpty();
    }

    public long capturedAt() {
        return capturedAt;
    }

    public String startUrl() {
        return startUrl;
    }

    public boolean windowOpen() {
        return windowOpen;
    }

    /** Copies for BrowserContext.addCookies. */
    List<Cookie> cookies() {
        List<Cookie> copies = new ArrayList<>();
        for (Cookie cookie : cookies) {
            copies.add(copy(cookie));
        }
        return copies;
    }

    /** Init script that puts the captured storage back before any page script runs, or null when there is none. */
    String initScript() {
        if (localStorage.isEmpty() && sessionStorage.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("local", localStorage);
        data.put("session", sessionStorage);
        return "(() => { const data = " + Json.write(data) + ";\n"
                + "  const put = (store, entries) => { if (!entries) return; for (const [k, v] of Object.entries(entries)) {"
                + " try { if (store.getItem(k) === null) store.setItem(k, v); } catch (e) {} } };\n"
                + "  try { put(window.localStorage, data.local[location.origin]); } catch (e) {}\n"
                + "  try { put(window.sessionStorage, data.session[location.origin]); } catch (e) {}\n"
                + "})();";
    }

    /** Short label for report headers, e.g. "site session captured 10:32:05 (4 cookies, 2 storage keys)". */
    public String label() {
        int keys = 0;
        for (Map<String, String> entries : localStorage.values()) {
            keys += entries.size();
        }
        for (Map<String, String> entries : sessionStorage.values()) {
            keys += entries.size();
        }
        return "site session captured " + Text.formatTime(capturedAt) + " (" + Text.plural(cookies.size(), "cookie")
                + (keys > 0 ? ", " + Text.plural(keys, "storage key") : "")
                + (windowOpen ? ", login window still open" : "") + ")";
    }

    /** Cookie names, storage keys per origin and when it was captured; never values. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        TreeSet<String> names = new TreeSet<>();
        for (Cookie cookie : cookies) {
            names.add(cookie.name);
        }
        sb.append(Text.plural(cookies.size(), "cookie"));
        if (!names.isEmpty()) {
            sb.append(" (").append(String.join(", ", names)).append(')');
        }
        appendStorage(sb, "local storage", localStorage);
        appendStorage(sb, "session storage", sessionStorage);
        sb.append("; captured ").append(Text.formatTime(capturedAt)).append(" from ").append(startUrl)
                .append(windowOpen ? " (login window still open)" : "");
        return sb.toString();
    }

    private static void appendStorage(StringBuilder sb, String label, Map<String, Map<String, String>> storage) {
        if (storage.isEmpty()) {
            return;
        }
        sb.append("; ").append(label).append(": ");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : storage.entrySet()) {
            parts.add(entry.getKey() + " (" + Text.plural(entry.getValue().size(), "key") + ")");
        }
        sb.append(String.join(", ", parts));
    }

    /** Cookie domains of localhost, 127.0.0.1 or ::1 (with or without a leading dot). */
    static boolean isLocalDomain(String domain) {
        if (domain == null) {
            return false;
        }
        String value = domain.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        return HostPolicy.isLocalHost(value);
    }

    static boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && HostPolicy.isLocalHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Map<String, String>> localOnly(Map<String, Map<String, String>> storage) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (storage == null) {
            return result;
        }
        for (Map.Entry<String, Map<String, String>> entry : storage.entrySet()) {
            if (isLocalOrigin(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                result.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Cookie copy(Cookie source) {
        Cookie cookie = new Cookie(source.name, source.value);
        cookie.setDomain(source.domain);
        cookie.setPath(source.path == null ? "/" : source.path);
        if (source.expires != null && source.expires > 0) {
            cookie.setExpires(source.expires);
        }
        if (source.httpOnly != null) {
            cookie.setHttpOnly(source.httpOnly);
        }
        if (source.secure != null) {
            cookie.setSecure(source.secure);
        }
        if (source.sameSite != null) {
            cookie.setSameSite(source.sameSite);
        }
        if (source.partitionKey != null) {
            cookie.setPartitionKey(source.partitionKey);
        }
        return cookie;
    }
}
