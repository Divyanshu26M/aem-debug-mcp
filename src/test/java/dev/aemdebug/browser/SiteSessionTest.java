package dev.aemdebug.browser;

import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiteSessionTest {

    private static Cookie cookie(String name, String value, String domain) {
        Cookie cookie = new Cookie(name, value);
        cookie.setDomain(domain);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setSameSite(SameSiteAttribute.LAX);
        return cookie;
    }

    private static SiteSession session(boolean windowOpen) {
        List<Cookie> cookies = List.of(
                cookie("app-session", "secret-session-value", "localhost"),
                cookie("csrf", "secret-csrf-value", ".localhost"),
                cookie("idp-session", "secret-idp-value", "login.idp.example.com"),
                cookie("lb", "secret-lb-value", "127.0.0.1"));
        Map<String, Map<String, String>> local = new LinkedHashMap<>();
        local.put("http://localhost:3000", Map.of("accessToken", "secret-access-token", "theme", "dark"));
        local.put("https://login.idp.example.com", Map.of("idp-state", "secret-idp-state"));
        Map<String, Map<String, String>> session = new LinkedHashMap<>();
        session.put("http://localhost:3000", Map.of("nonce", "secret-nonce"));
        session.put("http://localhost:3001", Map.of());
        return new SiteSession(cookies, local, session, "http://localhost:3000/account", 1_000L, windowOpen);
    }

    @Test
    void keepsOnlyCookiesAndStorageOfLocalHosts() {
        SiteSession site = session(false);
        assertThat(site.isEmpty()).isFalse();
        assertThat(site.cookies()).extracting(c -> c.name).containsExactly("app-session", "csrf", "lb");
        assertThat(site.cookies().get(0).sameSite).isEqualTo(SameSiteAttribute.LAX);
        String script = site.initScript();
        assertThat(script).contains("secret-access-token").contains("secret-nonce")
                .doesNotContain("secret-idp-state").doesNotContain("login.idp.example.com");
        assertThat(script).contains("store.getItem(k) === null");
    }

    @Test
    void summariesNameCookiesAndKeysButNeverValues() {
        SiteSession site = session(true);
        String summary = site.summary();
        assertThat(summary).contains("3 cookies (app-session, csrf, lb)")
                .contains("local storage: http://localhost:3000 (2 keys)")
                .contains("session storage: http://localhost:3000 (1 key)")
                .contains("from http://localhost:3000/account").contains("login window still open")
                .doesNotContain("secret").doesNotContain("idp");
        assertThat(site.label()).startsWith("site session captured").contains("3 cookies, 3 storage keys")
                .contains("login window still open").doesNotContain("secret");
    }

    @Test
    void cookiesAreCopiedSoTheSnapshotCannotChange() {
        SiteSession site = session(false);
        site.cookies().get(0).value = "changed";
        assertThat(site.cookies().get(0).value).isEqualTo("secret-session-value");
        SiteSession empty = new SiteSession(List.of(), Map.of(), Map.of(), "http://localhost:3000/", 1L, false);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.initScript()).isNull();
        assertThat(SiteSession.isLocalOrigin("http://[::1]:3000")).isTrue();
        assertThat(SiteSession.isLocalOrigin("https://www.example.com")).isFalse();
        assertThat(SiteSession.isLocalDomain(".LOCALHOST")).isTrue();
    }
}
