package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.server.Services;
import dev.aemdebug.toggles.RevertJournal;
import dev.aemdebug.toggles.ToggleService;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.util.List;

/** aem_debug_toggle: user-confirmed, auto-reverted debug OSGi changes. */
public final class AemDebugToggleTool extends AbstractTool {

    private final Services s;

    public AemDebugToggleTool(Services services) {
        super("aem_debug_toggle", "Debug toggle (confirmed, auto-reverted)",
                "The only tool that changes AEM, limited to debug settings: wcm-debug-filter (component debug "
                        + "comments), recent-requests (bigger Recent Requests buffer, optional path pattern), rpt-log "
                        + "(Request Progress Tracker into error.log), log-level (DEBUG/TRACE logging for given loggers). "
                        + "action=plan shows the exact PID and old -> new values without changing anything. "
                        + "action=enable asks the user to confirm through the client; if the client cannot ask, ask "
                        + "the user yourself and only then call again with confirm=true. Every change is journaled and "
                        + "reverted by debug_cleanup, after inactivity, on shutdown, or at the next start. "
                        + "action=revert undoes one toggle (or all); action=list shows active toggles.",
                Schema.object()
                        .enumStr("action", "plan, enable (default), revert or list", "plan", "enable", "revert", "list")
                        .enumStr("toggle", "Which debug setting", "wcm-debug-filter", "recent-requests", "rpt-log",
                                "log-level")
                        .strArray("loggers", "log-level: logger/package names, e.g. [\"com.mysite.core\"]")
                        .enumStr("level", "log-level: level (default debug)", "trace", "debug", "info")
                        .integer("size", "recent-requests: buffer size (default 200)", 20, 2000)
                        .str("pattern", "recent-requests: only record paths matching this regex, e.g. ^/content/.*")
                        .str("id", "revert: journal id such as t3 (default: all active toggles)")
                        .bool("confirm", "Only after the user explicitly approved this exact change in the chat")
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String action = args.choice("action", "enable", "plan", "enable", "revert", "list");
        AemInstance instance = s.instances.resolve(args.str("instance"));
        switch (action) {
            case "list":
                return ToolResult.of(list());
            case "revert":
                return ToolResult.of(revert(context, args.str("id"), args.str("toggle")));
            default:
                break;
        }
        String toggle = args.str("toggle");
        if (toggle == null) {
            throw new ToolException("toggle is required: " + ToggleService.TOGGLES);
        }
        ToggleService.Plan plan = s.toggles.plan(context, instance, toggle, args.strList("loggers"),
                args.str("level"), args.integer("size", 200, 20, 2000), args.str("pattern"));
        String description = plan.describe();
        if ("plan".equals(action)) {
            return ToolResult.of("Planned change (nothing applied):\n" + description);
        }
        if (plan.noOp()) {
            return ToolResult.of("Already in the requested state - nothing to change:\n" + description);
        }
        String reason;
        ToolContext.Confirmation confirmation = context.confirm("aem-debug-mcp wants to change an OSGi configuration "
                + "on your local AEM for debugging. It will be reverted automatically (debug_cleanup, after "
                + s.config.idleDetachMinutes() + " min idle, or when the server stops).\n\n" + description);
        switch (confirmation) {
            case ACCEPTED:
                reason = "confirmed by the user in the client";
                break;
            case DECLINED:
            case CANCELLED:
                return ToolResult.of("Not applied: the user declined the change.\n" + description);
            default:
                if (!args.bool("confirm", false)) {
                    return ToolResult.of("Confirmation required. This client cannot show a confirmation dialog, so ask "
                            + "the user to approve this exact change, then call aem_debug_toggle again with "
                            + "confirm=true:\n\n" + description);
                }
                reason = "confirmed by the user in chat (confirm=true)";
                break;
        }
        RevertJournal.Entry entry = s.toggles.apply(context, plan);
        return ToolResult.of("Applied " + entry.toggle + " as " + entry.id + " (" + reason + ") on PID " + entry.pid
                + ".\n" + description + "Revert with aem_debug_toggle action=revert id=" + entry.id
                + " or debug_cleanup.");
    }

    private String list() {
        List<RevertJournal.Entry> active = s.toggles.journal().active();
        if (active.isEmpty()) {
            return "No debug toggles are active. Available: " + ToggleService.TOGGLES;
        }
        StringBuilder sb = new StringBuilder("Active debug toggles:\n");
        for (RevertJournal.Entry entry : active) {
            sb.append("- ").append(entry.id).append(' ').append(entry.toggle).append(" on ").append(entry.instance)
                    .append(" PID ").append(entry.pid).append(" since ").append(Text.formatTime(entry.appliedAt))
                    .append(entry.created ? " (created)" : " (modified)");
            if (s.toggles.fromPreviousRun().contains(entry.id)) {
                sb.append(" - left over from a previous run, pending revert");
            }
            if (entry.revertError != null) {
                sb.append(" - last revert error: ").append(entry.revertError);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String revert(ToolContext context, String id, String toggle) {
        List<RevertJournal.Entry> active = s.toggles.journal().active();
        if (active.isEmpty()) {
            return "Nothing to revert.";
        }
        StringBuilder sb = new StringBuilder();
        for (RevertJournal.Entry entry : active) {
            if ((id != null && !id.equals(entry.id)) || (id == null && toggle != null && !toggle.equals(entry.toggle))) {
                continue;
            }
            try {
                sb.append(s.toggles.revert(context, entry)).append('\n');
            } catch (RuntimeException e) {
                sb.append(entry.id).append(" NOT reverted: ").append(e.getMessage()).append('\n');
            }
        }
        return sb.length() == 0 ? "No active toggle matches." : sb.toString();
    }
}
