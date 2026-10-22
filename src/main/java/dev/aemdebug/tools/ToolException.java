package dev.aemdebug.tools;

/** A failure with a message that is meant for the agent/user (returned as an MCP tool error). */
public class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
