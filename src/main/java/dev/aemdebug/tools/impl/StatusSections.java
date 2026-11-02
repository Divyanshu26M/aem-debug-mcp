package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.jdi.JdwpLocator;
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

    /**
     * One line about the JDWP endpoint of an instance, read fresh from its JVM: where the port comes from, and
     * whether a debugger can attach. Only ports that were not detected (set in this chat or configured) are probed.
     */
    static String jdwp(Services s, AemInstance instance) {
        String attached = JdiStatus.attachedDescription(s, instance);
        if (attached != null) {
            return "- " + instance.name() + " " + s.jdi(instance.name()).endpoint() + ": " + attached + '\n';
        }
        JdwpLocator.Endpoint endpoint = s.jdwp.endpoint(instance, true);
        String problem = endpoint.problem();
        if (problem != null) {
            return "- " + instance.name() + ": " + problem + '\n';
        }
        String head = "- " + instance.name() + " " + endpoint.address() + " (" + endpoint.origin() + ")";
        String attach = "jvm_connection action=attach" + (instance.isAuthor() ? "" : " instance=" + instance.name())
                + " (or debug_page) attaches on demand";
        if (endpoint.source() == JdwpLocator.Source.DETECTED) {
            return head + ": free - " + attach + '\n';
        }
        JdwpPortProbe.Result result = JdwpPortProbe.probe(endpoint.host(), endpoint.port(), 1500);
        switch (result.state) {
            case LISTENING:
                return head + ": free - a JDWP agent answers; " + attach + '\n';
            case NOT_ACCEPTING:
                return head + ": not accepting connections - either another debugger (e.g. IntelliJ remote debug) "
                        + "is attached, or " + instance.name() + " does not listen for debuggers on this port.\n";
            default:
                return head + ": " + result + '\n';
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
