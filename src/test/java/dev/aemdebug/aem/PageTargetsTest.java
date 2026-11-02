package dev.aemdebug.aem;

import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTargetsTest {

    @TempDir
    Path temp;

    private PageTargets targets(Config config) {
        return new PageTargets(new AemInstances(config), new DevServers(temp, config.devServers()));
    }

    private Config config() {
        return Config.builder().authorUrl("http://localhost:4502").publishUrl("http://localhost:4503")
                .artifactsDir(temp).build();
    }

    @Test
    void plainPathsUseTheInstanceArgument() {
        PageTargets targets = targets(config());
        PageTarget author = targets.resolve("/content/site/en", null);
        assertThat(author.instance().name()).isEqualTo("author");
        assertThat(author.devServer()).isFalse();
        assertThat(author.requestPath()).isEqualTo("/content/site/en.html");
        assertThat(author.renderUrl()).isEqualTo("http://localhost:4502/content/site/en.html");
        assertThat(author.instanceSource()).isEqualTo("default");

        PageTarget publish = targets.resolve("/content/site/en.html?x=1", "publish");
        assertThat(publish.instance().name()).isEqualTo("publish");
        assertThat(publish.renderUrl()).isEqualTo("http://localhost:4503/content/site/en.html?x=1");
        assertThat(publish.origin()).isEqualTo("http://localhost:4503");
        assertThat(publish.describe()).isEqualTo("publish");
    }

    @Test
    void urlsOfAnInstanceSelectThatInstance() {
        PageTargets targets = targets(config());
        PageTarget publish = targets.resolve("http://127.0.0.1:4503/content/site/en.html", null);
        assertThat(publish.instance().name()).isEqualTo("publish");
        assertThat(publish.requestPath()).isEqualTo("/content/site/en.html");

        PageTarget editor = targets.resolve("http://localhost:4502/editor.html/content/site/en.html", "author");
        assertThat(editor.instance().name()).isEqualTo("author");
        assertThat(editor.requestPath()).isEqualTo("/content/site/en.html");

        assertThatThrownBy(() -> targets.resolve("http://localhost:4503/content/site/en.html", "author"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is a publish URL but instance=author");
    }

    @Test
    void aDevServerUrlIsNeverRemappedToAnInstance() {
        PageTargets targets = targets(config());
        assertThatThrownBy(() -> targets.resolve("http://localhost:3000/content/site/en.html", null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("http://localhost:3000 is not one of the configured AEM instances")
                .hasMessageContaining("instance=publish");

        PageTarget target = targets.resolve("http://localhost:3000/content/site/en.html?debug=1#top", "publish");
        assertThat(target.devServer()).isTrue();
        assertThat(target.renderUrl()).isEqualTo("http://localhost:3000/content/site/en.html?debug=1#top");
        assertThat(target.origin()).isEqualTo("http://localhost:3000");
        assertThat(target.requestPath()).isEqualTo("/content/site/en.html?debug=1");
        assertThat(target.instance().name()).isEqualTo("publish");
        assertThat(target.pagePath()).isEqualTo("/content/site/en");
        assertThat(target.describe()).isEqualTo("dev server http://localhost:3000 -> publish (instance argument)");
    }

    @Test
    void theInstanceOfADevServerIsRememberedAcrossServers() {
        targets(config()).resolve("http://localhost:3000/", "publish");

        PageTargets later = targets(config());
        PageTarget target = later.resolve("http://127.0.0.1:3000/api/profile", null);
        assertThat(target.instance().name()).isEqualTo("publish");
        assertThat(target.instanceSource()).isEqualTo("remembered");
        assertThat(target.renderUrl()).isEqualTo("http://127.0.0.1:3000/api/profile");
        assertThat(target.pagePath()).isNull();
        assertThat(later.devServers().all()).containsOnlyKeys("http://localhost:3000");

        PageTarget switched = later.resolve("http://localhost:3000/", "author");
        assertThat(switched.instance().name()).isEqualTo("author");
        assertThat(targets(config()).resolve("http://localhost:3000/", null).instance().name()).isEqualTo("author");
    }

    @Test
    void configuredDevServersNeedNoInstanceArgument() {
        Config config = Config.builder().authorUrl("http://localhost:4502").publishUrl("http://localhost:4503")
                .artifactsDir(temp).devServer("http://localhost:5173", "author").build();
        PageTarget target = targets(config).resolve("http://localhost:5173/content/site/en.html", null);
        assertThat(target.instance().name()).isEqualTo("author");
        assertThat(target.instanceSource()).isEqualTo("AEM_DEV_SERVERS");
        assertThat(target.describe()).endsWith("(from AEM_DEV_SERVERS)");
    }

    @Test
    void remoteHostsAndUrlCredentialsAreRefused() {
        PageTargets targets = targets(config());
        assertThatThrownBy(() -> targets.resolve("https://www.example.com/content/site/en.html", "publish"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> targets.resolve("http://admin:s3cret@localhost:4502/content/site/en.html", null))
                .isInstanceOf(ToolException.class).hasMessageContaining("credentials")
                .hasMessageNotContaining("s3cret");
        assertThat(new DevServers(temp, Map.of()).all()).isEmpty();
    }
}
