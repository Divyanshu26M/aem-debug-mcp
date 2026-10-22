package dev.aemdebug.aem;

import dev.aemdebug.config.Config;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthManagerTest {

    @TempDir
    Path temp;

    private FakeAem fake;

    @BeforeEach
    void setUp() throws Exception {
        fake = FakeAem.start();
        fake.json("/content/site.json", "{\"jcr:primaryType\":\"cq:Page\"}");
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private Config config(String user, String password) {
        Config.Builder builder = Config.builder().authorUrl(fake.baseUrl()).artifactsDir(temp).loginWaitSeconds(1);
        if (user != null) {
            builder.credentials(user, password);
        }
        return builder.build();
    }

    @Test
    void credentialLoginEstablishesSession() {
        Config config = config("admin", "admin");
        AemHttpClient http = new AemHttpClient(new CookieJar());
        AuthManager auth = new AuthManager(config, http);
        AemInstance author = new AemInstances(config).author();
        assertThat(auth.loginWithCredentials(author)).isTrue();
        assertThat(auth.hasSession(author)).isTrue();
        AuthManager.Status status = auth.status(author, true);
        assertThat(status.user).isEqualTo("admin");
        assertThat(status.method).isEqualTo(AuthManager.Method.CREDENTIALS);
        auth.logout(author);
        assertThat(auth.hasSession(author)).isFalse();
    }

    @Test
    void wrongCredentialsFailWithoutLeakingThePassword() {
        Config config = config("admin", "wrong-password");
        AuthManager auth = new AuthManager(config, new AemHttpClient(new CookieJar()));
        AemInstance author = new AemInstances(config).author();
        assertThat(auth.loginWithCredentials(author)).isFalse();
        AuthManager.Status status = auth.status(author, false);
        assertThat(status.authenticated()).isFalse();
        assertThat(status.lastError).contains("HTTP 403").doesNotContain("wrong-password");
    }

    @Test
    void interactiveLoginCapturesTokenFromLoginWindow() {
        Config config = config(null, null);
        AuthManager auth = new AuthManager(config, new AemHttpClient(new CookieJar()));
        AtomicInteger opened = new AtomicInteger();
        auth.setLoginLauncher(new AuthManager.LoginLauncher() {
            @Override
            public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
                opened.incrementAndGet();
                assertThat(loginUrl).endsWith("/libs/granite/core/content/login.html");
                pending.complete(FakeAem.TOKEN);
            }

            @Override
            public void closeLoginWindow(AemInstance instance) {
            }
        });
        AemInstance author = new AemInstances(config).author();
        AemClient client = new AemClient(new AemInstances(config), new AemHttpClient(new CookieJar()), auth);
        AuthManager.Status status = auth.ensureSession(ToolContext.detached(), author, 2, false);
        assertThat(status.user).isEqualTo("admin");
        assertThat(status.method).isEqualTo(AuthManager.Method.INTERACTIVE);
        assertThat(opened.get()).isEqualTo(1);
        assertThat(client).isNotNull();
    }

    @Test
    void pendingInteractiveLoginReportsAwaitingLogin() {
        Config config = config(null, null);
        AuthManager auth = new AuthManager(config, new AemHttpClient(new CookieJar()));
        auth.setLoginLauncher(new AuthManager.LoginLauncher() {
            @Override
            public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
                // user never signs in
            }

            @Override
            public void closeLoginWindow(AemInstance instance) {
            }
        });
        AemInstance author = new AemInstances(config).author();
        assertThatThrownBy(() -> auth.ensureSession(ToolContext.detached(), author))
                .isInstanceOf(AuthPendingException.class)
                .hasMessageContaining("A browser window is open");
        assertThat(auth.status(author, false).loginWindowOpen).isTrue();
    }

    @Test
    void aemClientLogsInOnDemandForAuthorReads() {
        Config config = config("admin", "admin");
        AemHttpClient http = new AemHttpClient(new CookieJar());
        AuthManager auth = new AuthManager(config, http);
        AemInstances instances = new AemInstances(config);
        AemClient client = new AemClient(instances, http, auth);
        assertThat(client.getJsonObject(ToolContext.detached(), instances.author(), "/content/site.json"))
                .containsEntry("jcr:primaryType", "cq:Page");
        assertThat(fake.requests()).contains("POST /libs/granite/core/content/login.html/j_security_check");
    }
}
