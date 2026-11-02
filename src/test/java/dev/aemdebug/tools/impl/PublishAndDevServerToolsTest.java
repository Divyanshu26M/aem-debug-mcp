package dev.aemdebug.tools.impl;

import dev.aemdebug.config.Config;
import dev.aemdebug.jdi.JdiSupport;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Publish JVM debugging and dev-server targets at the tool level, without AEM or a browser. */
class PublishAndDevServerToolsTest {

    @TempDir
    Path temp;

    private Services services;

    private ToolRegistry registry() {
        Config config = Config.builder().authorUrl("http://127.0.0.1:1").publishUrl("http://127.0.0.1:2")
                .jdwp("127.0.0.1", 1).artifactsDir(temp).build();
        services = new Services(config);
        return ToolFactory.createRegistry(services);
    }

    @AfterEach
    void tearDown() {
        if (services != null) {
            services.cleanup.runAll(ToolContext.detached(), true);
        }
    }

    private static ToolResult call(ToolRegistry registry, String tool, Map<String, Object> arguments) {
        return registry.invoke(tool, ToolContext.detached(), arguments);
    }

    @Test
    void publishHasItsOwnJvmEngineAndNeedsItsOwnPort() {
        assumeTrue(JdiSupport.available(), "jdk.jdi not available");
        ToolRegistry registry = registry();

        String status = call(registry, "jvm_connection", Map.of("action", "status", "instance", "publish")).allText();
        assertThat(status).contains("### publish JVM (publish (no JDWP port))")
                .contains("No JDWP port is configured for publish")
                .contains("AEM_PUBLISH_JDWP_PORT=8768").contains("port=8768");

        ToolResult breakpoint = call(registry, "jvm_breakpoint",
                Map.of("location", "com.example.core.models.TeaserModel:41", "instance", "publish"));
        assertThat(breakpoint.isError()).as(breakpoint.allText()).isFalse();
        assertThat(breakpoint.allText()).contains("set in the publish JVM")
                .contains("Not attached to the publish JVM").contains("instance=publish");
        assertThat(services.jdi("publish").breakpointList()).hasSize(1);
        assertThat(services.jdi("author").breakpointList()).isEmpty();
        assertThat(call(registry, "jvm_breakpoint", Map.of("action", "list")).allText())
                .contains("No breakpoints.");

        assertThat(call(registry, "jvm_snapshots", Map.of("instance", "publish")).allText())
                .contains("0 of 0 stored snapshots match");

        ToolResult attach = call(registry, "jvm_connection",
                Map.of("action", "attach", "instance", "publish", "port", 1, "timeoutSeconds", 1));
        assertThat(attach.isError()).isTrue();
        assertThat(services.jdwpPort("publish")).isEqualTo(1);
        assertThat(services.jdwpPort("author")).isEqualTo(1);

        String cleanup = call(registry, "debug_cleanup", Map.of()).allText();
        assertThat(cleanup).contains("JVM debugging (publish): removed 1 breakpoint");
        assertThat(services.jdi("publish").breakpointList()).isEmpty();
        assertThat(call(registry, "debug_cleanup", Map.of()).allText()).doesNotContain("(publish)")
                .contains("JVM debugging (author): removed 0 breakpoints");
    }

    @Test
    void devServerUrlsAreNeverRenderedOnAnInstance() {
        ToolRegistry registry = registry();
        ToolResult render = call(registry, "page_render", Map.of("path", "http://localhost:3000/content/site/en.html"));
        assertThat(render.isError()).isTrue();
        assertThat(render.allText()).contains("http://localhost:3000 is not one of the configured AEM instances")
                .contains("instance=publish").doesNotContain("4502");

        ToolResult mismatch = call(registry, "debug_page",
                Map.of("path", "http://127.0.0.1:2/content/site/en.html", "instance", "author"));
        assertThat(mismatch.isError()).isTrue();
        assertThat(mismatch.allText()).contains("is a publish URL but instance=author");

        ToolResult remote = call(registry, "page_render",
                Map.of("path", "https://www.example.com/content/site/en.html", "instance", "publish"));
        assertThat(remote.isError()).isTrue();
        assertThat(services.devServers.all()).isEmpty();

        call(registry, "aem_resolve", Map.of("url", "http://localhost:3000/api/profile", "instance", "publish"));
        assertThat(services.devServers.lookup("http://localhost:3000").instance).isEqualTo("publish");
        String status = call(registry, "aem_status", Map.of("instance", "publish", "checkJdwp", false)).allText();
        assertThat(status).contains("### Front-end dev servers").contains("http://localhost:3000 -> publish (remembered)")
                .contains("### Site session").contains("Site session: none");
    }

    @Test
    void siteLoginIsLocalOnlyAndAuthStatusShowsBothIdentities() {
        ToolRegistry registry = registry();
        ToolResult remote = call(registry, "aem_auth", Map.of("action", "site-login", "url", "https://www.example.com/"));
        assertThat(remote.isError()).isTrue();
        assertThat(remote.allText()).containsIgnoringCase("local");

        assertThat(call(registry, "aem_auth", Map.of("action", "site-logout")).allText())
                .contains("There was no site session.");

        String status = call(registry, "aem_auth", Map.of("action", "status", "instance", "publish")).allText();
        assertThat(status).contains("AEM publish (http://127.0.0.1:2): not logged in")
                .contains("kept in memory only (AEM_DEBUG_REMEMBER_LOGINS=false)")
                .contains("Site session: none");
    }
}
