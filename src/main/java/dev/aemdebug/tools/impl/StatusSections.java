package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.jdi.JdwpPortProbe;
import dev.aemdebug.server.Services;

/** Shared status fragments used by aem_status and jvm_connection. */
final class StatusSections {

    private StatusSections() {
    }

    static String methodLabel(AuthManager.Method method) {
        if (method == null) {
            return "session";
        }
        switch (method) {
            case CREDENTIALS:
                return "AEM_USER credentials";
            case INTERACTIVE:
                return "interactive browser login";
            default:
                return "existing session";
        }
    }

    static String jdwp(Services s) {
        StringBuilder sb = new StringBuilder("\n### JVM debugging (JDWP)\n");
        if (s.config.jdwpHost() == null) {
            sb.append("- disabled: AEM_JDWP_HOST is not a local host\n");
            return sb.toString();
        }
        String endpoint = s.config.jdwpHost() + ":" + s.config.jdwpPort();
        String attached = JdiStatus.attachedDescription(s);
        if (attached != null) {
            sb.append("- ").append(endpoint).append(": ").append(attached).append('\n');
            return sb.toString();
        }
        JdwpPortProbe.Result result = JdwpPortProbe.probe(s.config.jdwpHost(), s.config.jdwpPort(), 1500);
        switch (result.state) {
            case LISTENING:
                sb.append("- ").append(endpoint).append(": free - AEM accepts a debugger; jvm_connection action=attach "
                        + "(or debug_page) can attach on demand\n");
                break;
            case NOT_ACCEPTING:
                sb.append("- ").append(endpoint).append(": not accepting connections - either another debugger "
                        + "(e.g. IntelliJ remote debug) is attached, or AEM was not started with "
                        + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=").append(s.config.jdwpPort())
                        .append(". Detach IntelliJ before using jvm_* tools.\n");
                break;
            default:
                sb.append("- ").append(endpoint).append(": ").append(result).append('\n');
                break;
        }
        return sb.toString();
    }
}
