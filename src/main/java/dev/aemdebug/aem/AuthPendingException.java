package dev.aemdebug.aem;

import dev.aemdebug.tools.ToolException;

/** Authentication is required and an interactive login is in progress (or could not be completed yet). */
public class AuthPendingException extends ToolException {

    private static final long serialVersionUID = 1L;

    public AuthPendingException(String message) {
        super(message);
    }
}
