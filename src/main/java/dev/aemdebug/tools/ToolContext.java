package dev.aemdebug.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-call context: progress notifications, elicitation (user confirmation) and client roots. Works without an
 * MCP exchange (tests, CLI probe), in which case those features are no-ops.
 */
public final class ToolContext {

    private static final Logger LOG = LoggerFactory.getLogger(ToolContext.class);
    private static final AtomicBoolean CLIENT_LOGGED = new AtomicBoolean();

    /** Outcome of a confirmation request. */
    public enum Confirmation {
        ACCEPTED, DECLINED, CANCELLED, UNSUPPORTED, FAILED
    }

    private final McpSyncServerExchange exchange;
    private final Object progressToken;
    private final AtomicInteger progress = new AtomicInteger();

    public ToolContext(McpSyncServerExchange exchange, Object progressToken) {
        this.exchange = exchange;
        this.progressToken = progressToken;
    }

    public static ToolContext detached() {
        return new ToolContext(null, null);
    }

    /** Sends a progress notification when the client asked for progress; otherwise just logs. */
    public void progress(String message) {
        LOG.debug("progress: {}", message);
        if (exchange == null || progressToken == null) {
            return;
        }
        int step = progress.incrementAndGet();
        try {
            exchange.progressNotification(
                    McpSchema.ProgressNotification.builder(progressToken, step).message(message).build());
        } catch (RuntimeException e) {
            LOG.debug("Progress notification failed: {}", e.toString());
        }
    }

    public boolean canElicit() {
        if (exchange == null) {
            return false;
        }
        try {
            McpSchema.ClientCapabilities capabilities = exchange.getClientCapabilities();
            return capabilities != null && capabilities.elicitation() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Asks the user (through the MCP client) to confirm a change. */
    public Confirmation confirm(String message) {
        if (!canElicit()) {
            return Confirmation.UNSUPPORTED;
        }
        Map<String, Object> confirmProperty = new LinkedHashMap<>();
        confirmProperty.put("type", "boolean");
        confirmProperty.put("title", "Apply this change");
        confirmProperty.put("description", "It is reverted automatically by debug_cleanup or when the debug session ends.");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("confirm", confirmProperty);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("confirm"));
        try {
            McpSchema.ElicitResult result =
                    exchange.createElicitation(McpSchema.ElicitFormRequest.builder(message, schema).build());
            if (result == null || result.action() == null) {
                return Confirmation.CANCELLED;
            }
            if (result.action() == McpSchema.ElicitResult.Action.ACCEPT) {
                Object value = result.content() == null ? null : result.content().get("confirm");
                boolean confirmed = Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
                return confirmed ? Confirmation.ACCEPTED : Confirmation.DECLINED;
            }
            if (result.action() == McpSchema.ElicitResult.Action.DECLINE) {
                return Confirmation.DECLINED;
            }
            return Confirmation.CANCELLED;
        } catch (RuntimeException e) {
            LOG.warn("Elicitation failed: {}", e.toString());
            return Confirmation.FAILED;
        }
    }

    /** File system roots announced by the client (e.g. the open workspace), if supported. */
    public List<Path> roots() {
        List<Path> paths = new ArrayList<>();
        if (exchange == null) {
            return paths;
        }
        try {
            McpSchema.ClientCapabilities capabilities = exchange.getClientCapabilities();
            boolean supported = capabilities != null && capabilities.roots() != null;
            if (CLIENT_LOGGED.compareAndSet(false, true)) {
                McpSchema.Implementation client = exchange.getClientInfo();
                LOG.info("MCP client {}: workspace roots {}, elicitation {}",
                        client == null ? "unknown" : client.name() + " " + client.version(),
                        supported ? "supported" : "not supported",
                        capabilities != null && capabilities.elicitation() != null ? "supported" : "not supported");
            }
            if (!supported) {
                return paths;
            }
            McpSchema.ListRootsResult result = exchange.listRoots();
            if (result == null || result.roots() == null) {
                return paths;
            }
            for (McpSchema.Root root : result.roots()) {
                try {
                    paths.add(Paths.get(URI.create(root.uri())));
                } catch (RuntimeException e) {
                    LOG.debug("Ignoring root {}: {}", root.uri(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("listRoots failed: {}", e.toString());
        }
        return paths;
    }
}
