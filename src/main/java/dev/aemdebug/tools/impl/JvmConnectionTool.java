package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.PortOwners;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

/** jvm_connection: attach / detach / status of the exclusive JDWP connection of an instance. */
public final class JvmConnectionTool extends AbstractTool {

    private final Services s;

    public JvmConnectionTool(Services services) {
        super("jvm_connection", "JVM connection (JDWP)",
                "Attaches to / detaches from the JVM of an AEM instance over JDWP, or shows the status. The JDWP port "
                        + "is detected from the JVM that serves the instance's HTTP port (by default author 4502, "
                        + "publish 4503), so any port works; AEM_JDWP_PORT / AEM_PUBLISH_JDWP_PORT are only "
                        + "used when detection cannot run, and port= sets it for this chat. Status tells whether the "
                        + "JVM was started without JDWP or another debugger is connected. JDWP accepts ONE debugger per "
                        + "JVM: if IntelliJ's Remote JVM Debug session is connected, ask the user to stop it first. "
                        + "Detach when done so IntelliJ can reconnect; the server also detaches after inactivity. "
                        + "Breakpoints never pause AEM: they capture a snapshot of the hit thread and resume it.",
                Schema.object()
                        .requiredEnum("action", "attach, detach or status", "attach", "detach", "status")
                        .enumStr("instance", "author (default) or publish", "author", "publish")
                        .integer("port", "JDWP port of that instance for this chat, instead of the detected one; "
                                + "0 forgets a port given earlier so it is detected again", 0, 65535)
                        .integer("timeoutSeconds", "Attach timeout (default 5)", 1, 60));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String action = args.choice("action", "status", "attach", "detach", "status");
        AemInstance instance = JvmTools.instance(s, args);
        JdiEngine engine = JvmTools.engine(s, instance);
        StringBuilder out = new StringBuilder("### ").append(instance.name()).append(" JVM\n");
        Integer port = args.integer("port");
        if (port != null && port == 0) {
            s.jdwp.override(instance.name(), 0);
            out.append("Forgot the port given earlier in this chat; the port is detected from the JVM again.\n");
        } else if (port != null) {
            if (engine.isAttached() && PortOwners.portOf(String.valueOf(engine.endpoint())) != port) {
                throw new ToolException("Already attached to the " + instance.name() + " JVM at " + engine.endpoint()
                        + "; detach first to switch to port " + port + ".");
            }
            s.jdwp.override(instance.name(), port);
        }
        switch (action) {
            case "attach": {
                String result = JvmTools.attach(s, instance, args.integer("timeoutSeconds", 5, 1, 60) * 1000);
                return ToolResult.of(out.append(result).append("\n\n").append(engine.status()).toString());
            }
            case "detach":
                return ToolResult.of(out.append(engine.detach("requested")).toString());
            default: {
                out.append(engine.status());
                if (!engine.isAttached()) {
                    out.append(StatusSections.jdwp(s, instance));
                }
                out.append("Idle auto-detach after ").append(s.config.idleDetachMinutes())
                        .append(" min without activity.\n");
                return ToolResult.of(out.toString());
            }
        }
    }
}
