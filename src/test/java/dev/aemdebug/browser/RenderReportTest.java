package dev.aemdebug.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenderReportTest {

    private static final String BASE = "http://localhost:4503";
    private static final String NOT_FOUND = "Failed to load resource: the server responded with a status of 404 (Not Found)";

    @Test
    void failedRequestsShowTheHttpStatusAndFaviconNoiseIsHidden() {
        NetworkEntry document = new NetworkEntry("GET", BASE + "/content/a.html", "document", 1000, true);
        document.setStatus(200);
        NetworkEntry aborted = new NetworkEntry("GET", BASE + "/etc.clientlibs/site/clientlib-hero.min.js", "script",
                1100, false);
        aborted.setFailure("net::ERR_ABORTED");
        NetworkEntry missingCss = new NetworkEntry("GET", BASE + "/etc.clientlibs/site/site.min.css", "stylesheet",
                1120, false);
        missingCss.setStatus(404);
        NetworkEntry favicon = new NetworkEntry("GET", BASE + "/favicon.ico", "other", 1200, false);
        favicon.setStatus(404);
        RenderResult result = RenderResult.forTest(BASE + "/content/a.html", "<html></html>")
                .withNetwork(document).withNetwork(aborted).withNetwork(missingCss).withNetwork(favicon)
                .withConsole(new ConsoleEntry("error", NOT_FOUND,
                        BASE + "/etc.clientlibs/site/clientlib-hero.min.js:0:0", 1150))
                .withConsole(new ConsoleEntry("error", NOT_FOUND, BASE + "/favicon.ico:0:0", 1250))
                .withConsole(new ConsoleEntry("error", "Uncaught ReferenceError: hero is not defined",
                        BASE + "/content/a.html:12:5", 1300));

        String summary = RenderReport.summary(result, 10);

        assertThat(summary).contains("Failed requests (2):")
                .contains("- 404 GET " + BASE + "/etc.clientlibs/site/clientlib-hero.min.js [script] net::ERR_ABORTED")
                .contains("- 404 GET " + BASE + "/etc.clientlibs/site/site.min.css [stylesheet]")
                .contains("Console/JS errors (2):")
                .contains("hero is not defined")
                .doesNotContain("favicon.ico");
        assertThat(RenderReport.networkLog(result)).contains("favicon.ico");
    }

    @Test
    void unknownStatusStaysFailed() {
        NetworkEntry refused = new NetworkEntry("GET", BASE + "/api/data.json", "fetch", 1000, false);
        refused.setFailure("net::ERR_CONNECTION_REFUSED");
        assertThat(RenderReport.statusOf(refused, java.util.List.of())).isEqualTo(-1);
        RenderResult result = RenderResult.forTest(BASE + "/content/a.html", "<html></html>").withNetwork(refused);
        assertThat(RenderReport.summary(result, 10)).contains("- FAILED GET " + BASE + "/api/data.json [fetch]");
    }
}
