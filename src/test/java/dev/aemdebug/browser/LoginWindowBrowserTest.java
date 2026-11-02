package dev.aemdebug.browser;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.LoginStore;
import dev.aemdebug.config.Config;
import dev.aemdebug.server.Services;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AEM login window driven through Playwright ({@code mvn verify -Pbrowser-tests}). The window runs headless and
 * the fake login page signs in by itself, standing in for the user.
 */
@Tag("browser")
class LoginWindowBrowserTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void headlessWindows() {
        System.setProperty("aemdebug.headlessLoginWindows", "true");
    }

    @AfterAll
    static void restore() {
        System.clearProperty("aemdebug.headlessLoginWindows");
    }

    private static long loginPageLoads(FakeAem fake) {
        return fake.requests().stream().filter(r -> r.equals("GET " + FakeAem.LOGIN_PAGE)).count();
    }

    @Test
    void parallelReadsShareOneLoginWindowAndTheNextChatReusesTheLogin() throws Exception {
        try (FakeAem fake = FakeAem.start()) {
            fake.json("/content/site.json", "{\"jcr:primaryType\":\"cq:Page\"}");
            fake.route(FakeAem.LOGIN_PAGE, r -> new FakeAem.Reply(200, "text/html", "<html><body>AEM Sign In<script>"
                    + "setTimeout(() => fetch('" + FakeAem.LOGIN_PAGE + "/j_security_check', { method: 'POST',"
                    + " headers: { 'Content-Type': 'application/x-www-form-urlencoded' },"
                    + " body: 'j_username=admin&j_password=admin&j_validate=true' }), 1500);"
                    + "</script></body></html>"));
            Config config = Config.builder().authorUrl(fake.baseUrl()).artifactsDir(temp).loginWaitSeconds(30)
                    .rememberLogins(true).build();

            Services first = new Services(config);
            AemInstance author = first.instances.author();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<Map<String, Object>>> reads = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    reads.add(pool.submit(() -> first.aem.getJsonObject(ToolContext.detached(), author,
                            "/content/site.json")));
                }
                for (Future<Map<String, Object>> read : reads) {
                    assertThat(read.get(60, TimeUnit.SECONDS)).containsEntry("jcr:primaryType", "cq:Page");
                }
                assertThat(loginPageLoads(fake)).as(fake.requests().toString()).isEqualTo(1);
                assertThat(first.auth.status(author, false).method).isEqualTo(AuthManager.Method.INTERACTIVE);
                assertThat(LoginStore.in(temp).token(author.origin())).isEqualTo(FakeAem.TOKEN);
            } finally {
                pool.shutdownNow();
                first.cleanup.runAll(ToolContext.detached(), true);
                first.browser.close();
            }

            Services nextChat = new Services(config);
            try {
                assertThat(nextChat.aem.getJsonObject(ToolContext.detached(), author, "/content/site.json"))
                        .containsEntry("jcr:primaryType", "cq:Page");
                assertThat(loginPageLoads(fake)).isEqualTo(1);
                assertThat(nextChat.auth.status(author, false).method).isEqualTo(AuthManager.Method.REMEMBERED);
            } finally {
                nextChat.cleanup.runAll(ToolContext.detached(), true);
                nextChat.browser.close();
            }
        }
    }
}
