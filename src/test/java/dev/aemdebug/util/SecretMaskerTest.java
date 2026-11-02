package dev.aemdebug.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTest {

    @Test
    void recognizesSecretKeys() {
        assertThat(SecretMasker.isSecretKey("password")).isTrue();
        assertThat(SecretMasker.isSecretKey("smtp.password")).isTrue();
        assertThat(SecretMasker.isSecretKey("apiKey")).isTrue();
        assertThat(SecretMasker.isSecretKey("client_secret")).isTrue();
        assertThat(SecretMasker.isSecretKey("accessToken")).isTrue();
        assertThat(SecretMasker.isSecretKey("jcr:title")).isFalse();
        assertThat(SecretMasker.isSecretKey("author")).isFalse();
        assertThat(SecretMasker.isSecretKey("sling:resourceType")).isFalse();
        assertThat(SecretMasker.isSecretKey("sling:key")).isFalse();
    }

    @Test
    void masksTreesRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("apiKey", "abc123");
        inner.put("title", "Hello");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("password", "s3cret");
        root.put("nested", inner);
        root.put("list", List.of(Map.of("token", "t"), "plain"));
        root.put("encrypted", "{0123456789abcdef0123456789abcdef0123456789abcdef}");
        root.put("enabledSecret", Boolean.TRUE);

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) SecretMasker.maskTree(root);
        assertThat(masked.get("password")).isEqualTo(SecretMasker.MASK);
        assertThat(((Map<?, ?>) masked.get("nested")).get("apiKey")).isEqualTo(SecretMasker.MASK);
        assertThat(((Map<?, ?>) masked.get("nested")).get("title")).isEqualTo("Hello");
        assertThat(((Map<?, ?>) ((List<?>) masked.get("list")).get(0)).get("token")).isEqualTo(SecretMasker.MASK);
        assertThat(masked.get("encrypted")).isEqualTo("{****}");
        assertThat(masked.get("enabledSecret")).isEqualTo(Boolean.TRUE);
        assertThat(root.get("password")).isEqualTo("s3cret");
    }

    @Test
    void masksFreeText() {
        String text = "Cookie: login-token=abc:def%3Atoken; other=1\nj_password=hunter2&j_username=admin\n"
                + "Authorization: Basic YWRtaW46YWRtaW4=\n{\"password\":\"x1\"}";
        String masked = SecretMasker.maskText(text);
        assertThat(masked).doesNotContain("abc:def").doesNotContain("hunter2").doesNotContain("YWRtaW46YWRtaW4=")
                .doesNotContain("x1");
        assertThat(masked).contains("j_username=admin").contains("other=1");
    }

    @Test
    void masksCredentialsInUrls() {
        assertThat(SecretMasker.maskValue("http://admin:s3cret@localhost:4502/content/site.html"))
                .isEqualTo("http://****@localhost:4502/content/site.html");
        assertThat(SecretMasker.maskText("open https://user:p%40ss@127.0.0.1:3000/ now"))
                .isEqualTo("open https://****@127.0.0.1:3000/ now");
        assertThat(String.valueOf(SecretMasker.maskTree(Map.of("path", "http://a:b@localhost:3000/x"))))
                .doesNotContain("a:b");
        assertThat(SecretMasker.maskValue("http://localhost:4502/content/a@b.html")).contains("a@b.html");
    }
}
