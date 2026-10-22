package dev.aemdebug.correlate;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Joins what the browser saw (network requests) with what AEM did (JVM events, log lines, request progress
 * tracker entries). The join key is the Sling request thread name {@code <ip> [<startMillis>] <METHOD> <URI> <protocol>}:
 * same method, same path, and request start times within a tolerance (browser and AEM share the local clock).
 */
public final class RequestCorrelator {

    /** Default tolerance between the browser's request start and the server's request start. */
    public static final long DEFAULT_TOLERANCE_MILLIS = 5000;

    /** A request observed by the browser. {@code id} is any caller-chosen reference. */
    public record BrowserRequest(String id, String method, String url, long startMillis) {
        public BrowserRequest {
            method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        }

        public String path() {
            return normalizePath(url);
        }
    }

    /**
     * A server-side request identity. {@code startMillis < 0} means the start time is unknown (for example request
     * progress tracker entries): such requests are matched by method and path only, in order.
     */
    public record ServerRequest(String method, String uri, long startMillis) {
        public ServerRequest {
            method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            uri = uri == null ? "" : uri;
        }

        public String path() {
            return normalizePath(uri);
        }

        public boolean timed() {
            return startMillis >= 0;
        }

        public String label() {
            return method + " " + uri + (timed() ? " [" + startMillis + "]" : "");
        }
    }

    /** Something that happened on the server; {@code request} is null when the thread was not a request thread. */
    public record Event(String kind, ServerRequest request, long timeMillis, String summary, Object payload) {
    }

    /** Events attributed to one request; {@code browser} is null for requests the browser did not make. */
    public static final class Group {
        private final BrowserRequest browser;
        private final ServerRequest server;
        private final List<Event> events = new ArrayList<>();

        Group(BrowserRequest browser, ServerRequest server) {
            this.browser = browser;
            this.server = server;
        }

        public BrowserRequest browser() {
            return browser;
        }

        public ServerRequest server() {
            return server;
        }

        public List<Event> events() {
            return events;
        }

