package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.BreakpointSpec;
import dev.aemdebug.jdi.ExceptionWatchSpec;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdiSupport;
import dev.aemdebug.jdi.JdwpLocator;
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

    /** Why the JVM of an instance cannot be attached over JDWP now (with the cause when known), or null. */
    static String endpointProblem(Services s, AemInstance instance) {
        return s.jdwp.endpoint(instance, false).problem();
    }

    /** "author 127.0.0.1:8767 (detected)" or "publish (started without JDWP)". */
    static String endpoint(Services s, AemInstance instance) {
        return instance.name() + " " + s.jdwp.endpoint(instance, false).label();
    }

    /**
     * Attaches to the port found by {@link dev.aemdebug.jdi.JdwpLocator}. A refused connection is retried briefly
     * (the agent re-listens a moment after a detach) with the port detected again, and finally explained with what
     * the JVM reports (another debugger connected, no JDWP agent, ...).
     */
    static String attach(Services s, AemInstance instance, int timeoutMs) {
        JdiEngine engine = engine(s, instance);
        JdwpLocator.Endpoint endpoint = s.jdwp.endpoint(instance, false);
        ToolException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String problem = endpoint.problem();
            if (problem != null) {
                throw new ToolException(problem);
            }
            try {
                String result = engine.attach(endpoint.host(), endpoint.port(), timeoutMs);
                return endpoint.source() == JdwpLocator.Source.DETECTED
                        ? result + " The port was " + endpoint.origin() + "." : result;
            } catch (ToolException e) {
                last = e;
                s.jdwp.invalidate(instance.name());
                if (attempt == 3 || !String.valueOf(e.getMessage()).contains("refused")) {
                    break;
                }
                pause(300L * attempt);
                endpoint = s.jdwp.endpoint(instance, true);
            }
        }
        JdwpLocator.Endpoint fresh = s.jdwp.endpoint(instance, true);
        if (fresh.problem() != null) {
            throw new ToolException(fresh.problem());
        }
        throw new ToolException(String.valueOf(last.getMessage()).contains("refused") ? fresh.refused()
                : last.getMessage());
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
