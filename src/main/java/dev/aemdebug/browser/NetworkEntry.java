package dev.aemdebug.browser;

import java.net.URI;

/** One network request observed by the browser during a render. Mutable while the page loads. */
public final class NetworkEntry {

    private final String method;
    private final String url;
    private final String resourceType;
    private final long startedAt;
    private final boolean navigation;
    volatile int status = -1;
    volatile String contentType;
    volatile String failure;
    volatile long finishedAt;

    public NetworkEntry(String method, String url, String resourceType, long startedAt, boolean navigation) {
        this.method = method;
        this.url = url;
        this.resourceType = resourceType;
        this.startedAt = startedAt;
        this.navigation = navigation;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    /** Path part of the URL (no query), as used in Sling thread names. */
    public String path() {
        try {
            String path = URI.create(url).getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            int q = url.indexOf('?');
            return q >= 0 ? url.substring(0, q) : url;
        }
    }

    public String host() {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String resourceType() {
        return resourceType;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean navigation() {
        return navigation;
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public String failure() {
        return failure;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public long durationMs() {
        return finishedAt > 0 ? finishedAt - startedAt : -1;
    }

    public boolean failed() {
        return failure != null || status >= 400;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setFailure(String failure) {
        this.failure = failure;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
