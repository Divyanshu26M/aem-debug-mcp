package dev.aemdebug.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @Test
    void defaultsPointToLocalAuthorAndPublish() {
        Config config = Config.fromEnvironment(Map.of());
        assertThat(config.authorUrl()).isEqualTo(URI.create("http://localhost:4502"));
        assertThat(config.publishUrl()).isEqualTo(URI.create("http://localhost:4503"));
        assertThat(config.jdwpHost()).isEqualTo("127.0.0.1");
        assertThat(config.jdwpPort()).isEqualTo(8767);
        assertThat(config.problems()).isEmpty();
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void rejectsRemoteHosts() {
        Map<String, String> env = new HashMap<>();
        env.put("AEM_AUTHOR_URL", "https://author-p123-e456.adobeaemcloud.com");
        env.put("AEM_JDWP_HOST", "10.0.0.5");
        Config config = Config.fromEnvironment(env);
        assertThat(config.authorUrl()).isNull();
        assertThat(config.jdwpHost()).isNull();
        assertThat(config.problems()).hasSize(2);
        assertThat(config.problems().get(0)).contains("Only local AEM instances are allowed");
    }

    @Test
    void parsesOptionalSettingsAndIgnoresUnexpandedVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("AEM_AUTHOR_URL", "http://127.0.0.1:4502/");
        env.put("AEM_JDWP_PORT", "5005");
        env.put("AEM_USER", "admin");
        env.put("AEM_PASSWORD", "admin");
        env.put("AEM_DEBUG_PROJECT_PACKAGES", "com.mysite, com.other");
        env.put("AEM_DEBUG_BROWSER_CHANNEL", "Chrome");
        env.put("AEM_PROJECT_DIR", "${PLUGIN_ROOT}/x");
        env.put("AEM_DEBUG_IDLE_DETACH_MINUTES", "abc");
        Config config = Config.fromEnvironment(env);
        assertThat(config.authorUrl()).isEqualTo(URI.create("http://127.0.0.1:4502"));
        assertThat(config.jdwpPort()).isEqualTo(5005);
        assertThat(config.hasCredentials()).isTrue();
        assertThat(config.projectPackages()).containsExactly("com.mysite", "com.other");
        assertThat(config.browserChannel()).isEqualTo("chrome");
        assertThat(config.projectDir()).isNull();
        assertThat(config.idleDetachMinutes()).isEqualTo(15);
        assertThat(config.problems()).anyMatch(p -> p.contains("AEM_DEBUG_IDLE_DETACH_MINUTES"));
        assertThat(config.toString()).doesNotContain("password=admin").contains("password=****");
    }

    @Test
    void publishDebuggingDevServersAndRememberedLogins() {
        Config defaults = Config.fromEnvironment(Map.of());
        assertThat(defaults.jdwpPort("author")).isEqualTo(8767);
        assertThat(defaults.jdwpPort("publish")).isEqualTo(-1);
        assertThat(defaults.logDir("publish")).isNull();
        assertThat(defaults.devServers()).isEmpty();
        assertThat(defaults.rememberLogins()).isTrue();

        Map<String, String> env = new HashMap<>();
        env.put("AEM_PUBLISH_JDWP_PORT", "8768");
        env.put("AEM_LOG_DIR", "/aem/author/crx-quickstart/logs");
        env.put("AEM_PUBLISH_LOG_DIR", "/aem/publish/crx-quickstart/logs");
        env.put("AEM_DEV_SERVERS", "http://localhost:3000=publish, http://127.0.0.1:5173=Author");
        env.put("AEM_DEBUG_REMEMBER_LOGINS", "false");
        Config config = Config.fromEnvironment(env);
        assertThat(config.jdwpPort("publish")).isEqualTo(8768);
        assertThat(config.jdwpPort("author")).isEqualTo(8767);
        assertThat(config.logDir("publish").toString().replace('\\', '/')).endsWith("publish/crx-quickstart/logs");
        assertThat(config.logDir("author").toString().replace('\\', '/')).endsWith("author/crx-quickstart/logs");
        assertThat(config.devServers()).containsEntry("http://localhost:3000", "publish")
                .containsEntry("http://localhost:5173", "author").hasSize(2);
        assertThat(config.rememberLogins()).isFalse();
        assertThat(config.problems()).isEmpty();
    }

    @Test
    void invalidDevServerEntriesAreReported() {
        Map<String, String> env = new HashMap<>();
        env.put("AEM_DEV_SERVERS", "http://localhost:3000;http://localhost:3001=stage;https://www.example.com=publish");
        env.put("AEM_PUBLISH_JDWP_PORT", "99999");
        Config config = Config.fromEnvironment(env);
        assertThat(config.devServers()).isEmpty();
        assertThat(config.jdwpPort("publish")).isEqualTo(-1);
        assertThat(config.problems()).hasSize(4)
                .anyMatch(p -> p.contains("'http://localhost:3000'"))
                .anyMatch(p -> p.contains("'http://localhost:3001=stage'"))
                .anyMatch(p -> p.contains("Only local AEM instances are allowed"))
                .anyMatch(p -> p.contains("AEM_PUBLISH_JDWP_PORT"));
    }

    @Test
    void hostPolicyAllowsOnlyLocalHosts() {
        assertThat(HostPolicy.isLocalHost("localhost")).isTrue();
        assertThat(HostPolicy.isLocalHost("127.0.0.1")).isTrue();
        assertThat(HostPolicy.isLocalHost("[::1]")).isTrue();
        assertThat(HostPolicy.isLocalHost("example.com")).isFalse();
        assertThat(HostPolicy.isLocalHost("127.0.0.2")).isFalse();
        assertThat(HostPolicy.requireLocalBaseUrl("http://[::1]:4502", "x").getHost()).isEqualTo("[::1]");
        assertThatThrownBy(() -> HostPolicy.requireLocalBaseUrl("ftp://localhost", "x"))
                .hasMessageContaining("http or https");
        assertThatThrownBy(() -> HostPolicy.requireLocal(URI.create("http://evil.example/")))
                .isInstanceOf(SecurityException.class);
        assertThat(HostPolicy.originKey(URI.create("http://127.0.0.1:3000/x?y=1")))
                .isEqualTo(HostPolicy.originKey(URI.create("http://LOCALHOST:3000")))
                .isEqualTo("http://localhost:3000");
        assertThat(HostPolicy.originKey(URI.create("https://localhost/"))).isEqualTo("https://localhost:443");
    }
}
