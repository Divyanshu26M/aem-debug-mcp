package dev.aemdebug.tools.impl;

import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdwpPortProbe;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

/** jvm_connection: attach / detach / status of the exclusive JDWP connection. */
public final class JvmConnectionTool extends AbstractTool {

    private final Services s;

    public JvmConnectionTool(Services services) {
        super("jvm_connection", "JVM connection (JDWP)",
                "Attaches to / detaches from the AEM JVM over JDWP (AEM_JDWP_HOST:AEM_JDWP_PORT, default 127.0.0.1:8767) "
                        + "or shows the status. JDWP accepts ONE debugger: if IntelliJ's Remote JVM Debug session is "
                        + "connected, ask the user to stop it first. Detach when done so IntelliJ can reconnect; the "
                        + "server also detaches after inactivity. Breakpoints never pause AEM: they capture a snapshot "
                        + "of the hit thread and resume it.",
                Schema.object()
                        .requiredEnum("action", "attach, detach or status", "attach", "detach", "status")
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
        JdiEngine engine = JvmTools.engine(s);
        switch (action) {
            case "attach": {
                String result = JvmTools.attach(s, args.integer("timeoutSeconds", 5, 1, 60) * 1000);
                return ToolResult.of(result + "\n\n" + engine.status());
            }
            case "detach":
                return ToolResult.of(engine.detach("requested"));
            default: {
                StringBuilder sb = new StringBuilder(engine.status());
                if (!engine.isAttached()) {
                    JdwpPortProbe.Result probe = JdwpPortProbe.probe(s.config.jdwpHost(), s.config.jdwpPort(), 1500);
                    sb.append("JDWP ").append(s.config.jdwpHost()).append(':').append(s.config.jdwpPort()).append(": ");
                    if (probe.state == JdwpPortProbe.State.LISTENING) {
                        sb.append("free - attach is possible\n");
                    } else if (probe.state == JdwpPortProbe.State.NOT_ACCEPTING) {
                        sb.append("not accepting connections (IntelliJ or another debugger attached, or AEM not "
                                + "started with JDWP)\n");
                    } else {
                        sb.append(probe).append('\n');
                    }
                }
                sb.append("Idle auto-detach after ").append(s.config.idleDetachMinutes()).append(" min without activity.\n");
                return ToolResult.of(sb.toString());
            }
        }
    }
}
