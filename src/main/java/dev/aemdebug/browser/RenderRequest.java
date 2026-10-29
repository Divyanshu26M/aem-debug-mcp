package dev.aemdebug.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parameters of a headless render. */
public final class RenderRequest {

    private final String url;
    private final String origin;
    private int width = 1440;
    private int height = 900;
    private int timeoutMs = 45_000;
    private int settleMs = 500;
    private boolean fullPage = true;
    private boolean blockExternal;
    private Map<String, String> cookies = new LinkedHashMap<>();
    private SiteSession siteSession;
    private String sessionNote = "anonymous";
    private String probeScript;
    private Object probeArg;
    private final List<String> elementShots = new ArrayList<>();

    public RenderRequest(String url, String origin) {
        this.url = url;
        this.origin = origin;
    }

    public RenderRequest viewport(int w, int h) {
        this.width = w;
        this.height = h;
        return this;
    }

    public RenderRequest timeoutMs(int value) {
        this.timeoutMs = value;
        return this;
    }

    public RenderRequest settleMs(int value) {
        this.settleMs = value;
        return this;
    }

    public RenderRequest fullPage(boolean value) {
        this.fullPage = value;
        return this;
    }

    public RenderRequest blockExternal(boolean value) {
        this.blockExternal = value;
        return this;
    }

    public RenderRequest cookies(Map<String, String> value) {
        this.cookies = new LinkedHashMap<>(value);
        return this;
    }

    /** Renders with a captured site session (cookies and storage) instead of the session cookies. */
    public RenderRequest siteSession(SiteSession value) {
        this.siteSession = value;
        return this;
    }

    /** How the render is authenticated, for the report, e.g. "anonymous" or "AEM login of publish (admin)". */
    public RenderRequest sessionNote(String value) {
        this.sessionNote = value;
        return this;
    }

    /** JavaScript function source evaluated after load, e.g. {@code (arg) => {...}}. */
    public RenderRequest probe(String script, Object arg) {
        this.probeScript = script;
        this.probeArg = arg;
        return this;
    }

    public RenderRequest elementShot(String selector) {
        this.elementShots.add(selector);
        return this;
    }

    public String url() {
        return url;
    }

    public String origin() {
        return origin;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int settleMs() {
        return settleMs;
    }

    public boolean fullPage() {
        return fullPage;
    }

    public boolean blockExternal() {
        return blockExternal;
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public SiteSession siteSession() {
        return siteSession;
    }

    public String sessionNote() {
        return sessionNote;
    }

    public String probeScript() {
        return probeScript;
    }

    public Object probeArg() {
        return probeArg;
    }

    public List<String> elementShots() {
        return elementShots;
    }
}
