package dev.aemdebug.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything captured during a headless render. */
public final class RenderResult {

    String requestedUrl;
    String finalUrl;
    String title;
    int status = -1;
    long startedAt;
    long loadedAt;
    String navigationError;
    boolean networkIdle = true;
    String channel;
    String html;
    byte[] viewportJpeg;
    byte[] fullPagePng;
    Object probeResult;
    String probeError;
    final Map<String, byte[]> elementShots = new LinkedHashMap<>();
    final List<NetworkEntry> network = new ArrayList<>();
    final List<ConsoleEntry> console = new ArrayList<>();
    Map<String, Object> timings = new LinkedHashMap<>();

    public String requestedUrl() {
        return requestedUrl;
    }

    public String finalUrl() {
        return finalUrl;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }

    public long startedAt() {
        return startedAt;
    }

    public long loadedAt() {
        return loadedAt;
    }

    public String navigationError() {
        return navigationError;
    }

    public boolean networkIdle() {
        return networkIdle;
    }

    public String channel() {
        return channel;
    }

    public String html() {
        return html;
    }

    public byte[] viewportJpeg() {
        return viewportJpeg;
    }

    public byte[] fullPagePng() {
        return fullPagePng;
    }

    public Object probeResult() {
        return probeResult;
    }

    public String probeError() {
        return probeError;
    }

    public Map<String, byte[]> elementShots() {
        return elementShots;
    }

    public List<NetworkEntry> network() {
        return network;
    }

    public List<ConsoleEntry> console() {
        return console;
    }

    public Map<String, Object> timings() {
        return timings;
    }

    public NetworkEntry documentRequest() {
        for (NetworkEntry entry : network) {
            if (entry.navigation() && "document".equals(entry.resourceType())) {
                return entry;
            }
        }
        return network.isEmpty() ? null : network.get(0);
    }

    public List<NetworkEntry> failedRequests() {
        List<NetworkEntry> failed = new ArrayList<>();
        for (NetworkEntry entry : network) {
            if (entry.failed()) {
                failed.add(entry);
            }
        }
        return failed;
    }

    public List<ConsoleEntry> consoleErrors() {
        List<ConsoleEntry> errors = new ArrayList<>();
        for (ConsoleEntry entry : console) {
            if (entry.isError()) {
                errors.add(entry);
            }
        }
        return errors;
    }

    /** Builder-style setters for tests that construct results without a browser. */
    public static RenderResult forTest(String url, String html) {
        RenderResult result = new RenderResult();
        result.requestedUrl = url;
        result.finalUrl = url;
        result.html = html;
        result.status = 200;
        result.startedAt = System.currentTimeMillis();
        result.loadedAt = result.startedAt;
        return result;
    }

    public RenderResult withProbeResult(Object value) {
        this.probeResult = value;
        return this;
    }

    public RenderResult withNetwork(NetworkEntry entry) {
        this.network.add(entry);
        return this;
    }

    public RenderResult withConsole(ConsoleEntry entry) {
        this.console.add(entry);
        return this;
    }
}
