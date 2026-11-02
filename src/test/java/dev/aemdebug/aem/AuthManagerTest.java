package dev.aemdebug.aem;

import dev.aemdebug.config.Config;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void parallelCallsShareOneLoginWindow() throws Exception {
        Config config = config(null, null);
        AuthManager auth = new AuthManager(config, new AemHttpClient(new CookieJar()));
        AtomicInteger opened = new AtomicInteger();
        auth.setLoginLauncher(new AuthManager.LoginLauncher() {
            @Override
            public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
                opened.incrementAndGet();
                sleep(300); // a slow browser start widens the race window
            }

            @Override
            public void closeLoginWindow(AemInstance instance) {
            }
        });
        AemInstance author = new AemInstances(config).author();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final boolean viaEnsure = i % 2 == 0;
                results.add(pool.submit(() -> {
                    start.await();
                    if (!viaEnsure) {
                        return auth.startInteractiveLogin(author);
                    }
                    try {
                        auth.ensureSession(ToolContext.detached(), author);
                        return "logged in";
                    } catch (AuthPendingException e) {
                        return e.getMessage();
                    }
                }));
            }
            start.countDown();
            Set<Object> windows = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Object> result : results) {
                Object value = result.get(20, TimeUnit.SECONDS);
                if (value instanceof AuthManager.PendingLogin) {
                    windows.add(value);
                    assertThat(((AuthManager.PendingLogin) value).isOpen()).isTrue();
                } else {
                    assertThat((String) value).contains("A browser window is open").doesNotContain("replaced");
                }
            }
            assertThat(opened.get()).isEqualTo(1);
            assertThat(windows).hasSize(1);
            assertThat(auth.status(author, false).loginWindowOpen).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRememberedLoginIsReusedByTheNextServerAndForgottenOnLogout() {
        Config withCredentials = config("admin", "admin");
        AemInstance author = new AemInstances(withCredentials).author();
        Path data = temp.resolve("plugin-data");
        AuthManager first = new AuthManager(withCredentials, new AemHttpClient(new CookieJar()), LoginStore.in(data));
        assertThat(first.loginWithCredentials(author)).isTrue();
        assertThat(LoginStore.in(data).token(author.origin())).isEqualTo(FakeAem.TOKEN);

        Config noCredentials = config(null, null);
        AuthManager second = new AuthManager(noCredentials, new AemHttpClient(new CookieJar()), LoginStore.in(data));
        second.setLoginLauncher(noWindowExpected());
        AuthManager.Status status = second.ensureSession(ToolContext.detached(), author, 1, false);
        assertThat(status.user).isEqualTo("admin");
        assertThat(status.method).isEqualTo(AuthManager.Method.REMEMBERED);
        assertThat(second.hasSession(author)).isTrue();

        second.logout(author);
        assertThat(LoginStore.in(data).token(author.origin())).isNull();
        AuthManager third = new AuthManager(noCredentials, new AemHttpClient(new CookieJar()), LoginStore.in(data));
        assertThat(third.status(author, true).authenticated()).isFalse();
    }

    @Test
    void aRejectedRememberedTokenIsDroppedButAnUnclearAnswerKeepsIt() {
        Config config = config(null, null);
        AemInstance author = new AemInstances(config).author();
        LoginStore store = LoginStore.in(temp);
        store.save(author.origin(), "admin", "expired-token");
        AuthManager auth = new AuthManager(config, new AemHttpClient(new CookieJar()), store);

        fake.route("/libs/granite/security/currentuser.json", r -> new FakeAem.Reply(503, "text/html", "starting"));
        assertThat(auth.status(author, true).authenticated()).isFalse();
        assertThat(store.token(author.origin())).isEqualTo("expired-token");

        fake.route("/libs/granite/security/currentuser.json", r -> new FakeAem.Reply(200, "application/json",
                "{\"authorizableId\":\"anonymous\"}"));
        assertThat(auth.status(author, true).authenticated()).isFalse();
        assertThat(store.token(author.origin())).isNull();
    }

    @Test
    void aRunningChatPicksUpAnotherChatsLoginAndNeverDeletesItForAnOldToken() {
        Config config = config(null, null);
        AemInstance author = new AemInstances(config).author();
        String origin = author.origin();
        LoginStore store = LoginStore.in(temp);
        AuthManager chat = new AuthManager(config, new AemHttpClient(new CookieJar()), store);
        chat.setLoginLauncher(noWindowExpected());
        assertThat(chat.status(author, true).authenticated()).isFalse();

        store.save(origin, "admin", "expired-token");
        assertThat(chat.status(author, true).authenticated()).isFalse();
        assertThat(store.token(origin)).as("AEM rejected it").isNull();
        store.save(origin, "admin", "expired-token");
        long checks = fake.requests().stream().filter(r -> r.contains("currentuser")).count();
        assertThat(chat.status(author, true).authenticated()).isFalse();
        assertThat(fake.requests().stream().filter(r -> r.contains("currentuser")).count())
                .as("a rejected token is not loaded again").isEqualTo(checks);

        store.save(origin, "admin", FakeAem.TOKEN);
        AuthManager.Status status = chat.ensureSession(ToolContext.detached(), author, 1, false);
        assertThat(status.user).isEqualTo("admin");
        assertThat(status.method).isEqualTo(AuthManager.Method.REMEMBERED);

        store.removeIfToken(origin, "expired-token");
        assertThat(store.token(origin)).as("a newer login stays").isEqualTo(FakeAem.TOKEN);
    }

    @Test
    void anExpiredTokenLeadsToOneNewLoginInsteadOfALoop() {
        Config config = config(null, null);
        AemInstances instances = new AemInstances(config);
        AemInstance author = instances.author();
        LoginStore store = LoginStore.in(temp);
        AemHttpClient http = new AemHttpClient(new CookieJar());
        AuthManager auth = new AuthManager(config, http, store);
        AtomicInteger windows = new AtomicInteger();
        auth.setLoginLauncher(new AuthManager.LoginLauncher() {
            @Override
            public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
                windows.incrementAndGet();
                pending.complete(fake.validToken());
            }

            @Override
            public void closeLoginWindow(AemInstance instance) {
            }
        });
        AemClient client = new AemClient(instances, http, auth);
        fake.clearRejectedTokens(true);
        assertThat(client.getJsonObject(ToolContext.detached(), author, "/content/site.json")).isNotEmpty();
        assertThat(windows.get()).isEqualTo(1);
        assertThat(store.token(author.origin())).isEqualTo(FakeAem.TOKEN);

        // The token expires: AEM redirects to the login page and clears the cookie in the same response.
        fake.validToken("fresh-login-token-2");
        assertThat(client.getJsonObject(ToolContext.detached(), author, "/content/site.json")).isNotEmpty();
        assertThat(windows.get()).as("one new login window, no loop").isEqualTo(2);
        assertThat(store.token(author.origin())).isEqualTo("fresh-login-token-2");
        assertThat(auth.status(author, false).method).isEqualTo(AuthManager.Method.INTERACTIVE);

        // status() notices an expiry too, even though AEM clears the cookie while answering.
        fake.validToken("fresh-login-token-3");
        assertThat(auth.status(author, true).authenticated()).isFalse();
        assertThat(store.token(author.origin())).isNull();
        assertThat(auth.hasSession(author)).isFalse();
    }

    @Test
    void rememberedLoginsAreOwnerOnlyAndExpire() throws Exception {
        LoginStore store = LoginStore.in(temp);
        store.save("http://localhost:4502", "admin", "token-1");
        Path file = store.file();
        assertThat(Files.readString(file)).contains("token-1").doesNotContain("j_password");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertThat(PosixFilePermissions.toString(posix.readAttributes().permissions())).isEqualTo("rw-------");
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertThat(acl.getAcl()).hasSize(1);
            assertThat(acl.getAcl().get(0).principal()).isEqualTo(acl.getOwner());
        }
        assertThat(store.token("http://localhost:4502")).isEqualTo("token-1");

        Files.writeString(file, "{\"logins\":{\"http://localhost:4502\":{\"user\":\"admin\",\"token\":\"old\","
                + "\"savedAt\":1}}}");
        assertThat(store.token("http://localhost:4502")).isNull();
        assertThat(LoginStore.disabled().token("http://localhost:4502")).isNull();
    }

    private static AuthManager.LoginLauncher noWindowExpected() {
        return new AuthManager.LoginLauncher() {
            @Override
            public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
                throw new AssertionError("no login window expected");
            }

            @Override
            public void closeLoginWindow(AemInstance instance) {
            }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
