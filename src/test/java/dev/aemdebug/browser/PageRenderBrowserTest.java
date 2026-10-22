package dev.aemdebug.browser;

import dev.aemdebug.config.Config;
import dev.aemdebug.server.Services;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.tools.impl.ToolFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives page_render through Playwright and the installed Edge/Chrome ({@code mvn verify -Pbrowser-tests}). */
@Tag("browser")
class PageRenderBrowserTest {

    private static final String PAGE = "<!DOCTYPE html><html><head><title>Fixture page</title>"
            + "<script src=\"/etc.clientlibs/test/clientlibs/missing.min.js\"></script></head>"
            + "<body><h1>Hello from the fixture</h1><script>console.error('boom from page')</script></body></html>";

    @Test
    void rendersAPublishPageWithFailuresAndConsoleErrors() throws Exception {
        try (FakeAem fake = FakeAem.start()) {
            fake.requireAuth(false);
            fake.html("/content/test/page.html", PAGE);
            Config config = Config.builder()
                    .authorUrl(fake.baseUrl())
                    .publishUrl(fake.baseUrl())
                    .artifactsDir(Paths.get("target", "browser-test-artifacts").toAbsolutePath())
                    .build();
            Services services = new Services(config);
            try {
                ToolRegistry registry = ToolFactory.createRegistry(services);
                ToolResult result = registry.invoke("page_render", ToolContext.detached(),
                        Map.of("path", "/content/test/page.html", "instance", "publish"));
                String text = result.allText();
                assertThat(result.isError()).as(text).isFalse();
                assertThat(text).contains("HTTP 200").contains("Fixture page").contains("missing.min.js")
                        .contains("boom from page");
                assertThat(result.imageCount()).isEqualTo(1);
            } finally {
                services.cleanup.runAll(ToolContext.detached(), true);
            }
        }
    }
}
