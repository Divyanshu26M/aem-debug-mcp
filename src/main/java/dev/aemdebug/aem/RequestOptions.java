package dev.aemdebug.aem;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-request options for {@link AemHttpClient}. Immutable; use the with* methods. */
public final class RequestOptions {

    private final Duration timeout;
    private final Map<String, String> extraCookies;
    private final Map<String, String> headers;
    private final boolean followRedirects;
    private final boolean sendSession;

    private RequestOptions(Duration timeout, Map<String, String> extraCookies, Map<String, String> headers,
                           boolean followRedirects, boolean sendSession) {
        this.timeout = timeout;
        this.extraCookies = extraCookies;
        this.headers = headers;
        this.followRedirects = followRedirects;
        this.sendSession = sendSession;
    }

    public static RequestOptions defaults() {
        return new RequestOptions(Duration.ofSeconds(30), Map.of(), Map.of(), true, true);
    }

    public RequestOptions withTimeout(Duration value) {
        return new RequestOptions(value, extraCookies, headers, followRedirects, sendSession);
    }

    public RequestOptions withCookie(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(extraCookies);
        copy.put(name, value);
        return new RequestOptions(timeout, copy, headers, followRedirects, sendSession);
    }

    public RequestOptions withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new RequestOptions(timeout, extraCookies, copy, followRedirects, sendSession);
    }

    public RequestOptions withoutRedirects() {
        return new RequestOptions(timeout, extraCookies, headers, false, sendSession);
    }

    /** Sends the request anonymously (no session cookies). */
    public RequestOptions anonymous() {
        return new RequestOptions(timeout, extraCookies, headers, followRedirects, false);
    }

    public Duration timeout() {
        return timeout;
    }

    public Map<String, String> extraCookies() {
        return extraCookies;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public boolean followRedirects() {
        return followRedirects;
    }

    public boolean sendSession() {
        return sendSession;
    }
}
