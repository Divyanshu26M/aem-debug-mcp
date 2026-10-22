package dev.aemdebug.tools;

import dev.aemdebug.util.Args;

import java.util.Map;

/** An MCP tool exposed by the server. Implementations should be thread safe. */
public interface Tool {

    String name();

    String title();

    String description();

    Map<String, Object> inputSchema();

    /** True when the tool never changes AEM, JVM or local state (MCP readOnlyHint). */
    default boolean readOnly() {
        return true;
    }

    default boolean destructive() {
        return false;
    }

    default boolean idempotent() {
        return readOnly();
    }

    ToolResult call(ToolContext context, Args args) throws Exception;
}
