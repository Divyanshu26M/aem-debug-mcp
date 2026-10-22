package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.logs.LogEntry;
import dev.aemdebug.logs.LogService;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** aem_logs: read/search error.log by level, logger, request, time or mark. */
public final class AemLogsTool extends AbstractTool {

    private final Services s;

    public AemLogsTool(Services services) {
        super("aem_logs", "AEM logs",
                "Reads and searches the local AEM error.log (directly from crx-quickstart/logs, or via the Sling log "
                        + "tailer). action=read (default) filters by level, logger, regex, request path and time; "
                        + "action=mark returns a marker id before you reproduce something, then read with since=<mark> "
                        + "to get only new lines. Entries are grouped by the Sling request thread "
                        + "(GET /content/... @time) and stack traces are folded.",
                Schema.object()
                        .enumStr("action", "read (default) or mark", "read", "mark")
                        .enumStr("level", "Minimum level (default WARN)", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")
                        .str("logger", "Only loggers containing this text, e.g. com.mysite or ModelAdapterFactory")
                        .str("grep", "Regex matched against the whole entry including stack trace")
                        .str("request", "Only entries logged by request threads whose URI contains this text")
                        .integer("sinceMinutes", "Only entries from the last N minutes", 1, 1440)
                        .str("since", "Marker id from action=mark: only entries written after it")
                        .integer("limit", "Maximum entries (default 100)", 1, 2000)
                        .integer("stackLines", "Stack trace lines per entry (default 8)", 0, 200)
                        .enumStr("instance", "author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        if ("mark".equals(args.choice("action", "read", "read", "mark"))) {
            LogService.Mark mark = s.logs.mark(context, instance);
            return ToolResult.of("Log marker " + mark.id + " set at " + Text.formatTime(mark.time)
                    + (mark.file != null ? " (" + mark.file + " offset " + mark.offset + ")" : " (via log tailer)")
                    + ". Reproduce the problem, then call aem_logs with since=" + mark.id + ".");
        }
        LogService.Query query = new LogService.Query();
        query.minSeverity = LogEntry.severity(args.choice("level", "WARN", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")
                .toUpperCase(Locale.ROOT));
        query.logger = args.str("logger");
        query.requestPath = args.str("request");
        query.limit = args.integer("limit", 100, 1, 2000);
        String grep = args.str("grep");
        if (grep != null) {
            try {
                query.grep = Pattern.compile(grep);
            } catch (PatternSyntaxException e) {
                query.grep = Pattern.compile(Pattern.quote(grep));
            }
        }
        Integer sinceMinutes = args.integer("sinceMinutes");
        if (sinceMinutes != null) {
            query.sinceMillis = System.currentTimeMillis() - sinceMinutes * 60_000L;
        }
        LogService.Result result;
        String markId = args.str("since");
        if (markId != null) {
            LogService.Mark mark = s.logs.mark(markId);
            if (mark == null) {
                throw new ToolException("Unknown log marker '" + markId + "' (markers live until the server restarts)");
            }
            LogService.Result raw = s.logs.since(context, instance, mark, 4 * 1024 * 1024);
            result = new LogService.Result(LogService.filter(raw.entries, query), raw.source, raw.truncated, raw.scanned);
        } else {
            result = s.logs.read(context, instance, query, sinceMinutes != null ? 16 * 1024 * 1024 : 4 * 1024 * 1024);
        }
        return ToolResult.of(render(result, args.integer("stackLines", 8, 0, 200), query));
    }

    private String render(LogService.Result result, int stackLines, LogService.Query query) {
        StringBuilder out = new StringBuilder();
        out.append("### error.log: ").append(result.entries.size()).append(" matching entries (scanned ")
                .append(result.scanned).append(") from ").append(result.source).append('\n');
        if (result.truncated) {
            out.append("(older content not scanned; narrow with since/sinceMinutes)\n");
        }
        if (result.entries.isEmpty()) {
            out.append("No entries at level >= threshold match the filters.\n");
            return out.toString();
        }
        for (Map.Entry<String, List<LogEntry>> group : LogService.groupByRequest(result.entries).entrySet()) {
            out.append("\n#### ").append(group.getKey()).append(" (").append(group.getValue().size()).append(")\n");
            out.append(SecretMasker.maskText(LogService.format(group.getValue(), stackLines)));
        }
        String fullText = SecretMasker.maskText(LogService.fullText(result.entries));
        if (out.length() > ToolOutput.INLINE_LIMIT || fullText.length() > out.length() * 2) {
            String reportId = s.artifacts.newReport();
            s.artifacts.write(reportId, "error-log.txt", fullText);
            String inline = out.length() > ToolOutput.INLINE_LIMIT ? out.substring(0, ToolOutput.INLINE_LIMIT)
                    + "\n... (truncated)" : out.toString();
            return inline + "\nFull entries: " + s.artifacts.uri(reportId, "error-log.txt") + " = "
                    + s.artifacts.resolve(reportId, "error-log.txt") + "\n";
        }
        return out.toString();
    }
}
