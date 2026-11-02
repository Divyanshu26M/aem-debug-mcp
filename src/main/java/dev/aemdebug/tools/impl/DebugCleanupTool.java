package dev.aemdebug.tools.impl;

import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;

import java.util.ArrayList;
import java.util.List;

/** debug_cleanup: end the debugging session and restore everything. */
public final class DebugCleanupTool extends AbstractTool {

    private final Services s;
    private final DebugCaptureTool capture;

    public DebugCleanupTool(Services services, DebugCaptureTool capture) {
        super("debug_cleanup", "Clean up debugging",
                "Ends the debugging session: stops a running debug_capture, removes all breakpoints and exception "
                        + "watches, detaches from JDWP (so IntelliJ can attach again), reverts every debug toggle made "
                        + "by aem_debug_toggle and closes the headless browser. Snapshots stay readable until the "
                        + "server stops unless clearSnapshots=true. Safe to call at any time.",
                Schema.object()
                        .bool("clearSnapshots", "Also delete the stored JVM snapshots (default false)"));
        this.s = services;
        this.capture = capture;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        List<String> lines = new ArrayList<>();
        String stopped = capture.discard();
        if (stopped != null) {
            lines.add(stopped);
        }
        lines.addAll(s.cleanup.runAll(context, false));
        if (args.bool("clearSnapshots", false)) {
            int deleted = 0;
            for (JdiEngine engine : s.jdiEngines()) {
                deleted += engine.snapshots().clear();
            }
            lines.add("Deleted " + deleted + " stored snapshot(s).");
        }
        StringBuilder out = new StringBuilder("## Debug cleanup\n");
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
        return ToolResult.of(out.toString());
    }
}
