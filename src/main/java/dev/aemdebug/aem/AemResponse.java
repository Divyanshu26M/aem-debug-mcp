package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP response from AEM with helpers for JSON and login detection. */
public final class AemResponse {

    public static final String LOGIN_PAGE = "/libs/granite/core/content/login.html";

    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final URI uri;
    private final long durationMs;

    public AemResponse(int status, Map<String, List<String>> headers, byte[] body, URI uri, long durationMs) {
        this.status = status;
        this.headers = headers == null ? Map.of() : headers;
        this.body = body == null ? new byte[0] : body;
        this.uri = uri;
        this.durationMs = durationMs;
    }

    public int status() {
        return status;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public byte[] body() {
        return body;
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public URI uri() {
        return uri;
    }

    public long durationMs() {
        return durationMs;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public String header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    public String contentType() {
        String value = header("Content-Type");
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public String location() {
        return header("Location");
    }

    public boolean isRedirect() {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** True when AEM asks for authentication (401, redirect to the login page or the login page itself). */
    public boolean isLoginRequired() {
        if (status == 401) {
            return true;
        }
        if (isRedirect()) {
            String location = location();
            return location != null && (location.contains(LOGIN_PAGE) || location.contains("j_security_check"));
        }
        if (uri != null && uri.getPath() != null && uri.getPath().startsWith(LOGIN_PAGE)) {
            return true;
        }
        return false;
    }

    public Object json() {
        return Json.parse(text());
    }

    public Map<String, Object> jsonObject() {
        return Json.parseObject(text());
    }

    public String snippet(int maxChars) {
        return Text.oneLine(text(), maxChars);
    }

    /** Short status line for error messages. */
    public String describe() {
        return "HTTP " + status + " for " + (uri == null ? "?" : uri.getPath()) + " (" + durationMs + " ms)";
    }
}
