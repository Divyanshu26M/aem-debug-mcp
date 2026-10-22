package dev.aemdebug.aem;

import dev.aemdebug.testsupport.FakeAem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AemHttpClientTest {

    private FakeAem fake;
    private CookieJar cookies;
    private AemHttpClient http;
    private AemInstance author;

    @BeforeEach
    void setUp() throws Exception {
        fake = FakeAem.start();
        cookies = new CookieJar();
        http = new AemHttpClient(cookies);
        author = new AemInstance("author", URI.create(fake.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void getsPublicJsonAnonymously() {
        AemResponse response = http.get(author, "/libs/granite/security/currentuser.json");
        assertThat(response.ok()).isTrue();
        assertThat(response.jsonObject()).containsEntry("authorizableId", "anonymous");
        assertThat(response.isLoginRequired()).isFalse();
    }

    @Test
    void detectsLoginRedirectWithoutFollowingIt() {
        fake.json("/content/site.json", "{}");
        AemResponse response = http.get(author, "/content/site.json");
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.isLoginRequired()).isTrue();
        assertThat(fake.requests()).containsExactly("GET /content/site.json");
    }

    @Test
    void storesSessionCookieFromLoginAndSendsItAfterwards() {
        fake.json("/content/site.json", "{\"jcr:primaryType\":\"cq:Page\"}");
        AemResponse login = http.postForm(author, "/libs/granite/core/content/login.html/j_security_check",
                List.of(new String[] {"j_username", "admin"}, new String[] {"j_password", "admin"}),
                AemHttpClient.WriteReason.LOGIN, RequestOptions.defaults());
        assertThat(login.status()).isEqualTo(200);
        assertThat(cookies.get(author.origin(), "login-token")).isEqualTo(FakeAem.TOKEN);
        AemResponse page = http.get(author, "/content/site.json");
        assertThat(page.ok()).isTrue();
        assertThat(page.jsonObject()).containsEntry("jcr:primaryType", "cq:Page");
        assertThat(http.get(author, "/content/site.json", RequestOptions.defaults().anonymous()).status()).isEqualTo(302);
    }

    @Test
    void followsLocalRedirects() {
        fake.requireAuth(false);
        fake.route("/old", r -> new FakeAem.Reply(301, "text/plain", "").header("Location", "/new"));
        fake.text("/new", "moved here");
        AemResponse response = http.get(author, "/old");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("moved here");
        assertThat(response.uri().getPath()).isEqualTo("/new");
    }

    @Test
    void reportsUnreachableInstanceClearly() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        AemInstance down = new AemInstance("publish", URI.create("http://127.0.0.1:" + freePort));
        assertThatThrownBy(() -> http.get(down, "/libs/granite/core/content/login.html"))
                .isInstanceOf(AemUnavailableException.class)
                .hasMessageContaining("Is AEM running?");
    }

    @Test
    void blocksRemoteHostsAndRequiresWriteReason() {
        AemInstance remote = new AemInstance("author", URI.create("http://example.com"));
        assertThatThrownBy(() -> http.get(remote, "/")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> http.postForm(author, "/x", List.of(), null, RequestOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cookieJarIsKeyedByOrigin() {
        cookies.put("http://localhost:4502", "login-token", "a");
        cookies.put("http://localhost:4503", "login-token", "b");
        assertThat(cookies.cookieHeader("http://localhost:4502", java.util.Map.of("wcmmode", "edit"), true))
                .isEqualTo("login-token=a; wcmmode=edit");
        assertThat(cookies.cookieHeader("http://localhost:4503", null, false)).isEmpty();
        assertThat(new AemInstance("author", URI.create("http://localhost:4502")).origin())
                .isEqualTo("http://localhost:4502");
        assertThat(new AemInstance("author", URI.create("http://localhost")).origin()).isEqualTo("http://localhost:80");
    }
}
