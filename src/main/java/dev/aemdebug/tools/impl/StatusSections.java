package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.jdi.JdwpPortProbe;
import dev.aemdebug.server.Services;

import java.util.ArrayList;
import java.util.List;

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
            case REMEMBERED:
                return "login remembered from an earlier chat";
            default:
                return "existing session";
        }
    }

    static String jdwp(Services s) {
        return jdwp(s, configured(s));
    }

    /** JDWP lines for the given instances. */
    static String jdwp(Services s, List<AemInstance> instances) {
        StringBuilder sb = new StringBuilder("\n### JVM debugging (JDWP)\n");
        if (s.config.jdwpHost() == null) {
            sb.append("- disabled: AEM_JDWP_HOST is not a local host\n");
            return sb.toString();
        }
        for (AemInstance instance : instances) {
            sb.append(jdwp(s, instance));
        }
        return sb.toString();
    }

    /** One line about the JDWP endpoint of an instance. */
    static String jdwp(Services s, AemInstance instance) {
        String problem = JvmTools.endpointProblem(s, instance);
        if (problem != null) {
            return "- " + instance.name() + ": " + problem + '\n';
        }
        int port = s.jdwpPort(instance.name());
        String endpoint = instance.name() + " " + s.config.jdwpHost() + ":" + port;
        String attached = JdiStatus.attachedDescription(s, instance);
        if (attached != null) {
            return "- " + endpoint + ": " + attached + '\n';
        }
        JdwpPortProbe.Result result = JdwpPortProbe.probe(s.config.jdwpHost(), port, 1500);
        switch (result.state) {
            case LISTENING:
                return "- " + endpoint + ": free - AEM accepts a debugger; jvm_connection action=attach"
                        + (instance.isAuthor() ? "" : " instance=" + instance.name())
                        + " (or debug_page) can attach on demand\n";
            case NOT_ACCEPTING:
                return "- " + endpoint + ": not accepting connections - either another debugger (e.g. IntelliJ "
                        + "remote debug) is attached, or " + instance.name() + " was not started with "
                        + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port
                        + ". Detach IntelliJ before using jvm_* tools.\n";
            default:
                return "- " + endpoint + ": " + result + '\n';
        }
    }

    private static List<AemInstance> configured(Services s) {
        List<AemInstance> list = new ArrayList<>();
        if (s.instances.authorOrNull() != null) {
            list.add(s.instances.authorOrNull());
        }
        if (s.instances.publishOrNull() != null) {
            list.add(s.instances.publishOrNull());
        }
        return list;
    }
}
