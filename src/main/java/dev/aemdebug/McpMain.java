package dev.aemdebug;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.aemdebug.config.Config;
import dev.aemdebug.server.AemDebugServer;
import dev.aemdebug.server.Services;

/**
 * Entry point. stdout carries the MCP protocol only: it is captured for the transport and System.out is redirected
 * to stderr before anything else runs, so stray library output cannot corrupt the protocol stream.
 */
public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocolOut = System.out;
        System.setOut(System.err);
        System.setProperty("java.awt.headless", "true");
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");

        for (String arg : args) {
            if ("--version".equals(arg)) {
                System.err.println("aem-debug-mcp " + Version.get());
                return;
            }
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.err.println("aem-debug-mcp " + Version.get() + " - MCP server (stdio) for local AEM debugging.\n"
                        + "Configure with environment variables, see docs/setup.md. Options: --version");
                return;
            }
        }

        Config config = Config.fromEnvironment(System.getenv());
        Files.createDirectories(config.artifactsDir());
        Path logFile = config.artifactsDir().resolve("aem-debug-mcp.log");
        System.setProperty("aemdebug.logFile", logFile.toString());

        Services services = new Services(config);
        AemDebugServer server = new AemDebugServer(services);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutdown("JVM shutdown"), "aemdbg-shutdown"));

        InputStream in = new EofNotifyingInputStream(System.in, () -> {
            Thread exit = new Thread(() -> {
                server.shutdown("stdin closed");
                System.exit(0);
            }, "aemdbg-exit");
            exit.setDaemon(false);
            exit.start();
        });
        server.start(in, protocolOut);
        server.awaitShutdown();
    }

    /** Fires a callback once when the client closes stdin. */
    static final class EofNotifyingInputStream extends FilterInputStream {
        private final Runnable onEof;
        private final AtomicBoolean fired = new AtomicBoolean();

        EofNotifyingInputStream(InputStream in, Runnable onEof) {
            super(in);
            this.onEof = onEof;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                fire();
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int value;
            try {
                value = super.read(b, off, len);
            } catch (IOException e) {
                fire();
                throw e;
            }
            if (value < 0) {
                fire();
            }
            return value;
        }

        private void fire() {
            if (fired.compareAndSet(false, true)) {
                onEof.run();
            }
        }
    }
}
