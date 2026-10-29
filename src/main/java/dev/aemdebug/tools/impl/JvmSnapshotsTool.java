package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.Snapshot;
import dev.aemdebug.jdi.SnapshotStore;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** jvm_snapshots: list, read, expand and clear captured snapshots. */
public final class JvmSnapshotsTool extends AbstractTool {

    private final Services s;

    public JvmSnapshotsTool(Services services) {
        super("jvm_snapshots", "JVM snapshots",
                "Reads what jvm_breakpoint, jvm_exception_watch, debug_page and debug_capture captured inside the AEM "
                        + "JVM: thread, Sling request, stack, locals and fields of `this` at the moment of the hit (AEM "
                        + "was not paused). action=list (default, newest first) filters by source (bp1, ex2), request "
                        + "(URI text), kind and age; action=get shows one snapshot in full; action=expand renders an "
                        + "object referenced in a snapshot (its numeric id) deeper while still attached; action=clear "
                        + "empties the store. The latest 1000 snapshots are kept in memory.",
                Schema.object()
                        .enumStr("action", "list (default), get, expand or clear", "list", "get", "expand", "clear")
                        .str("id", "Snapshot id for action=get, e.g. s12")
                        .str("source", "Only snapshots of this breakpoint or exception watch, e.g. bp1 or ex2")
                        .str("request", "Only snapshots whose Sling request URI contains this text")
                        .enumStr("kind", "Filter by kind (default all)", "all", "exception", "breakpoint", "logpoint")
                        .integer("sinceSeconds", "Only snapshots from the last N seconds", 1, 86_400)
                        .integer("limit", "Maximum snapshots listed (default 20)", 1, 200)
                        .bool("details", "action=list: print full snapshots instead of one line each (default false)")
                        .integer("stackLines", "Stack lines for action=get (default 60)", 1, 400)
                        .str("objectId", "Object id for action=expand, as shown in a snapshot")
                        .integer("depth", "Levels for action=expand (default 2)", 1, 4)
                        .bool("save", "Also save the listed snapshots as a JSON report artifact (default false)")
                        .enumStr("instance", "JVM of author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = JvmTools.instance(s, args);
        JdiEngine engine = JvmTools.engine(s, instance);
        SnapshotStore store = engine.snapshots();
        String action = args.choice("action", "list", "list", "get", "expand", "clear");
        switch (action) {
            case "get": {
                String id = args.requireStr("id").trim();
                Snapshot snapshot = store.get(id);
                if (snapshot == null) {
                    throw new ToolException("No snapshot " + id + " in the " + instance.name() + " JVM ("
                            + store.size() + " stored; jvm_snapshots action=list shows the ids"
                            + (instance.isAuthor() ? "; snapshots of publish need instance=publish" : "") + ").");
                }
                return ToolResult.of(snapshot.format(args.integer("stackLines", 60, 1, 400)));
            }
            case "expand": {
                String digits = args.requireStr("objectId").replaceAll("[^0-9]", "");
                if (digits.isEmpty() || digits.length() > 18) {
                    throw new ToolException("objectId must be the numeric object id shown in a snapshot");
                }
                return ToolResult.of(engine.expand(Long.parseLong(digits), args.integer("depth", 2, 1, 4)));
            }
            case "clear":
                return ToolResult.of("Cleared " + Text.plural(store.clear(), "snapshot") + ".");
            default:
                return list(args, store);
        }
    }

    private ToolResult list(Args args, SnapshotStore store) {
        String source = JvmTools.blankToNull(args.str("source"));
        String request = JvmTools.blankToNull(args.str("request"));
        String kind = args.choice("kind", "all", "all", "exception", "breakpoint", "logpoint");
        Integer sinceSeconds = args.integer("sinceSeconds");
        long since = sinceSeconds == null ? -1 : System.currentTimeMillis() - sinceSeconds * 1000L;
        Predicate<Snapshot> filter = snapshot -> (source == null || source.equalsIgnoreCase(snapshot.sourceId))
                && (request == null || matchesRequest(snapshot, request))
                && matchesKind(snapshot, kind)
                && (since < 0 || snapshot.time >= since);
        List<Snapshot> matching = new ArrayList<>(store.list(filter));
        Collections.reverse(matching);
        int limit = args.integer("limit", 20, 1, 200);
        List<Snapshot> shown = matching.subList(0, Math.min(limit, matching.size()));
        boolean details = args.bool("details", false);

        StringBuilder out = new StringBuilder();
        out.append(matching.size()).append(" of ").append(store.size()).append(" stored snapshots match");
        if (shown.size() < matching.size()) {
            out.append(" (newest ").append(shown.size()).append(" shown)");
        }
        out.append(".\n");
        for (Snapshot snapshot : shown) {
            if (details) {
                out.append('\n').append(snapshot.format(25)).append('\n');
            } else {
                out.append("- ").append(snapshot.summary()).append('\n');
            }
        }
        if (matching.isEmpty()) {
            out.append("Nothing captured. Check that the breakpoint/watch is armed (jvm_breakpoint action=list, "
                    + "jvm_exception_watch action=list), that the JVM is attached (jvm_connection), and that the code "
                    + "path ran (reproduce with debug_page).\n");
        } else if (!details) {
            out.append("action=get id=<id> shows stack, locals and fields.\n");
        }
        if (args.bool("save", false) && !matching.isEmpty()) {
            List<Object> maps = new ArrayList<>();
            for (Snapshot snapshot : matching) {
                maps.add(snapshot.toMap());
            }
            String reportId = s.artifacts.newReport();
            s.artifacts.write(reportId, "snapshots.json", SecretMasker.maskText(Json.pretty(maps)));
            out.append("Saved ").append(s.artifacts.uri(reportId, "snapshots.json")).append('\n');
        }
        return ToolResult.of(out.toString());
    }

    static boolean matchesRequest(Snapshot snapshot, String text) {
        if (snapshot.requestKey != null && snapshot.requestKey.uri().contains(text)) {
            return true;
        }
        return snapshot.threadName != null && snapshot.threadName.contains(text);
    }

    static boolean matchesKind(Snapshot snapshot, String kind) {
        switch (kind) {
            case "exception":
                return snapshot.kind == Snapshot.Kind.EXCEPTION;
            case "breakpoint":
                return snapshot.kind == Snapshot.Kind.BREAKPOINT;
            case "logpoint":
                return snapshot.kind == Snapshot.Kind.LOGPOINT;
            default:
                return true;
        }
    }
}
