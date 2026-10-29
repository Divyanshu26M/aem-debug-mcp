package dev.aemdebug.browser;

import com.microsoft.playwright.options.Cookie;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aemdebug.config.Config;
import dev.aemdebug.server.Services;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.tools.impl.ToolFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page served by a local front-end dev server that answers every page route with its app shell and proxies /api to
 * publish ({@code mvn verify -Pbrowser-tests}).
 */
@Tag("browser")
class DevServerBrowserTest {

    private static final String APP_SHELL = "<!DOCTYPE html><html><head><title>Dev app</title></head><body>"
            + "<div id=\"token\"></div><div id=\"profile\"></div><script>"
            + "document.getElementById('token').textContent = localStorage.getItem('accessToken') ? 'token present'"
            + " : 'no token';"
            + "fetch('/api/profile').then(r => r.text().then(t => {"
            + " document.getElementById('profile').textContent = 'profile ' + r.status + ' ' + t; }));"
            + "</script></body></html>";

    /** Stands in for the user signing in: the page stores a session cookie and tokens like a real app would. */
    private static final String LOGIN_PAGE = "<!DOCTYPE html><html><head><title>Sign in</title></head><body>"
            + "Signed in<script>document.cookie = 'app-session=visitor-1-secret-cookie; path=/';"
            + "localStorage.setItem('accessToken', 'token-from-storage-xyz');"
            + "sessionStorage.setItem('nonce', 'nonce-from-session-xyz');</script></body></html>";

    @TempDir
    Path temp;

