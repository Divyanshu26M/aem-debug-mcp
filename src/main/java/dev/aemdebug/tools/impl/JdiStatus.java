package dev.aemdebug.tools.impl;

import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.server.Services;

/** Describes the JDWP attachment held by this server, if any. */
final class JdiStatus {

    private JdiStatus() {
    }

    /** Returns a description when this server is attached, otherwise null. */
    static String attachedDescription(Services s) {
        JdiEngine engine = s.jdi;
        if (engine == null || !engine.isAttached()) {
            return null;
        }
        return "attached by aem-debug-mcp (" + engine.breakpointList().size() + " breakpoints, "
                + engine.watchList().size() + " exception watches, " + engine.snapshots().size()
                + " snapshots); jvm_connection action=detach hands the port back to IntelliJ";
    }
}
