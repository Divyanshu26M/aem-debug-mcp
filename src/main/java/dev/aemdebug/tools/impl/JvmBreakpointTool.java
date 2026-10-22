package dev.aemdebug.tools.impl;

import dev.aemdebug.jdi.BreakpointSpec;
import dev.aemdebug.jdi.Condition;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.util.List;

/** jvm_breakpoint: non-suspending snapshot breakpoints and logpoints. */
public final class JvmBreakpointTool extends AbstractTool {

    private final Services s;

    public JvmBreakpointTool(Services services) {
        super("jvm_breakpoint", "JVM snapshot breakpoints",
                "Sets, lists or removes snapshot breakpoints and logpoints in the AEM JVM. When a thread reaches the "
                        + "line, the server records stack, locals and fields of `this` (AEM-aware: Resource path, type "
                        + "and properties, ValueMaps, collections; secrets masked) and resumes the thread at once - AEM "
                        + "is never paused. Locations: com.mysite.core.models.HeroModel:41, HeroModel.java:41 (resolved "
                        + "through the local project) or com.mysite.core.models.HeroModel#init. Options: condition "
                        + "(e.g. title == null && count > 2; operators == != < > contains startsWith endsWith matches "
                        + "&& || !), log (logpoint message such as \"title={title}\", no stack), request (only requests "
                        + "whose URI contains this text, ^regex, or 'requests' for any request), maxHits (default 50, "
                        + "then the breakpoint disables itself). Breakpoints are re-armed when a bundle is redeployed. "
                        + "Read results with jvm_snapshots, or per request with debug_page. Attaches to JDWP "
                        + "automatically when the port is free.",
                Schema.object()
                        .enumStr("action", "set (default when location is given), list, remove or clear",
                                "set", "list", "remove", "clear")
                        .str("location", "Class:line, File.java:line or Class#method")
                        .str("condition", "Only capture when this is true, e.g. title == null")
                        .str("log", "Logpoint message with {expressions}, e.g. \"title={title} i={i}\"")
                        .str("request", "Request filter: URI substring, ^regex, or 'requests' for any request")
                        .integer("maxHits", "Captures before the breakpoint disables itself (default 50)", 1, 1000)
                        .integer("frames", "Stack frames recorded (default 12)", 1, 100)
                        .integer("localsFrames", "Frames whose locals are captured (default 1)", 1, 10)
                        .integer("depth", "Object rendering depth (default 2)", 0, 4)
                        .str("id", "Breakpoint id for action=remove, e.g. bp2"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        JdiEngine engine = JvmTools.engine(s);
        String action = args.choice("action", args.has("location") ? "set" : "list",
                "set", "list", "remove", "clear");
        switch (action) {
            case "set":
                return set(context, args, engine);
            case "remove": {
                String id = args.requireStr("id").trim();
                if (!engine.removeBreakpoint(id)) {
                    throw new ToolException("No breakpoint " + id + ". " + list(engine));
                }
                return ToolResult.of("Removed " + id + ".\n" + list(engine));
            }
            case "clear": {
                int removed = 0;
                for (BreakpointSpec spec : engine.breakpointList()) {
                    if (engine.removeBreakpoint(spec.id)) {
                        removed++;
                    }
                }
                return ToolResult.of("Removed " + Text.plural(removed, "breakpoint") + ".");
            }
            default:
                return ToolResult.of(list(engine));
        }
    }

    private ToolResult set(ToolContext context, Args args, JdiEngine engine) {
        BreakpointSpec spec;
        try {
            spec = BreakpointSpec.parse(args.requireStr("location"));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        spec.condition = JvmTools.blankToNull(args.str("condition"));
        if (spec.condition != null) {
            try {
                Condition.parse(spec.condition);
            } catch (RuntimeException e) {
                throw new ToolException("Invalid condition: " + e.getMessage());
            }
        }
        spec.logMessage = JvmTools.blankToNull(args.str("log"));
        spec.requestFilter = JvmTools.blankToNull(args.str("request"));
        spec.maxHits = args.integer("maxHits", spec.maxHits, 1, 1000);
        spec.frames = args.integer("frames", spec.frames, 1, 100);
        spec.localsFrames = args.integer("localsFrames", spec.localsFrames, 1, 10);
        spec.depth = args.integer("depth", spec.depth, 0, 4);
        String resolution = JvmTools.resolveLocation(s, context, spec);
        String attach = JvmTools.ensureAttached(s, false);
        engine.addBreakpoint(spec);

        StringBuilder out = new StringBuilder();
        if (attach != null) {
            out.append(attach).append('\n');
        }
        out.append(spec.isLogpoint() ? "Logpoint " : "Breakpoint ").append(spec.id).append(" set: ")
                .append(spec.describe()).append('\n');
        if (resolution != null) {
            out.append("Location: ").append(resolution).append('\n');
        }
        if (engine.isAttached() && spec.state().startsWith("pending")) {
            out.append("The class is not loaded yet; the breakpoint arms itself when AEM loads it (first use). If it "
                    + "never arms, check the class name and that its bundle is active (aem_osgi_status).\n");
        }
        out.append("Next: reproduce (debug_page path=... or let the user open the page), then jvm_snapshots source=")
                .append(spec.id).append(".\n");
        return ToolResult.of(out.toString());
    }

    static String list(JdiEngine engine) {
        StringBuilder sb = new StringBuilder();
        sb.append(engine.isAttached() ? "Attached to " + engine.endpoint() + "."
                : "Not attached; breakpoints are armed on attach.").append('\n');
        List<BreakpointSpec> specs = engine.breakpointList();
        if (specs.isEmpty()) {
            sb.append("No breakpoints.\n");
        }
        for (BreakpointSpec spec : specs) {
            sb.append("- ").append(spec.temporary ? "[temporary] " : "").append(spec.describe()).append('\n');
        }
        return sb.toString();
    }
}
