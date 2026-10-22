package dev.aemdebug.fixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP application used by the JDI integration tests. Like Sling's main servlet, each request thread is
 * renamed to "{remoteAddr} [{startMillis}] {METHOD} {uri} HTTP/1.1" while the request is processed.
 * Prints FIXTURE_PORT=n on stdout and exits when stdin is closed.
 */
public final class FixtureApp {

    private FixtureApp() {
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "fixture-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", FixtureApp::handle);
        server.setExecutor(workers);
        server.start();
        System.out.println("FIXTURE_PORT=" + server.getAddress().getPort());
        System.out.flush();
        while (System.in.read() >= 0) {
            // keep running until the parent closes stdin
        }
        server.stop(0);
        System.exit(0);
    }

    static void handle(HttpExchange exchange) throws IOException {
        Thread thread = Thread.currentThread();
        String original = thread.getName();
        long start = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        thread.setName(remote + " [" + start + "] " + exchange.getRequestMethod() + " " + path + " HTTP/1.1");
        int status = 200;
        String body;
        try {
            body = FixturePages.render(path);
        } catch (Exception e) {
            status = 500;
            body = "error: " + e;
        } finally {
            thread.setName(original);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
