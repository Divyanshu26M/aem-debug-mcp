package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdwpPortProbe;
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
                "Attaches to / detaches from the JVM of an AEM instance over JDWP, or shows the status. Author uses "
                        + "AEM_JDWP_HOST:AEM_JDWP_PORT (default 127.0.0.1:8767); publish runs in its own JVM and needs "
                        + "its own port (AEM_PUBLISH_JDWP_PORT, or pass port here). JDWP accepts ONE debugger per JVM: "
                        + "if IntelliJ's Remote JVM Debug session is connected, ask the user to stop it first. Detach "
                        + "when done so IntelliJ can reconnect; the server also detaches after inactivity. Breakpoints "
                        + "never pause AEM: they capture a snapshot of the hit thread and resume it.",
                Schema.object()
                        .requiredEnum("action", "attach, detach or status", "attach", "detach", "status")
                        .enumStr("instance", "author (default) or publish", "author", "publish")
                        .integer("port", "JDWP port of that instance for this chat (e.g. 8768 for publish); "
                                + "overrides the configuration", 1, 65535)
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
        Integer port = args.integer("port");
        if (port != null) {
            if (engine.isAttached() && port != s.jdwpPort(instance.name())) {
                throw new ToolException("Already attached to the " + instance.name() + " JVM at " + engine.endpoint()
                        + "; detach first to switch to port " + port + ".");
            }
            s.overrideJdwpPort(instance.name(), port);
        }
        String heading = "### " + instance.name() + " JVM (" + JvmTools.endpoint(s, instance) + ")\n";
        switch (action) {
            case "attach": {
                String result = JvmTools.attach(s, instance, args.integer("timeoutSeconds", 5, 1, 60) * 1000);
                return ToolResult.of(heading + result + "\n\n" + engine.status());
            }
            case "detach":
                return ToolResult.of(heading + engine.detach("requested"));
            default: {
                StringBuilder sb = new StringBuilder(heading).append(engine.status());
                if (!engine.isAttached()) {
                    String problem = JvmTools.endpointProblem(s, instance);
                    if (problem != null) {
                        sb.append(problem).append('\n');
                    } else {
                        int jdwpPort = s.jdwpPort(instance.name());
                        JdwpPortProbe.Result probe = JdwpPortProbe.probe(s.config.jdwpHost(), jdwpPort, 1500);
                        sb.append("JDWP ").append(s.config.jdwpHost()).append(':').append(jdwpPort).append(": ");
                        if (probe.state == JdwpPortProbe.State.LISTENING) {
                            sb.append("free - attach is possible\n");
                        } else if (probe.state == JdwpPortProbe.State.NOT_ACCEPTING) {
                            sb.append("not accepting connections (IntelliJ or another debugger attached, or ")
                                    .append(instance.name()).append(" not started with JDWP on this port)\n");
                        } else {
                            sb.append(probe).append('\n');
                        }
                    }
                }
                sb.append("Idle auto-detach after ").append(s.config.idleDetachMinutes()).append(" min without activity.\n");
                return ToolResult.of(sb.toString());
            }
        }
    }
}
