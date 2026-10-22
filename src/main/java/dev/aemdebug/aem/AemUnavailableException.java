package dev.aemdebug.aem;

import dev.aemdebug.tools.ToolException;

/** AEM could not be reached (not running, wrong port, timeout). */
public class AemUnavailableException extends ToolException {

    private static final long serialVersionUID = 1L;

    public AemUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
