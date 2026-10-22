package dev.aemdebug.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.util.Args;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Holds the tools, adapts them to MCP specifications and applies uniform error handling. */
public final class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    /** Text budget per tool result; larger artifacts are written to files and referenced. */
    public static final int MAX_TEXT_CHARS = 30_000;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ActivityTracker activity;

    public ToolRegistry(ActivityTracker activity) {
        this.activity = activity;
    }

    public ToolRegistry register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("Duplicate tool name " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public Collection<Tool> tools() {
        return tools.values();
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** Invokes a tool by name (used by tests and the in-process probe). */
    public ToolResult invoke(String name, ToolContext context, Map<String, Object> arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("Unknown tool " + name);
        }
        return invoke(tool, context, arguments);
    }

    public ToolResult invoke(Tool tool, ToolContext context, Map<String, Object> arguments) {
        activity.touch();
        long start = System.nanoTime();
        Args args = new Args(arguments);
        try {
            LOG.info("tool {} called with {}", tool.name(), args);
            ToolResult result = tool.call(context, args);
            return result == null ? ToolResult.of("(no output)") : result;
        } catch (ToolException e) {
            LOG.info("tool {} failed: {}", tool.name(), e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            LOG.info("tool {} rejected arguments: {}", tool.name(), e.getMessage());
            return ToolResult.error("Invalid arguments: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted");
        } catch (Exception | LinkageError e) {
            LOG.error("tool {} failed unexpectedly", tool.name(), e);
            return ToolResult.error("Unexpected error in " + tool.name() + ": " + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + " (see the aem-debug-mcp server log for details)");
        } finally {
            activity.touch();
            LOG.info("tool {} finished in {} ms", tool.name(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
        for (Tool tool : tools.values()) {
            McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                    .title(tool.title())
                    .readOnlyHint(tool.readOnly())
                    .destructiveHint(tool.destructive())
                    .idempotentHint(tool.idempotent())
                    .openWorldHint(false)
                    .build();
            McpSchema.Tool definition = McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                    .title(tool.title())
                    .description(tool.description())
                    .annotations(annotations)
                    .build();
            specifications.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(definition)
                    .callHandler((exchange, request) -> invoke(tool,
                            new ToolContext(exchange, request.progressToken()), request.arguments())
                            .toMcp(MAX_TEXT_CHARS))
                    .build());
        }
        return specifications;
    }
}
