package dev.aemdebug.correlate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestKeyTest {

    @Test
    void parsesSlingThreadNames() {
        RequestKey key = RequestKey.parse("127.0.0.1 [1729590000123] GET /content/we-retail/us/en.html HTTP/1.1");
        assertThat(key).isNotNull();
        assertThat(key.remoteAddr()).isEqualTo("127.0.0.1");
        assertThat(key.startMillis()).isEqualTo(1729590000123L);
        assertThat(key.method()).isEqualTo("GET");
        assertThat(key.uri()).isEqualTo("/content/we-retail/us/en.html");
        assertThat(key.protocol()).isEqualTo("HTTP/1.1");
        assertThat(key.threadName()).isEqualTo("127.0.0.1 [1729590000123] GET /content/we-retail/us/en.html HTTP/1.1");
        assertThat(RequestKey.parse(key.threadName())).isEqualTo(key);
    }

    @Test
    void parsesIpv6AndUrisWithSpaces() {
        RequestKey key = RequestKey.parse("0:0:0:0:0:0:0:1 [1729590000123] POST /content/dam/my file.jpg HTTP/2");
        assertThat(key).isNotNull();
        assertThat(key.uri()).isEqualTo("/content/dam/my file.jpg");
        assertThat(key.protocol()).isEqualTo("HTTP/2");
    }

    @Test
    void ignoresOtherThreads() {
        assertThat(RequestKey.parse("main")).isNull();
        assertThat(RequestKey.parse("pool-5-thread-1")).isNull();
        assertThat(RequestKey.parse("sling-default-3-Registered Service.123")).isNull();
        assertThat(RequestKey.parse(null)).isNull();
    }

    @Test
    void matchesBrowserRequestsWithinTolerance() {
        RequestKey key = RequestKey.parse("127.0.0.1 [1000000000500] GET /content/a.html HTTP/1.1");
        assertThat(key.matches("GET", "/content/a.html", 1000000000400L, 2000)).isTrue();
        assertThat(key.matches("get", "/content/a.html", 1000000000400L, 2000)).isTrue();
        assertThat(key.matches("GET", "/content/b.html", 1000000000400L, 2000)).isFalse();
        assertThat(key.matches("GET", "/content/a.html", 1000000009000L, 2000)).isFalse();
    }
}
