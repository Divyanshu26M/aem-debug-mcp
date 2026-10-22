package dev.aemdebug.correlate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aemdebug.correlate.RequestCorrelator.BrowserRequest;
import dev.aemdebug.correlate.RequestCorrelator.Event;
import dev.aemdebug.correlate.RequestCorrelator.Group;
import dev.aemdebug.correlate.RequestCorrelator.Result;
import dev.aemdebug.correlate.RequestCorrelator.ServerRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestCorrelatorTest {

    private static Event ev(String kind, ServerRequest r, long t, String summary) {
        return new Event(kind, r, t, summary, null);
    }

    @Test
    void joinsBrowserRequestsWithServerEventsByMethodPathAndTime() {
        BrowserRequest page = new BrowserRequest("r1", "get", "http://localhost:4502/content/site/en.html?wcmmode=disabled", 1000);
        BrowserRequest js = new BrowserRequest("r2", "GET", "http://localhost:4502/etc.clientlibs/site/clientlib.js", 1200);
        BrowserRequest img = new BrowserRequest("r3", "GET", "http://localhost:4502/content/dam/a.png", 1300);
        ServerRequest pageServer = new ServerRequest("GET", "/content/site/en.html", 1004);
        ServerRequest jsServer = new ServerRequest("GET", "/etc.clientlibs/site/clientlib.js", 1203);

        Result r = RequestCorrelator.correlate(List.of(page, js, img), List.of(
                ev("log", pageServer, 1100, "ERROR something"),
                ev("exception", pageServer, 1050, "NPE"),
                ev("log", jsServer, 1210, "WARN minify"),
                ev("log", null, 1150, "background job")), RequestCorrelator.DEFAULT_TOLERANCE_MILLIS);

        assertThat(r.requests()).hasSize(2);
        Group first = r.requests().get(0);
        assertThat(first.browser().id()).isEqualTo("r1");
        assertThat(first.server()).isEqualTo(pageServer);
        assertThat(first.events()).extracting(Event::summary).containsExactly("NPE", "ERROR something");
        assertThat(first.events("exception")).hasSize(1);
        assertThat(r.requests().get(1).browser().id()).isEqualTo("r2");
        assertThat(r.serverOnly()).isEmpty();
        assertThat(r.unattributed()).extracting(Event::summary).containsExactly("background job");
        assertThat(r.browserWithoutEvents()).extracting(BrowserRequest::id).containsExactly("r3");
        assertThat(r.eventCount()).isEqualTo(4);
    }

    @Test
    void repeatedRequestsPairWithTheClosestStartTime() {
        BrowserRequest a1 = new BrowserRequest("a1", "GET", "/content/a.html", 1000);
        BrowserRequest a2 = new BrowserRequest("a2", "GET", "/content/a.html", 4000);
        ServerRequest s1 = new ServerRequest("GET", "/content/a.html", 1002);
        ServerRequest s2 = new ServerRequest("GET", "/content/a.html", 4001);

        Result r = RequestCorrelator.correlate(List.of(a1, a2),
                List.of(ev("log", s2, 4005, "second"), ev("log", s1, 1005, "first")), 5000);

        assertThat(r.requests()).hasSize(2);
        assertThat(r.requests().get(0).browser().id()).isEqualTo("a1");
        assertThat(r.requests().get(0).events()).extracting(Event::summary).containsExactly("first");
        assertThat(r.requests().get(1).browser().id()).isEqualTo("a2");
        assertThat(r.requests().get(1).events()).extracting(Event::summary).containsExactly("second");
    }

    @Test
    void requestsOutsideTheToleranceOrWithOtherMethodsAreServerOnly() {
        BrowserRequest a = new BrowserRequest("a", "GET", "/content/a.html", 1000);
        ServerRequest late = new ServerRequest("GET", "/content/a.html", 20_000);
        ServerRequest post = new ServerRequest("POST", "/content/a.html", 1001);

        Result r = RequestCorrelator.correlate(List.of(a),
                List.of(ev("log", late, 20_001, "late"), ev("log", post, 1002, "post")), 5000);

        assertThat(r.requests()).isEmpty();
        assertThat(r.serverOnly()).extracting(Group::label)
                .containsExactly("POST /content/a.html [1001]", "GET /content/a.html [20000]");
        assertThat(r.browserWithoutEvents()).extracting(BrowserRequest::id).containsExactly("a");
    }

    @Test
    void untimedEventsAttachToTheNthRequestWithTheSamePath() {
        BrowserRequest a1 = new BrowserRequest("a1", "GET", "/content/a.html", 1000);
        BrowserRequest other = new BrowserRequest("b", "GET", "/content/b.html", 1100);
        BrowserRequest a2 = new BrowserRequest("a2", "GET", "/content/a.html", 2000);
        ServerRequest untimed = new ServerRequest("GET", "/content/a.html", -1);
        ServerRequest unknown = new ServerRequest("GET", "/content/zzz.html", -1);

        Result r = RequestCorrelator.correlate(List.of(a1, other, a2), List.of(
                ev("rpt", untimed, 10, "rpt-1"), ev("rpt", untimed, 11, "rpt-2"), ev("rpt", untimed, 12, "rpt-3"),
                ev("rpt", unknown, 13, "rpt-z")), 5000);

        assertThat(r.requests()).extracting(g -> g.browser().id()).containsExactly("a1", "a2");
        assertThat(r.requests().get(0).events()).extracting(Event::summary).containsExactly("rpt-1");
        assertThat(r.requests().get(1).events()).extracting(Event::summary).containsExactly("rpt-2");
        assertThat(r.serverOnly()).hasSize(2);
        assertThat(r.serverOnly().get(0).events()).extracting(Event::summary).containsExactly("rpt-3");
        assertThat(r.serverOnly().get(1).label()).isEqualTo("GET /content/zzz.html");
    }

    @Test
    void groupsByServerRequestWithoutBrowserData() {
        ServerRequest s1 = new ServerRequest("GET", "/content/a.html", 1000);
        ServerRequest s2 = new ServerRequest("GET", "/content/b.html", 1500);

        Result r = RequestCorrelator.group(List.of(
                ev("exception", s2, 1600, "boom"), ev("log", s1, 1001, "one"), ev("log", s1, 1002, "two")));

        assertThat(r.requests()).isEmpty();
        assertThat(r.serverOnly()).extracting(Group::label)
                .containsExactly("GET /content/a.html [1000]", "GET /content/b.html [1500]");
        assertThat(r.serverOnly().get(0).events()).hasSize(2);
    }

    @Test
    void normalizesPaths() {
        assertThat(RequestCorrelator.normalizePath("http://localhost:4502/content/a%20b.html?x=1#top"))
                .isEqualTo("/content/a b.html");
        assertThat(RequestCorrelator.normalizePath("/content/a+b/%C3%A4.html")).isEqualTo("/content/a+b/\u00e4.html");
        assertThat(RequestCorrelator.normalizePath("content/x.html")).isEqualTo("/content/x.html");
        assertThat(RequestCorrelator.normalizePath("http://localhost:4502")).isEqualTo("/");
        assertThat(RequestCorrelator.normalizePath(null)).isEqualTo("/");
        assertThat(RequestCorrelator.normalizePath("/bad%zzescape")).isEqualTo("/bad%zzescape");
    }
}