    /** The fake dev server: /api is proxied to publish unchanged, everything else returns the app shell. */
    private static HttpServer devServer(String publishUrl) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpClient client = HttpClient.newHttpClient();
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                if (path.startsWith("/api/")) {
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(publishUrl + path)).GET();
                    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                    if (cookie != null) {
                        request.header("Cookie", cookie);
                    }
                    HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                    reply(exchange, response.statusCode(), "application/json", response.body());
                } else if (path.equals("/login")) {
                    reply(exchange, 200, "text/html; charset=utf-8", LOGIN_PAGE.getBytes(StandardCharsets.UTF_8));
                } else {
                    reply(exchange, 200, "text/html; charset=utf-8", APP_SHELL.getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply(exchange, 502, "text/plain", new byte[0]);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static FakeAem publish() throws IOException {
        FakeAem publish = FakeAem.start();
        publish.requireAuth(false);
        publish.route("/api/profile", r -> r.cookieHeader != null && r.cookieHeader.contains("app-session=visitor-1")
                ? new FakeAem.Reply(200, "application/json", "{\"name\":\"Visitor\"}")
                : new FakeAem.Reply(401, "application/json", "{\"error\":\"sign in\"}"));
        // A minimal Recent Requests console listing the /api requests publish really received.
        publish.route("/system/console/requests", r -> {
            if (r.query != null && r.query.contains("index=")) {
                return new FakeAem.Reply(200, "text/html", "<html><body><pre>0 TIMER_START{Request Processing}\n"
                        + "3 TIMER_END{3,Request Processing} Request Processing ended\n</pre></body></html>");
            }
            StringBuilder html = new StringBuilder("<html><body><table>");
            List<String> received = publish.requests();
            for (int i = 0; i < received.size(); i++) {
                if (received.get(i).startsWith("GET /api/")) {
                    html.append("<tr><td><a href=\"requests?index=").append(i).append("\">").append(received.get(i))
                            .append(" (anonymous)</a></td><td>12:00:00</td></tr>");
                }
            }
            return new FakeAem.Reply(200, "text/html", html.append("</table></body></html>").toString());
        });
        return publish;
    }

    @Test
    void debugPageOnADevServerReportsTheBrowserSideAndWhatReachedPublish() throws Exception {
        try (FakeAem publish = publish()) {
            HttpServer dev = devServer(publish.baseUrl());
            String devOrigin = "http://127.0.0.1:" + dev.getAddress().getPort();
            Config config = Config.builder()
                    .authorUrl("http://127.0.0.1:1")
                    .publishUrl(publish.baseUrl())
                    .credentials("admin", "admin")
                    .artifactsDir(Paths.get("target", "browser-test-artifacts").toAbsolutePath())
                    .build();
            Services services = new Services(config);
            try {
                ToolRegistry registry = ToolFactory.createRegistry(services);
                ToolResult result = registry.invoke("debug_page", ToolContext.detached(), Map.of(
                        "path", devOrigin + "/content/site/en.html", "instance", "publish", "jvm", false));
                String text = result.allText();
                assertThat(result.isError()).as(text).isFalse();
                assertThat(text).contains("## Debug report").contains(devOrigin + "/content/site/en.html")
                        .contains("dev server " + devOrigin + " -> publish (instance argument)")
                        .contains("Session: anonymous")
                        .contains("### Dev server")
                        .contains("Document: served by the dev server itself (not among publish's requests)")
                        .contains("Requests that reached publish: 1 of ")
                        .contains("  - GET /api/profile (HTTP 401, fetch)")
                        .contains("Request Progress Tracker: 1 request(s)")
                        .contains("Not mapped: the page was loaded from the dev server")
                        .contains("1 local request refused (e.g. GET /api/profile -> 401)")
                        .contains("aem_auth action=site-login url=" + devOrigin + "/content/site/en.html")
                        .doesNotContain("HTTP 404: check the path");
                assertThat(publish.requests()).contains("GET /api/profile")
                        .noneMatch(r -> r.startsWith("GET /content/site/en.html"));
                assertThat(result.imageCount()).isEqualTo(1);

                ToolResult again = registry.invoke("page_render", ToolContext.detached(), Map.of(
                        "path", devOrigin + "/content/site/en.html", "screenshot", false));
                assertThat(again.allText()).as("the dev server mapping is remembered")
                        .contains("dev server " + devOrigin + " -> publish (remembered mapping)");
            } finally {
                services.cleanup.runAll(ToolContext.detached(), true);
                services.browser.close();
                dev.stop(0);
            }
        }
    }

    @Test
    void theSiteLoginWindowIsCapturedInMemoryAndUsedByDevServerRenders() throws Exception {
        System.setProperty("aemdebug.headlessLoginWindows", "true");
        try (FakeAem publish = publish()) {
            HttpServer dev = devServer(publish.baseUrl());
            String devOrigin = "http://127.0.0.1:" + dev.getAddress().getPort();
            Config config = Config.builder()
                    .authorUrl("http://127.0.0.1:1")
                    .publishUrl(publish.baseUrl())
                    .artifactsDir(temp)
                    .build();
            Services services = new Services(config);
            try {
                ToolRegistry registry = ToolFactory.createRegistry(services);
                ToolContext context = ToolContext.detached();
                assertThat(registry.invoke("aem_auth", context, Map.of("action", "site-login", "url",
                        devOrigin + "/login")).allText()).contains("Opened a site login window at " + devOrigin + "/login");
                assertThat(registry.invoke("aem_auth", context, Map.of("action", "site-login", "url",
                        devOrigin + "/other")).allText()).contains("already open").contains("never replaced");

                String status = registry.invoke("aem_auth", context, Map.of("action", "status", "instance",
                        "publish")).allText();
                assertThat(status).contains("Site session (memory only): 1 cookie (app-session)")
                        .contains("local storage: " + devOrigin + " (1 key)")
                        .contains("session storage: " + devOrigin + " (1 key)")
                        .contains("login window still open")
                        .doesNotContain("secret-cookie").doesNotContain("from-storage");

                String signedIn = registry.invoke("page_render", context, Map.of("path", devOrigin + "/account",
                        "instance", "publish", "screenshot", false)).allText();
                assertThat(signedIn).contains("Session: site session captured").contains("1 cookie, 2 storage keys")
                        .doesNotContain("refused").doesNotContain("401");

                String anonymous = registry.invoke("page_render", context, Map.of("path", devOrigin + "/account",
                        "session", "none", "screenshot", false)).allText();
                assertThat(anonymous).contains("Session: anonymous").contains("1 local request refused");

                // debug_cleanup closes the window but keeps its capture; a new site login must wait for the new
                // window instead of reporting that older capture as a finished login.
                registry.invoke("debug_cleanup", context, Map.of());
                assertThat(services.browser.siteSessionSnapshot().windowOpen()).isFalse();
                String relogin = registry.invoke("aem_auth", context, Map.of("action", "site-login", "url",
                        devOrigin + "/", "waitSeconds", 2)).allText();
                assertThat(relogin).contains("Opened a site login window").contains("login window still open")
                        .doesNotContain("closed the site login window");

                assertThat(registry.invoke("aem_auth", context, Map.of("action", "site-logout")).allText())
                        .contains("Closed the site login window");
                assertThat(services.browser.siteSessionSnapshot()).isNull();
            } finally {
                services.cleanup.runAll(ToolContext.detached(), true);
                services.browser.close();
                dev.stop(0);
                System.clearProperty("aemdebug.headlessLoginWindows");
            }
            try (Stream<Path> files = Files.walk(temp)) {
                for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    assertThat(content).as(file.toString()).doesNotContain("visitor-1-secret-cookie")
                            .doesNotContain("token-from-storage-xyz").doesNotContain("nonce-from-session-xyz");
                }
            }
        }
    }

    @Test
    void aSiteSessionCarriesCookiesAndStorageIntoTheRender() throws Exception {
        try (FakeAem publish = publish()) {
            HttpServer dev = devServer(publish.baseUrl());
            String devOrigin = "http://127.0.0.1:" + dev.getAddress().getPort();
            Config config = Config.builder()
                    .authorUrl("http://127.0.0.1:1")
                    .publishUrl(publish.baseUrl())
                    .artifactsDir(Paths.get("target", "browser-test-artifacts").toAbsolutePath())
                    .build();
            Services services = new Services(config);
            try {
                Cookie cookie = new Cookie("app-session", "visitor-1");
                cookie.setDomain("127.0.0.1");
                cookie.setPath("/");
                SiteSession site = new SiteSession(List.of(cookie),
                        Map.of(devOrigin, Map.of("accessToken", "abc")), Map.of(), devOrigin + "/", 1L, false);

                RenderResult signedIn = services.browser.render(new RenderRequest(devOrigin + "/account", devOrigin)
                        .fullPage(false).siteSession(site));
                assertThat(signedIn.html()).contains("token present").contains("profile 200 {\"name\":\"Visitor\"}");

                RenderResult anonymous = services.browser.render(new RenderRequest(devOrigin + "/account", devOrigin)
                        .fullPage(false));
                assertThat(anonymous.html()).contains("no token").contains("profile 401");
            } finally {
                services.cleanup.runAll(ToolContext.detached(), true);
                services.browser.close();
                dev.stop(0);
            }
        }
    }
}
