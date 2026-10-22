package dev.aemdebug.logs;

import dev.aemdebug.correlate.RequestKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorLogParserTest {

    static final String LOG = String.join("\n",
            "22.10.2026 17:19:00.945 *INFO* [OsgiInstallerImpl] org.apache.sling.audit.osgi.installer Started bundle",
            "22.10.2026 17:19:01.123 *WARN* [127.0.0.1 [1729590000000] GET /content/site/en.html HTTP/1.1] "
                    + "org.apache.sling.models.impl.ModelAdapterFactory Could not adapt to model",
            "org.apache.sling.models.factory.MissingElementsException: Could not inject all required fields into class com.mysite.HeroModel",
            "\tat org.apache.sling.models.impl.ModelAdapterFactory.createObject(ModelAdapterFactory.java:719)",
            "\tat org.apache.sling.models.impl.ModelAdapterFactory.internalCreateModel(ModelAdapterFactory.java:395)",
            "Caused by: java.lang.IllegalArgumentException: No property 'title'",
            "\t... 42 common frames omitted",
            "22.10.2026 17:19:01.200 *ERROR* [127.0.0.1 [1729590000000] GET /content/site/en.html HTTP/1.1] "
                    + "org.apache.sling.servlets.resolver.internal.SlingServletResolver Original error class java.lang.NullPointerException",
            "22.10.2026 17:19:02.000 *DEBUG* [pool-5-thread-1] com.mysite.Job tick",
            "");

    @Test
    void parsesEntriesWithNestedBracketThreadsAndStackTraces() {
        List<LogEntry> entries = ErrorLogParser.parse(LOG);
        assertThat(entries).hasSize(4);
        LogEntry warn = entries.get(1);
        assertThat(warn.level()).isEqualTo("WARN");
        assertThat(warn.thread()).isEqualTo("127.0.0.1 [1729590000000] GET /content/site/en.html HTTP/1.1");
        assertThat(warn.logger()).isEqualTo("org.apache.sling.models.impl.ModelAdapterFactory");
        assertThat(warn.message()).isEqualTo("Could not adapt to model");
        assertThat(warn.continuation()).hasSize(5);
        assertThat(warn.hasStackTrace()).isTrue();
        assertThat(warn.rootCause()).isEqualTo("java.lang.IllegalArgumentException: No property 'title'");
        RequestKey key = warn.requestKey();
        assertThat(key).isNotNull();
        assertThat(key.uri()).isEqualTo("/content/site/en.html");
        assertThat(key.startMillis()).isEqualTo(1729590000000L);
        assertThat(entries.get(0).requestKey()).isNull();
        assertThat(entries.get(0).timestamp()).isGreaterThan(0);
        assertThat(warn.format(2)).contains("[GET /content/site/en.html]").contains("... 3 more lines")
                .contains("root cause: java.lang.IllegalArgumentException");
    }

    @Test
    void offsetsPointToLineStarts() {
        List<LogEntry> entries = ErrorLogParser.parse(LOG, 100);
        assertThat(entries.get(0).offset()).isEqualTo(100);
        int secondLineStart = LOG.indexOf("22.10.2026 17:19:01.123");
        assertThat(entries.get(1).offset()).isEqualTo(100 + secondLineStart);
    }

    @Test
    void filtersAndGroupsByRequest() {
        List<LogEntry> entries = ErrorLogParser.parse(LOG);
        LogService.Query query = new LogService.Query();
        query.minSeverity = LogEntry.severity("WARN");
        assertThat(LogService.filter(entries, query)).hasSize(2);
        query.requestPath = "/content/site";
        query.grep = Pattern.compile("MissingElements");
        assertThat(LogService.filter(entries, query)).hasSize(1);
        assertThat(LogService.groupByRequest(entries).keySet()).hasSize(2);
    }

    @Test
    void readsOnlyNewBytesAfterMarkAndHandlesRotation(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("error.log");
        Files.writeString(log, LOG, StandardCharsets.UTF_8);
        long mark = Files.size(log);
        Files.writeString(log, "22.10.2026 17:20:00.000 *ERROR* [main] com.mysite.X boom\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        LogReader.Chunk chunk = LogReader.readFrom(log, mark, 1_000_000);
        List<LogEntry> entries = ErrorLogParser.parse(chunk.text, chunk.from);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo("boom");
        assertThat(chunk.rotated).isFalse();

        Files.writeString(log, "22.10.2026 00:00:01.000 *INFO* [main] a.B fresh file\n", StandardCharsets.UTF_8);
        LogReader.Chunk rotated = LogReader.readFrom(log, mark, 1_000_000);
        assertThat(rotated.rotated).isTrue();
        assertThat(ErrorLogParser.parse(rotated.text)).hasSize(1);

        LogReader.Chunk tail = LogReader.readFrom(log, 0, 20);
        assertThat(tail.truncated).isTrue();
    }
}
