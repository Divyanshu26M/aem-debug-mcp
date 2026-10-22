package dev.aemdebug.rpt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RptParserTest {

    static final String RPT = String.join("\n",
            "      0 TIMER_START{Request Processing}",
            "      1 COMMENT timer_end format is {<elapsed microseconds>,<timer name>} <optional message>",
            "     13 LOG Method=GET, PathInfo=/content/site/en.html",
            "     17 TIMER_START{handleSecurity}",
            "   2600 TIMER_END{2582,handleSecurity} authenticator org.apache.sling.auth.core.impl.SlingAuthenticator returns true",
            "   3047 TIMER_START{ResourceResolution}",
            "   4049 TIMER_END{1001,ResourceResolution} URI=/content/site/en.html resolves to Resource=JcrNodeResource, "
                    + "type=site/components/page, superType=null, path=/content/site/en/jcr:content",
            "   4069 LOG Resource Path Info: SlingRequestPathInfo: path='/content/site/en/jcr:content', selectorString='null', extension='html', suffix='null'",
            "   4069 TIMER_START{ServletResolution}",
            "   4071 TIMER_START{resolveServlet(/content/site/en/jcr:content)}",
            "   4791 TIMER_END{719,resolveServlet(/content/site/en/jcr:content)} Using servlet /apps/site/components/page/page.html",
            "   4795 TIMER_END{725,ServletResolution} URI=/content/site/en.html handled by Servlet=/apps/site/components/page/page.html",
            "   4800 LOG Applying REQUEST filters",
            "   5500 TIMER_START{/apps/site/components/page/page.html#0}",
            "   6800 LOG Including resource JcrNodeResource, type=site/components/hero, superType=null, "
                    + "path=/content/site/en/jcr:content/root/hero (SlingRequestPathInfo: path='/content/site/en/jcr:content/root/hero', selectorString='null', extension='html', suffix='null')",
            "   6801 TIMER_START{resolveServlet(/content/site/en/jcr:content/root/hero)}",
            "   6900 TIMER_END{99,resolveServlet(/content/site/en/jcr:content/root/hero)} Using servlet /apps/site/components/hero/hero.html",
            "   6950 TIMER_START{/apps/site/components/hero/hero.html#1}",
            "   7300 LOG Exception during response processing: java.lang.NullPointerException",
            "   9950 TIMER_END{3000,/apps/site/components/hero/hero.html#1}",
            "  92000 TIMER_END{86500,/apps/site/components/page/page.html#0}",
            "  98000 TIMER_END{97999,Request Processing} Dumping SlingRequestProgressTracker Entries");

    @Test
    void parsesResolutionIncludesAndTimers() {
        RptParser.Result result = RptParser.parse(RPT);
        assertThat(result.unit).isEqualTo("us");
        assertThat(result.method).isEqualTo("GET");
        assertThat(result.uri).isEqualTo("/content/site/en.html");
        assertThat(result.resolvedPath).isEqualTo("/content/site/en/jcr:content");
        assertThat(result.resolvedType).isEqualTo("site/components/page");
        assertThat(result.servlet).isEqualTo("/apps/site/components/page/page.html");
        assertThat(result.total).isEqualTo(97999);
        assertThat(result.formatDuration(result.total)).isEqualTo("98.0 ms");
        List<RptParser.Include> includes = result.includes;
        assertThat(includes).hasSize(1);
        RptParser.Include hero = includes.get(0);
        assertThat(hero.resourcePath).isEqualTo("/content/site/en/jcr:content/root/hero");
        assertThat(hero.resourceType).isEqualTo("site/components/hero");
        assertThat(hero.script).isEqualTo("/apps/site/components/hero/hero.html");
        assertThat(hero.elapsed).isEqualTo(3000);
        assertThat(result.scriptsByPath()).containsEntry("/content/site/en/jcr:content/root/hero",
                "/apps/site/components/hero/hero.html");
        assertThat(result.problems).anyMatch(p -> p.contains("NullPointerException"));
        String tree = result.tree(50);
        assertThat(tree).contains("Request Processing 98.0 ms").contains("  ResourceResolution 1.0 ms")
                .contains("* Including resource");
    }

    @Test
    void toleratesMillisecondFormatAndGarbage() {
        RptParser.Result result = RptParser.parse("garbage\n   0 TIMER_START{Request Processing}\n"
                + "   0 COMMENT timer_end format is {<elapsed msec>,<timer name>} <optional message>\n"
                + "  12 TIMER_END{12,Request Processing}\n");
        assertThat(result.unit).isEqualTo("ms");
        assertThat(result.total).isEqualTo(12);
        assertThat(result.formatDuration(12)).isEqualTo("12.0 ms");
        assertThat(RptParser.parse(null).lineCount).isZero();
    }

    @Test
    void parsesRecentRequestsConsoleHtml() {
        String list = "<html><body><p class='statline'>Recorded 2 requests (max: 20)</p><table>"
                + "<tr><td><a href='requests?index=17'>17 : GET /content/site/en.html (admin)</a></td><td>17:19:00</td></tr>"
                + "<tr><td><a href='requests?index=16'>16 : GET /etc.clientlibs/site/clientlibs/site.min.js (anonymous)</a></td></tr>"
                + "</table></body></html>";
        List<RecentRequestsClient.RecentRequest> requests = RecentRequestsClient.parseList(list);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).index).isEqualTo("17");
        assertThat(requests.get(0).method).isEqualTo("GET");
        assertThat(requests.get(0).path).isEqualTo("/content/site/en.html");
        assertThat(requests.get(0).user).isEqualTo("admin");
        assertThat(requests.get(0).time).isEqualTo("17:19:00");

        String detail = "<html><body><h2>Request 17 (GET /content/site/en.html) by admin</h2><pre>" + RPT
                + "</pre></body></html>";
        String rpt = RecentRequestsClient.extractRpt(detail);
        assertThat(rpt).contains("TIMER_START{Request Processing}");
        assertThat(RptParser.parse(rpt).includes).hasSize(1);
        assertThat(RecentRequestsClient.extractRpt("<html><body>nothing</body></html>")).isNull();
    }
}
