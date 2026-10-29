package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.server.Services;

/** Describes the JDWP attachment held by this server, if any. */
final class JdiStatus {

    private JdiStatus() {
    }

    /** Returns a description when this server is attached to the JVM of the instance, otherwise null. */
    static String attachedDescription(Services s, AemInstance instance) {
        JdiEngine engine = s.jdi(instance.name());
        if (engine == null || !engine.isAttached()) {
            return null;
        }
        return "attached by aem-debug-mcp (" + engine.breakpointList().size() + " breakpoints, "
                + engine.watchList().size() + " exception watches, " + engine.snapshots().size()
                + " snapshots); jvm_connection action=detach" + (instance.isAuthor() ? "" : " instance=" + instance.name())
                + " hands the port back to IntelliJ";
    }
}