        public List<Event> events(String kind) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
                if (e.kind().equals(kind)) {
                    out.add(e);
                }
            }
            return out;
        }

        public String label() {
            if (server != null && server.timed()) {
                return server.label();
            }
            if (browser != null) {
                return browser.method() + " " + browser.path();
            }
            return server == null ? "(unknown)" : server.label();
        }
    }

    /**
     * Result of a correlation. {@code requests} holds one group per browser request that has server-side events
     * (in browser order), {@code serverOnly} holds requests the browser did not make (other clients, the user's own
     * browser, internal requests) and {@code unattributed} holds events from non-request threads.
     */
    public record Result(List<Group> requests, List<Group> serverOnly, List<Event> unattributed,
                         List<BrowserRequest> browserWithoutEvents) {

        public int eventCount() {
            int n = unattributed.size();
            for (Group g : requests) {
                n += g.events().size();
            }
            for (Group g : serverOnly) {
                n += g.events().size();
            }
            return n;
        }
    }

    private RequestCorrelator() {
    }

    /** Groups events by server request without any browser information (capture mode). */
    public static Result group(List<Event> events) {
        return correlate(List.of(), events, DEFAULT_TOLERANCE_MILLIS);
    }

    public static Result correlate(List<BrowserRequest> browserRequests, List<Event> events, long toleranceMillis) {
        List<BrowserRequest> browser = browserRequests == null ? List.of() : browserRequests;
        List<Event> sorted = new ArrayList<>(events == null ? List.of() : events);
        sorted.sort(Comparator.comparingLong(Event::timeMillis));

        // Distinct timed server requests, in first-seen order.
        Map<String, ServerRequest> timed = new LinkedHashMap<>();
        for (Event e : sorted) {
            ServerRequest r = e.request();
            if (r != null && r.timed()) {
                timed.putIfAbsent(key(r), r);
            }
        }

        // Candidate pairs by time distance; greedy 1:1 assignment, closest first.
        List<Pair> pairs = new ArrayList<>();
        for (ServerRequest s : timed.values()) {
            for (int i = 0; i < browser.size(); i++) {
                BrowserRequest b = browser.get(i);
                if (sameRequest(b, s)) {
                    long d = Math.abs(s.startMillis() - b.startMillis());
                    if (d <= toleranceMillis) {
                        pairs.add(new Pair(i, s, d));
                    }
                }
            }
        }
        pairs.sort(Comparator.comparingLong((Pair p) -> p.distance).thenComparingInt(p -> p.browserIndex));
        Map<Integer, ServerRequest> browserToServer = new HashMap<>();
        Set<String> assignedServer = new HashSet<>();
        for (Pair p : pairs) {
            if (!browserToServer.containsKey(p.browserIndex) && assignedServer.add(key(p.server))) {
                browserToServer.put(p.browserIndex, p.server);
            }
        }

        Map<Integer, Group> browserGroups = new LinkedHashMap<>();
        Map<String, Group> serverGroups = new LinkedHashMap<>();
        for (Map.Entry<Integer, ServerRequest> e : browserToServer.entrySet()) {
            Group g = new Group(browser.get(e.getKey()), e.getValue());
            browserGroups.put(e.getKey(), g);
            serverGroups.put(key(e.getValue()), g);
        }
        Map<String, Group> serverOnly = new LinkedHashMap<>();
        List<Event> unattributed = new ArrayList<>();
        // Untimed events (e.g. RPT) attach to the n-th request with the same method and path.
        Map<String, Integer> untimedSeen = new HashMap<>();

        for (Event e : sorted) {
            ServerRequest r = e.request();
            if (r == null) {
                unattributed.add(e);
                continue;
            }
            if (r.timed()) {
                Group g = serverGroups.get(key(r));
                if (g == null) {
                    g = serverOnly.computeIfAbsent(key(r), k -> new Group(null, r));
                }
                g.events.add(e);
                continue;
            }
            String mp = r.method() + " " + r.path();
            int nth = untimedSeen.merge(mp + "#" + e.kind(), 1, Integer::sum) - 1;
            Group target = nthBrowserGroup(browser, browserGroups, r, nth);
            if (target == null) {
                target = serverOnly.computeIfAbsent("untimed " + mp, k -> new Group(null, r));
            }
            target.events.add(e);
        }

        List<Group> requests = new ArrayList<>();
        List<BrowserRequest> without = new ArrayList<>();
        for (int i = 0; i < browser.size(); i++) {
            Group g = browserGroups.get(i);
            if (g != null && !g.events.isEmpty()) {
                requests.add(g);
            } else if (g == null) {
                without.add(browser.get(i));
            }
        }
        return new Result(Collections.unmodifiableList(requests),
                Collections.unmodifiableList(new ArrayList<>(serverOnly.values())),
                Collections.unmodifiableList(unattributed), Collections.unmodifiableList(without));
    }

    private static Group nthBrowserGroup(List<BrowserRequest> browser, Map<Integer, Group> groups, ServerRequest r,
                                         int nth) {
        int seen = 0;
        for (int i = 0; i < browser.size(); i++) {
            BrowserRequest b = browser.get(i);
            if (b.method().equals(r.method()) && b.path().equals(r.path())) {
                if (seen == nth) {
                    return groups.computeIfAbsent(i, k -> new Group(b, null));
                }
                seen++;
            }
        }
        return null;
    }

    static boolean sameRequest(BrowserRequest b, ServerRequest s) {
        return b.method().equals(s.method()) && b.path().equals(s.path());
    }

    private static String key(ServerRequest r) {
        return r.method() + " " + r.path() + " " + r.startMillis();
    }

    /** Path without scheme, host, query and fragment, percent-decoded ('+' stays literal). */
    public static String normalizePath(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            return "/";
        }
        String s = urlOrPath.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        if (s.contains("://")) {
            try {
                String raw = URI.create(s.replace(" ", "%20")).getRawPath();
                s = raw == null || raw.isEmpty() ? "/" : raw;
            } catch (IllegalArgumentException e) {
                int slash = s.indexOf('/', s.indexOf("://") + 3);
                s = slash < 0 ? "/" : s.substring(slash);
            }
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return percentDecode(s);
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static final class Pair {
        final int browserIndex;
        final ServerRequest server;
        final long distance;

        Pair(int browserIndex, ServerRequest server, long distance) {
            this.browserIndex = browserIndex;
            this.server = Objects.requireNonNull(server);
            this.distance = distance;
        }
    }
}
