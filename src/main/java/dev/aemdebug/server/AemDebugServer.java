package dev.aemdebug.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.Version;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.impl.ToolFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds and runs the MCP server over stdio. */
public final class AemDebugServer {

    private static final Logger LOG = LoggerFactory.getLogger(AemDebugServer.class);

    public static final String NAME = "aem-debug-mcp";

    static final String INSTRUCTIONS = String.join("\n",
            "aem-debug-mcp debugs a LOCAL AEM instance (author http://localhost:4502 by default) end to end.",
            "Typical loop: aem_status -> debug_page (render + component map + server-side exceptions + logs for that",
            "request) -> component_inspect / aem_get_resource / aem_get_model_json -> jvm_breakpoint snapshots or",
            "jvm_exception_watch -> re-run debug_page -> fix code -> redeploy -> verify with debug_page.",
            "AEM login: tools open a visible login window when needed; never ask the user for passwords.",
            "JVM tools attach exclusively to the JDWP port; ask the user to detach IntelliJ first and hand the port back",
            "with jvm_connection action=detach. Never set aem_debug_toggle confirm=true without the user's explicit OK.",
            "Finish with debug_cleanup (removes breakpoints, reverts debug toggles, detaches JDWP, closes browsers).");

    private final Services services;
    private final ToolRegistry registry;
    private final CleanupCoordinator cleanup;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final ScheduledExecutorService housekeeping;
    private McpSyncServer server;

    public AemDebugServer(Services services) {
        this.services = services;
        this.registry = ToolFactory.createRegistry(services);
        this.cleanup = services.cleanup;
        this.housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aemdbg-housekeeping");
            t.setDaemon(true);
            return t;
        });
    }

    public ToolRegistry registry() {
        return registry;
    }

    public void start(InputStream in, OutputStream out) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, in, out);
        server = McpServer.sync(transport)
                .serverInfo(NAME, Version.get())
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .resources(false, false)
                        .logging()
                        .build())
                .requestTimeout(Duration.ofMinutes(10))
                .validateToolInputs(false)
                .tools(registry.specifications())
                .resources(ReportResources.index(services.artifacts))
                .resourceTemplates(ReportResources.template(services.artifacts))
                .build();
        housekeeping.scheduleWithFixedDelay(this::idleCheck, 30, 30, TimeUnit.SECONDS);
        cleanup.recoverFromPreviousRun();
        LOG.info("{} {} started with {} tools; config {}", NAME, Version.get(), registry.tools().size(),
                services.config);
    }

    private void idleCheck() {
        try {
            int minutes = services.config.idleDetachMinutes();
            if (minutes > 0 && services.activity.idleMillis() > minutes * 60_000L) {
                cleanup.onIdle();
            }
        } catch (RuntimeException e) {
            LOG.warn("idle check failed", e);
        }
    }

    /** Releases JDWP, reverts toggles and closes browsers; idempotent. */
    public void shutdown(String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down: {}", reason);
        try {
            cleanup.onShutdown();
        } catch (RuntimeException e) {
            LOG.warn("cleanup during shutdown failed", e);
        }
        try {
            if (server != null) {
                server.close();
            }
        } catch (RuntimeException e) {
            LOG.debug("server close failed: {}", e.toString());
        }
        housekeeping.shutdownNow();
        services.browser.shutdown();
        stopped.countDown();
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }
}
