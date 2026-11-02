package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.BreakpointSpec;
import dev.aemdebug.jdi.ExceptionWatchSpec;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdiSupport;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Args;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared helpers for the jvm_* and debug_* tools. */
final class JvmTools {

    /** Throw-site packages watched by default in addition to the project's own packages. */
    static final List<String> DEFAULT_WATCH_PACKAGES =
            List.of("org.apache.sling.models", "org.apache.sling.scripting.sightly");

    /** Suggested JDWP port for publish, next to the usual author port. */
    static final int SUGGESTED_PUBLISH_PORT = 8768;

    private JvmTools() {
    }

    /** The instance argument of the jvm_* tools (author by default). */
    static AemInstance instance(Services s, Args args) {
        return s.instances.resolve(args.str("instance"));
    }

    static JdiEngine engine(Services s, AemInstance instance) {
        JdiEngine engine = s.jdi(instance.name());
        if (engine == null) {
            throw new ToolException(JdiSupport.unavailableMessage());
        }
        return engine;
    }

    /** Why the JVM of an instance cannot be reached over JDWP, or null when host and port are known. */
    static String endpointProblem(Services s, AemInstance instance) {
        if (s.config.jdwpHost() == null) {
            return "JVM debugging is disabled: AEM_JDWP_HOST is not a local host.";
        }
        if (s.jdwpPort(instance.name()) < 0) {
            return "No JDWP port is configured for " + instance.name() + ". Start " + instance.name()
                    + " with -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + SUGGESTED_PUBLISH_PORT
                    + " (a port of your choice, not the author's), then set AEM_PUBLISH_JDWP_PORT=" + SUGGESTED_PUBLISH_PORT
                    + " in the MCP server environment or call jvm_connection action=attach instance=" + instance.name()
                    + " port=" + SUGGESTED_PUBLISH_PORT + ".";
        }
        return null;
    }

    /** "publish 127.0.0.1:8768" or "publish (no JDWP port)". */
    static String endpoint(Services s, AemInstance instance) {
        int port = s.jdwpPort(instance.name());
        return instance.name() + (port < 0 || s.config.jdwpHost() == null ? " (no JDWP port)"
                : " " + s.config.jdwpHost() + ":" + port);
    }

    /** Attaches, retrying briefly: the JDWP agent re-opens its port a moment after a probe or a detach. */
    static String attach(Services s, AemInstance instance, int timeoutMs) {
        JdiEngine engine = engine(s, instance);
        String problem = endpointProblem(s, instance);
        if (problem != null) {
            throw new ToolException(problem);
        }
        ToolException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return engine.attach(s.config.jdwpHost(), s.jdwpPort(instance.name()), timeoutMs);
            } catch (ToolException e) {
                last = e;
                if (attempt == 3 || !String.valueOf(e.getMessage()).contains("refused")) {
                    break;
                }
                pause(300L * attempt);
            }
        }
        throw last;
    }

    /** Attaches if needed; returns a note (or null) instead of failing so definitions can still be made. */
    static String ensureAttached(Services s, AemInstance instance, boolean failIfImpossible) {
        JdiEngine engine = engine(s, instance);
        if (engine.isAttached()) {
            return null;
        }
        try {
            return attach(s, instance, 5000);
        } catch (ToolException e) {
            if (failIfImpossible) {
                throw e;
            }
            return "Not attached to the " + instance.name() + " JVM: " + e.getMessage()
                    + " The definition is kept and armed on the next attach.";
        }
    }

    /** The local project index if it is cached or can be located; null otherwise. */
    static ProjectIndex projectIndex(Services s, ToolContext context) {
        ProjectIndex index = s.projects.cached();
        if (index != null) {
            return index;
        }
        try {
            return s.projects.get(context);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves file names and simple class names against the local project. Returns a note or null. */
    static String resolveLocation(Services s, ToolContext context, BreakpointSpec spec) {
        ProjectIndex index = projectIndex(s, context);
        if (spec.kind == BreakpointSpec.Kind.FILE_LINE) {
            if (index == null) {
                return "No local project index: " + spec.fileName
                        + " is matched against the source file names of loaded classes.";
            }
            for (ProjectModel.JavaClass c : index.classesForSourceFile(spec.fileName)) {
                if (!spec.candidateClasses.contains(c.fqn)) {
                    spec.candidateClasses.add(c.fqn);
                }
            }
            return spec.candidateClasses.isEmpty()
                    ? spec.fileName + " is not in the indexed project; loaded classes are matched by source file name."
                    : spec.fileName + " -> " + String.join(", ", spec.candidateClasses);
        }
        if (spec.className != null && !spec.hasPackage() && index != null) {
            ProjectModel.JavaClass c = index.classByName(spec.className);
            if (c != null) {
                spec.className = c.fqn;
                return "resolved to " + c.fqn + " (" + index.relative(c.file) + ")";
            }
        }
        return null;
    }

    /** Exception watch patterns: the project's packages plus Sling Models and HTL. */
    static List<String> defaultWatchPackages(Services s, ToolContext context) {
        Set<String> packages = new LinkedHashSet<>();
        ProjectIndex index = projectIndex(s, context);
        if (index != null) {
            packages.addAll(index.projectPackages());
        }
        packages.addAll(s.config.projectPackages());
        packages.addAll(DEFAULT_WATCH_PACKAGES);
        return patterns(new ArrayList<>(packages));
    }

    /** JDI class patterns for package names, class names or patterns (comma-separated values allowed). */
    static List<String> patterns(List<String> packages) {
        List<String> out = new ArrayList<>();
        for (String value : packages) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String pattern = ExceptionWatchSpec.toPattern(part);
                if (!pattern.isEmpty() && !out.contains(pattern)) {
                    out.add(pattern);
                }
            }
        }
        return out;
    }

    static List<String> strings(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
