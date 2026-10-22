package dev.aemdebug.correlate;

import dev.aemdebug.util.Text;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identity of one Sling request: Sling renames the request thread to
 * {@code <remoteAddr> [<startMillis>] <METHOD> <requestURI> <protocol>} (URI without query string). The same string
 * is the thread name seen in JDI and the [thread] field of error.log, so it joins JVM events, log lines and
 * browser requests.
 */
public final class RequestKey {

    private static final Pattern THREAD_NAME =
            Pattern.compile("^(\\S+) \\[(\\d{10,})\\] ([A-Z]+) (\\S.*?) (HTTP/\\d(?:\\.\\d)?)$");

    private final String remoteAddr;
    private final long startMillis;
    private final String method;
    private final String uri;
    private final String protocol;

    public RequestKey(String remoteAddr, long startMillis, String method, String uri, String protocol) {
        this.remoteAddr = remoteAddr;
        this.startMillis = startMillis;
        this.method = method;
        this.uri = uri;
        this.protocol = protocol;
    }

    /** Parses a Sling request thread name; returns null for other threads. */
    public static RequestKey parse(String threadName) {
        if (threadName == null) {
            return null;
        }
        Matcher m = THREAD_NAME.matcher(threadName.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return new RequestKey(m.group(1), Long.parseLong(m.group(2)), m.group(3), m.group(4), m.group(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String remoteAddr() {
        return remoteAddr;
    }

    public long startMillis() {
        return startMillis;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public String protocol() {
        return protocol;
    }

    public String threadName() {
        return remoteAddr + " [" + startMillis + "] " + method + " " + uri + " " + protocol;
    }

    /** Human friendly label, e.g. "GET /content/site/en.html @17:19:00.945". */
    public String label() {
        return method + " " + uri + " @" + Text.formatTime(startMillis);
    }

    /**
     * True if this request plausibly is the server side of a browser request with the given method and path that
     * the browser started at {@code clientStartMillis} (same machine clock).
     */
    public boolean matches(String otherMethod, String path, long clientStartMillis, long toleranceMs) {
        if (!method.equalsIgnoreCase(otherMethod) || !uri.equals(path)) {
            return false;
        }
        return startMillis >= clientStartMillis - toleranceMs && startMillis <= clientStartMillis + toleranceMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RequestKey)) {
            return false;
        }
        RequestKey other = (RequestKey) o;
        return startMillis == other.startMillis && remoteAddr.equals(other.remoteAddr) && method.equals(other.method)
                && uri.equals(other.uri) && protocol.equals(other.protocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remoteAddr, startMillis, method, uri, protocol);
    }

    @Override
    public String toString() {
        return threadName();
    }
}
