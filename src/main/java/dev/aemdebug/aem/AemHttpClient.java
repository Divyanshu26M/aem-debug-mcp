package dev.aemdebug.aem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.tools.ToolException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP access to local AEM. Only local hosts are allowed. Reads use GET; POST is only possible with an explicit
 * {@link WriteReason} (login and user-confirmed debug toggles).
 */
public final class AemHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(AemHttpClient.class);
    private static final int MAX_REDIRECTS = 5;

    /** The only reasons the server may send a non-GET request to AEM. */
    public enum WriteReason {
        LOGIN, CONFIRMED_DEBUG_TOGGLE
    }

    private final HttpClient client;
    private final CookieJar cookies;

    public AemHttpClient(CookieJar cookies) {
        this.cookies = cookies;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public CookieJar cookies() {
        return cookies;
    }

    public AemResponse get(AemInstance instance, String pathAndQuery) {
        return get(instance, pathAndQuery, RequestOptions.defaults());
    }

    public AemResponse get(AemInstance instance, String pathAndQuery, RequestOptions options) {
        return send(instance, "GET", pathAndQuery, null, null, options);
    }

    /** Form POST; only for login and user-confirmed debug toggles. Request bodies are never logged. */
    public AemResponse postForm(AemInstance instance, String path, List<String[]> form, WriteReason reason,
                                RequestOptions options) {
        if (reason == null) {
            throw new IllegalArgumentException("A write reason is required for POST requests");
        }
        StringBuilder body = new StringBuilder();
        for (String[] pair : form) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(java.net.URLEncoder.encode(pair[0], StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(pair[1] == null ? "" : pair[1], StandardCharsets.UTF_8));
        }
        LOG.info("POST {} on {} ({})", path, instance.name(), reason);
        return send(instance, "POST", path, body.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded; charset=UTF-8", options.withoutRedirects());
    }

    private AemResponse send(AemInstance instance, String method, String pathAndQuery, byte[] body,
                             String contentType, RequestOptions options) {
        URI uri = instance.uri(pathAndQuery);
        HostPolicy.requireLocal(uri);
        long start = System.nanoTime();
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(options.timeout())
                    .header("User-Agent", "aem-debug-mcp");
            String cookieHeader = cookies.cookieHeader(instance.origin(), options.extraCookies(), options.sendSession());
            if (!cookieHeader.isEmpty()) {
                builder.header("Cookie", cookieHeader);
            }
            for (Map.Entry<String, String> header : options.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", contentType);
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.GET();
            }
            HttpResponse<byte[]> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpConnectTimeoutException e) {
                throw new AemUnavailableException("Timed out connecting to AEM " + instance.name() + " at "
                        + instance.origin() + ". Is AEM running?", e);
            } catch (ConnectException e) {
                throw new AemUnavailableException("Cannot connect to AEM " + instance.name() + " at "
                        + instance.origin() + " (connection refused). Is AEM running?", e);
            } catch (HttpTimeoutException e) {
                throw new AemUnavailableException("AEM " + instance.name() + " did not answer " + uri.getPath()
                        + " within " + options.timeout().toSeconds() + " s", e);
            } catch (IOException e) {
                throw new AemUnavailableException("I/O error talking to AEM " + instance.name() + " ("
                        + uri.getPath() + "): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("Interrupted while calling AEM");
            }
            storeCookies(instance, response.headers().allValues("Set-Cookie"));
            int status = response.statusCode();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            AemResponse result = new AemResponse(status, response.headers().map(), response.body(), uri, elapsed);
            LOG.debug("{} {} -> {} ({} ms)", method, uri.getPath(), status, elapsed);
            if (options.followRedirects() && "GET".equals(method) && result.isRedirect() && hop < MAX_REDIRECTS) {
                String location = result.location();
                if (location != null && !result.isLoginRequired()) {
                    URI next = uri.resolve(location);
                    if (HostPolicy.isLocalHost(next.getHost())) {
                        uri = next;
                        continue;
                    }
                }
            }
            return result;
        }
    }

    private void storeCookies(AemInstance instance, List<String> setCookieHeaders) {
        for (String header : setCookieHeaders) {
            try {
                for (HttpCookie cookie : HttpCookie.parse(header)) {
                    if (cookie.hasExpired() || cookie.getMaxAge() == 0) {
                        cookies.remove(instance.origin(), cookie.getName());
                    } else {
                        cookies.put(instance.origin(), cookie.getName(), cookie.getValue());
                    }
                }
            } catch (IllegalArgumentException e) {
                LOG.debug("Ignoring unparsable Set-Cookie header");
            }
        }
    }
}
