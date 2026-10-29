package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.ExceptionWatchSpec;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

import java.util.List;

/** jvm_exception_watch: capture exceptions at the throw site, caught or not. */
public final class JvmExceptionWatchTool extends AbstractTool {

    private final Services s;

    public JvmExceptionWatchTool(Services services) {
        super("jvm_exception_watch", "JVM exception watch",
                "Captures exceptions inside the AEM JVM at the moment they are thrown - including exceptions that are "
                        + "caught and swallowed (Sling Model injection failures, HTL use-class errors, NPEs hidden by "
                        + "try/catch) - with stack, locals and the Sling request that threw them; the thread resumes at "
                        + "once. Scope: packages (throw-site packages or classes; default = the local project's "
                        + "packages + org.apache.sling.models + org.apache.sling.scripting.sightly), exception (class "
                        + "incl. subclasses, default java.lang.Throwable), caught/uncaught, request (URI text). Results: "
                        + "jvm_snapshots kind=exception, or per request in debug_page (which adds a temporary watch by "
                        + "itself). Attaches to JDWP automatically when the port is free.",
                Schema.object()
                        .enumStr("action", "add (default), list, remove or clear", "add", "list", "remove", "clear")
                        .strArray("packages", "Throw-site packages or classes, e.g. com.mysite or "
                                + "com.mysite.core.models.HeroModel (default: project packages + Sling Models + HTL)")
                        .str("exception", "Exception class to capture, incl. subclasses (default java.lang.Throwable)")
                        .bool("caught", "Capture exceptions that are caught (default true)")
                        .bool("uncaught", "Capture uncaught exceptions (default true)")
                        .str("request", "Request filter: URI substring, ^regex, or 'requests' for any request")
                        .integer("maxHits", "Captures before the watch disables itself (default 100)", 1, 1000)
                        .str("id", "Watch id for action=remove, e.g. ex1")
                        .enumStr("instance", "JVM of author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = JvmTools.instance(s, args);
        JdiEngine engine = JvmTools.engine(s, instance);
        String action = args.choice("action", "add", "add", "list", "remove", "clear");
        switch (action) {
            case "list":
                return ToolResult.of(list(instance, engine));
            case "remove": {
                String id = args.requireStr("id").trim();
                if (!engine.removeExceptionWatch(id)) {
                    throw new ToolException("No exception watch " + id + " in the " + instance.name() + " JVM. "
                            + list(instance, engine));
                }
                return ToolResult.of("Removed " + id + ".\n" + list(instance, engine));
            }
            case "clear": {
                int removed = 0;
                for (ExceptionWatchSpec spec : engine.watchList()) {
                    if (engine.removeExceptionWatch(spec.id)) {
                        removed++;
                    }
                }
                return ToolResult.of("Removed " + removed + (removed == 1 ? " exception watch" : " exception watches")
                        + " from the " + instance.name() + " JVM.");
            }
            default:
                return add(context, args, instance, engine);
        }
    }

    private ToolResult add(ToolContext context, Args args, AemInstance instance, JdiEngine engine) {
        ExceptionWatchSpec spec = new ExceptionWatchSpec();
        List<String> packages = JvmTools.strings(args.strList("packages"));
        boolean defaults = packages.isEmpty();
        spec.packages.addAll(defaults ? JvmTools.defaultWatchPackages(s, context) : JvmTools.patterns(packages));
        String exception = JvmTools.blankToNull(args.str("exception"));
        if (exception != null) {
            spec.exceptionClass = exception;
        }
        spec.caught = args.bool("caught", true);
        spec.uncaught = args.bool("uncaught", true);
        if (!spec.caught && !spec.uncaught) {
            throw new ToolException("caught and uncaught cannot both be false");
        }
        spec.requestFilter = JvmTools.blankToNull(args.str("request"));
        spec.maxHits = args.integer("maxHits", spec.maxHits, 1, 1000);
        String attach = JvmTools.ensureAttached(s, instance, false);
        engine.addExceptionWatch(spec);

        StringBuilder out = new StringBuilder();
        if (attach != null) {
            out.append(attach).append('\n');
        }
        out.append("Exception watch ").append(spec.id).append(" added to the ").append(instance.name())
                .append(" JVM: ").append(spec.describe()).append('\n');
        if (defaults) {
            out.append("Default scope = local project packages + Sling Models + HTL; pass packages to change it.\n");
        }
        out.append("Next: reproduce, then jvm_snapshots source=").append(spec.id)
                .append(instance.isAuthor() ? "" : " instance=" + instance.name()).append(" (or kind=exception).\n");
        return ToolResult.of(out.toString());
    }

    static String list(AemInstance instance, JdiEngine engine) {
        StringBuilder sb = new StringBuilder();
        sb.append(engine.isAttached() ? "Attached to the " + instance.name() + " JVM at " + engine.endpoint() + "."
                : "Not attached to the " + instance.name() + " JVM; watches are armed on attach.").append('\n');
        List<ExceptionWatchSpec> specs = engine.watchList();
        if (specs.isEmpty()) {
            sb.append("No exception watches.\n");
        }
        for (ExceptionWatchSpec spec : specs) {
            sb.append("- ").append(spec.temporary ? "[temporary] " : "").append(spec.describe()).append('\n');
        }
        return sb.toString();
    }
}
