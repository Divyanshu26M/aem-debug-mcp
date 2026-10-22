package dev.aemdebug.tools.impl;

import dev.aemdebug.correlate.RequestCorrelator;
import dev.aemdebug.correlate.RequestCorrelator.Event;
import dev.aemdebug.correlate.RequestCorrelator.ServerRequest;
import dev.aemdebug.correlate.RequestKey;
import dev.aemdebug.jdi.Snapshot;
import dev.aemdebug.logs.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugReportTest {

    private static final long START = 1_761_130_000_000L;
    private static final String THREAD = "127.0.0.1 [" + START + "] GET /content/a.html HTTP/1.1";

    private static LogEntry log(String level, String message) {
        return new LogEntry(START + 5, "22.10.2026 17:19:00.945", level, THREAD, "com.mysite.Foo", message, 0);
    }

    private static Snapshot snapshot(String id, Snapshot.Kind kind) {
        Snapshot snapshot = new Snapshot();
        snapshot.id = id;
        snapshot.kind = kind;
        snapshot.sourceId = kind == Snapshot.Kind.EXCEPTION ? "ex1" : "bp1";
        snapshot.time = START + 3;
        snapshot.threadName = THREAD;
        snapshot.requestKey = RequestKey.parse(THREAD);
        snapshot.location = "com.mysite.core.models.HeroModel.init(HeroModel.java:41)";
        snapshot.className = "com.mysite.core.models.HeroModel";
        snapshot.methodName = "init";
        snapshot.sourceName = "HeroModel.java";
        snapshot.line = 41;
        snapshot.message = "hit";
        if (kind == Snapshot.Kind.EXCEPTION) {
            snapshot.exceptionClass = "java.lang.IllegalStateException";
            snapshot.exceptionMessage = "Could not inject field 'title'";
            snapshot.catchLocation = "com.mysite.core.models.HeroModel.render(HeroModel.java:20)";
        }
        return snapshot;
    }

    @Test
    void countsAndOrdersEventsBySeverity() {
        ServerRequest request = DebugRun.server(RequestKey.parse(THREAD));
        List<Event> events = List.of(
                new Event("log", request, START + 9, "info", log("INFO", "rendered page")),
                new Event("log", request, START + 8, "error", log("ERROR", "something failed")),
                new Event("snapshot", request, START + 7, "hit", snapshot("s2", Snapshot.Kind.BREAKPOINT)),
                new Event("exception", request, START + 6, "boom", snapshot("s1", Snapshot.Kind.EXCEPTION)));

        assertThat(DebugReport.counts(events)).contains("1 exception").contains("1 snapshot")
                .contains("(1 WARN/ERROR)");
        assertThat(DebugReport.counts(List.of())).isEqualTo("no server events");

        StringBuilder sb = new StringBuilder();
        DebugReport.appendEvents(sb, events, 10, false, "");
        String text = sb.toString();
        assertThat(text.indexOf("s1")).isLessThan(text.indexOf("s2"));
        assertThat(text.indexOf("s2")).isLessThan(text.indexOf("something failed"));
        assertThat(text.indexOf("something failed")).isLessThan(text.indexOf("rendered page"));

        StringBuilder limited = new StringBuilder();
        DebugReport.appendEvents(limited, events, 2, false, "  ");
        assertThat(limited.toString()).contains("  - ... 2 more");
    }

    @Test
    void labelsAndOwnRequests() {
        ServerRequest timed = new ServerRequest("GET", "/content/a.html", START);
        RequestCorrelator.Result result = RequestCorrelator.group(List.of(
                new Event("log", timed, START + 1, "x", log("WARN", "x")),
                new Event("log", new ServerRequest("GET", "/content/a/jcr:content.infinity.json", START + 2),
                        START + 3, "y", log("WARN", "y"))));
        assertThat(result.serverOnly()).hasSize(2);
        assertThat(DebugReport.label(result.serverOnly().get(0))).startsWith("GET /content/a.html @ ");
        assertThat(DebugReport.isOwnRequest(result.serverOnly().get(0))).isFalse();
        assertThat(DebugReport.isOwnRequest(result.serverOnly().get(1))).isTrue();
    }

    @Test
    void indentsMultiLineBullets() {
        assertThat(DebugReport.bullet("first\nsecond", "  ")).isEqualTo("  - first\n    second");
        assertThat(DebugReport.shortName("com.mysite.Foo")).isEqualTo("Foo");
        assertThat(DebugReport.outer("com.mysite.Foo$Bar")).isEqualTo("com.mysite.Foo");
    }
}
