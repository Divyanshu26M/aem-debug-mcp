package dev.aemdebug.e2e;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.config.Config;
import dev.aemdebug.jdi.BreakpointSpec;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdwpPortProbe;
import dev.aemdebug.jdi.Snapshot;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.tools.impl.ToolFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in checks against the developer's running local AEM ({@code mvn verify -Pe2e}). Read-only: no toggles, no
 * deployments. The JVM part attaches only when the JDWP port is free, uses a single non-suspending breakpoint
 * with a request filter and detaches at the end. Authenticated checks run only with AEM_USER/AEM_PASSWORD or with
 * {@code -Daemdebug.e2e.login=true} (opens the AEM login window and waits for the user). Set AEM_E2E_PAGE (e.g.
 * /content/wknd/us/en) to also run debug_page on a real page. Findings go to target/e2e-findings.md.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalAemE2ETest {

    private static final String LOGIN_PAGE = "/libs/granite/core/content/login.html";
    private static final Map<String, String> FINDINGS = new LinkedHashMap<>();

    private static Config config;
    private static Services services;
    private static ToolRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putIfAbsent("AEM_DEBUG_ARTIFACTS_DIR", Paths.get("target", "e2e-artifacts").toAbsolutePath().toString());
        config = Config.fromEnvironment(env);
        assumeTrue(status(config.authorUrl() + LOGIN_PAGE) == 200, "local AEM author not reachable at " + config.authorUrl());
        services = new Services(config);
        registry = ToolFactory.createRegistry(services);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (services != null) {
            for (String line : services.cleanup.runAll(ToolContext.detached(), true)) {
                FINDINGS.put("cleanup: " + line, "");
            }
        }
        StringBuilder md = new StringBuilder("# E2E findings\n\n");
        for (Map.Entry<String, String> entry : FINDINGS.entrySet()) {
            md.append("## ").append(entry.getKey()).append("\n\n```\n").append(entry.getValue()).append("\n```\n\n");
        }
        Path out = Paths.get("target", "e2e-findings.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    @Test
    @Order(1)
    void statusWithoutLogin() {
        String text = call("aem_status", Map.of("instance", "author", "login", false));
        assertThat(text).contains("reachable: yes");
    }

    @Test
    @Order(2)
    void snapshotOnARealSlingRequestCarriesTheRequestThreadName() throws Exception {
        JdiEngine engine = services.jdi("author");
        assumeTrue(engine != null, "jdk.jdi not available");
        JdwpPortProbe.Result probe = JdwpPortProbe.probe(config.jdwpHost(), config.jdwpPort(), 1500);
        FINDINGS.put("JDWP probe", String.valueOf(probe));
        assumeTrue(probe.state == JdwpPortProbe.State.LISTENING, "JDWP port not free (IntelliJ attached?): " + probe);
        Thread.sleep(500);
        try {
            attach(engine);
            BreakpointSpec spec = BreakpointSpec.parse(
                    "org.apache.sling.engine.impl.SlingRequestProcessorImpl#doProcessRequest");
            spec.maxHits = 3;
            spec.frames = 6;
            spec.depth = 1;
            spec.requestFilter = LOGIN_PAGE;
            engine.addBreakpoint(spec);
            FINDINGS.put("breakpoint", spec.describe());

            assertThat(status(config.authorUrl() + LOGIN_PAGE)).isEqualTo(200);
            List<Snapshot> snapshots = engine.snapshots().list(s -> spec.id.equals(s.sourceId));
            long deadline = System.currentTimeMillis() + 10_000;
            while (snapshots.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                snapshots = engine.snapshots().list(s -> spec.id.equals(s.sourceId));
            }
            FINDINGS.put("engine status", engine.status());
            assertThat(snapshots).as(spec.describe()).isNotEmpty();
            Snapshot snapshot = snapshots.get(0);
            FINDINGS.put("snapshot", snapshot.format(6));
            assertThat(snapshot.requestKey).as(snapshot.threadName).isNotNull();
            assertThat(snapshot.requestKey.method()).isEqualTo("GET");
            assertThat(snapshot.requestKey.uri()).isEqualTo(LOGIN_PAGE);

            List<JdiEngine.ClassVersion> versions = engine.classVersions("org.apache.sling.engine.impl.SlingMainServlet");
            StringBuilder classes = new StringBuilder();
            for (JdiEngine.ClassVersion version : versions) {
                classes.append(version.name).append(" - ").append(version.loader).append('\n');
            }
            FINDINGS.put("class versions", classes.toString());
            assertThat(versions).isNotEmpty();
            assertThat(versions.get(0).bundleSymbolicName).isEqualTo("org.apache.sling.engine");
        } finally {
            engine.removeAll();
            FINDINGS.put("detach", engine.detach("e2e finished"));
        }
    }

    @Test
    @Order(3)
    void authenticatedReadsAndRender() {
        boolean interactive = Boolean.getBoolean("aemdebug.e2e.login");
        assumeTrue(config.hasCredentials() || interactive,
                "no AEM_USER/AEM_PASSWORD and interactive login not enabled (-Daemdebug.e2e.login=true)");
        AemInstance author = services.instances.author();
        services.auth.ensureSession(ToolContext.detached(), author, Math.max(120, config.loginWaitSeconds()),
                !config.hasCredentials());
        assertThat(call("aem_status", Map.of("instance", "author"))).contains("logged in as");
        call("aem_osgi_status", Map.of());
        call("aem_get_osgi_config", Map.of("pid", "com.day.cq.wcm.core.impl.WCMDebugFilter"));
        call("aem_debug_toggle", Map.of("action", "plan", "toggle", "wcm-debug-filter"));
        call("aem_recent_requests", Map.of());
        call("aem_logs", Map.of());
        call("aem_resolve", Map.of("url", LOGIN_PAGE));
        call("page_render", Map.of("path", LOGIN_PAGE, "wcmmode", "none", "screenshot", false));
        String page = System.getenv("AEM_E2E_PAGE");
        if (page != null && !page.isBlank()) {
            call("debug_page", Map.of("path", page, "screenshot", false));
        }
    }

    private static String call(String tool, Map<String, Object> arguments) {
        ToolResult result = registry.invoke(tool, ToolContext.detached(), arguments);
        String text = result.allText();
        FINDINGS.put(tool + " " + arguments, text);
        assertThat(result.isError()).as(tool + ": " + text).isFalse();
        return text;
    }

    private static void attach(JdiEngine engine) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                FINDINGS.put("attach", engine.attach(config.jdwpHost(), config.jdwpPort(), 5000));
                return;
            } catch (ToolException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(300);
            }
        }
    }

    private static int status(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
