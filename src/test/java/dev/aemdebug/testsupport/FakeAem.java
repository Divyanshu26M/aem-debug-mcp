package dev.aemdebug.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Minimal fake of a local AEM instance for tests: login page, j_security_check (admin/admin), current user,
 * plus configurable routes. Unauthenticated requests to protected paths are redirected to the login page.
 */
public final class FakeAem implements AutoCloseable {

    public static final String TOKEN = "fake-login-token-0123";
    public static final String LOGIN_PAGE = "/libs/granite/core/content/login.html";

    /** A canned reply. */
    public static final class Reply {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        public Reply(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }

        public Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    /** Dynamic route. */
    public interface Handler {
        Reply handle(Request request);
    }

    /** What a handler sees. */
    public static final class Request {
        public final String method;
        public final String path;
        public final String query;
        public final String cookieHeader;
        public final Map<String, String> form;

        Request(String method, String path, String query, String cookieHeader, Map<String, String> form) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.cookieHeader = cookieHeader;
            this.form = form;
        }

        public boolean authenticated() {
            return cookieHeader != null && cookieHeader.contains("login-token=" + TOKEN);
        }
    }

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "fake-aem");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final Set<String> publicPaths = ConcurrentHashMap.newKeySet();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private volatile boolean requireAuth = true;

    private FakeAem() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        publicPaths.add(LOGIN_PAGE);
        publicPaths.add(LOGIN_PAGE + "/j_security_check");
        publicPaths.add("/libs/granite/security/currentuser.json");
        route(LOGIN_PAGE, r -> new Reply(200, "text/html", "<html><body><form id='login'>AEM Sign In</form></body></html>"));
        route(LOGIN_PAGE + "/j_security_check", r -> {
            if ("admin".equals(r.form.get("j_username")) && "admin".equals(r.form.get("j_password"))) {
                return new Reply(200, "text/plain", "OK")
                        .header("Set-Cookie", "login-token=" + TOKEN + "; Path=/; HttpOnly");
            }
            return new Reply(403, "text/plain", "j_reason=invalid_login");
        });
        route("/libs/granite/security/currentuser.json", r -> new Reply(200, "application/json",
                "{\"authorizableId\":\"" + (r.authenticated() ? "admin" : "anonymous") + "\",\"home\":\"/home/users/a\"}"));
    }

    public static FakeAem start() throws IOException {
        FakeAem fake = new FakeAem();
        fake.server.start();
        return fake;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public FakeAem route(String path, Handler handler) {
        routes.put(path, handler);
        return this;
    }

    public FakeAem json(String path, String json) {
        return route(path, r -> new Reply(200, "application/json;charset=utf-8", json));
    }

    public FakeAem text(String path, String text) {
        return route(path, r -> new Reply(200, "text/plain;charset=utf-8", text));
    }

    public FakeAem html(String path, String html) {
        return route(path, r -> new Reply(200, "text/html;charset=utf-8", html));
    }

    public FakeAem publicPath(String path) {
        publicPaths.add(path);
        return this;
    }

    public FakeAem requireAuth(boolean value) {
        this.requireAuth = value;
        return this;
    }

    /** "METHOD /path?query" of every request received. */
    public List<String> requests() {
        return log;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            Map<String, String> form = new LinkedHashMap<>();
            if ("POST".equals(method)) {
                try (InputStream in = exchange.getRequestBody()) {
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String pair : body.split("&")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            log.add(method + " " + path + (query == null ? "" : "?" + query));
            Request request = new Request(method, path, query, cookie, form);
            Reply reply;
            if (requireAuth && !publicPaths.contains(path) && !request.authenticated()) {
                reply = new Reply(302, "text/html", "").header("Location", LOGIN_PAGE + "?resource="
                        + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
            } else {
                Handler handler = routes.get(path);
                reply = handler == null ? new Reply(404, "text/html", "<html>Not found " + path + "</html>")
                        : handler.handle(request);
            }
            exchange.getResponseHeaders().add("Content-Type", reply.contentType);
            for (Map.Entry<String, String> header : reply.headers.entrySet()) {
                exchange.getResponseHeaders().add(header.getKey(), header.getValue());
            }
            exchange.sendResponseHeaders(reply.status, reply.body.length == 0 ? -1 : reply.body.length);
            if (reply.body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply.body);
                }
            }
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
