package dev.aemdebug.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import dev.aemdebug.testsupport.FakeAem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Drives the shaded jar over stdio with the MCP Java client against a fake AEM. */
class StdioServerIT {

    static final String BUNDLES = "{\"status\":\"x\",\"data\":["
            + "{\"id\":0,\"name\":\"System Bundle\",\"fragment\":false,\"stateRaw\":32,\"state\":\"Active\","
            + "\"version\":\"7.0.5\",\"symbolicName\":\"org.apache.felix.framework\"},"
            + "{\"id\":1,\"name\":\"Core\",\"fragment\":false,\"stateRaw\":2,\"state\":\"Installed\","
            + "\"version\":\"1.0.0\",\"symbolicName\":\"com.mysite.core\"},"
            + "{\"id\":2,\"name\":\"Other\",\"fragment\":false,\"stateRaw\":32,\"state\":\"Active\","
            + "\"version\":\"1.0.0\",\"symbolicName\":\"other\"}]}";

    static Path workDir(String name) throws java.io.IOException {
        String base = System.getProperty("aemdebug.itWork", "target/it-work");
        Path dir = Paths.get(base, name + "-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        return dir;
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent) {
                sb.append(((McpSchema.TextContent) content).text()).append('\n');
            }
        }
        return sb.toString();
    }

    static McpSyncClient startClient(String jar, Map<String, String> env) {
        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters params = ServerParameters.builder(javaExe).args("-jar", jar).env(env).build();
        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(90)).build();
    }

    @Test
    void listsToolsAndAnswersStatusOverStdio() throws Exception {
        String jar = System.getProperty("aemdebug.jar");
        assumeTrue(jar != null && Files.exists(Paths.get(jar)), "shaded jar not built");
        Path temp = workDir("status");
        Path slingHome = temp.resolve("crx-quickstart");
        Files.createDirectories(slingHome.resolve("logs"));
        Files.writeString(slingHome.resolve("logs").resolve("error.log"),
                "22.10.2026 17:19:00.945 *INFO* [main] org.example Started\n");
        try (FakeAem fake = FakeAem.start()) {
            fake.json("/system/console/bundles.json", BUNDLES);
            fake.text("/system/console/status-slingsettings.txt",
                    "Sling ID = abc\nSling Home = " + slingHome + "\nRun Modes = [author, crx3]\n");
            Map<String, String> env = new HashMap<>();
            env.put("AEM_AUTHOR_URL", fake.baseUrl());
            env.put("AEM_PUBLISH_URL", "http://127.0.0.1:1");
            env.put("AEM_USER", "admin");
            env.put("AEM_PASSWORD", "admin");
            env.put("AEM_JDWP_PORT", "1");
            env.put("AEM_DEBUG_ARTIFACTS_DIR", temp.resolve("artifacts").toString());
            try (McpSyncClient client = startClient(jar, env)) {
                client.initialize();
                List<McpSchema.Tool> tools = client.listTools().tools();
                List<String> names = new ArrayList<>();
                for (McpSchema.Tool tool : tools) {
                    names.add(tool.name());
                    assertThat(tool.description()).as(tool.name()).isNotBlank();
                    assertThat(tool.inputSchema()).as(tool.name()).containsEntry("type", "object");
                }
                assertThat(names).containsExactlyInAnyOrder("aem_status", "aem_auth", "aem_get_resource", "aem_query",
                        "aem_get_model_json", "aem_page_context", "aem_get_osgi_config", "aem_osgi_status",
                        "aem_resolve", "aem_logs", "aem_recent_requests", "aem_debug_toggle", "page_render",
                        "page_component_map", "component_inspect", "project_index", "jvm_connection",
                        "jvm_breakpoint", "jvm_exception_watch", "jvm_snapshots", "jvm_classes", "debug_page",
                        "debug_capture", "debug_cleanup");

                McpSchema.CallToolResult status = client.callTool(McpSchema.CallToolRequest.builder("aem_status")
                        .arguments(Map.of("instance", "author")).build());
                String statusText = text(status);
                assertThat(status.isError()).as(statusText).isFalse();
                assertThat(statusText).contains("logged in as admin").contains("3 total, 1 not active")
                        .contains("run modes: author, crx3").contains("error.log");
                assertThat(statusText).doesNotContain("admin\",\"j_password");

                McpSchema.CallToolResult auth = client.callTool(McpSchema.CallToolRequest.builder("aem_auth")
                        .arguments(Map.of("action", "status")).build());
                assertThat(text(auth)).contains("logged in as admin");

                McpSchema.CallToolResult jvm = client.callTool(McpSchema.CallToolRequest.builder("jvm_connection")
                        .arguments(Map.of("action", "status")).build());
                assertThat(jvm.isError()).as(text(jvm)).isFalse();
                assertThat(text(jvm)).contains("Not attached").contains("Idle auto-detach");

                McpSchema.CallToolResult snapshots = client.callTool(McpSchema.CallToolRequest.builder("jvm_snapshots")
                        .arguments(Map.of()).build());
                assertThat(snapshots.isError()).as(text(snapshots)).isFalse();
                assertThat(text(snapshots)).contains("0 of 0 stored snapshots match");

                McpSchema.CallToolResult cleanup = client.callTool(McpSchema.CallToolRequest.builder("debug_cleanup")
                        .arguments(Map.of()).build());
                assertThat(cleanup.isError()).as(text(cleanup)).isFalse();
                assertThat(text(cleanup)).contains("Debug cleanup");

                List<String> templates = new ArrayList<>();
                for (McpSchema.ResourceTemplate template : client.listResourceTemplates().resourceTemplates()) {
                    templates.add(template.uriTemplate());
                }
                assertThat(templates).contains("aemdbg://reports/{id}/{name}");
                McpSchema.ReadResourceResult index = client.readResource(
                        McpSchema.ReadResourceRequest.builder(ReportResources.INDEX_URI).build());
                assertThat(((McpSchema.TextResourceContents) index.contents().get(0)).text())
                        .contains("aem-debug-mcp reports");
            }
        }
    }

    @Test
    void exitsWhenStdinIsClosed() throws Exception {
        String jar = System.getProperty("aemdebug.jar");
        assumeTrue(jar != null && Files.exists(Paths.get(jar)), "shaded jar not built");
        Path temp = workDir("eof");
        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(javaExe, "-jar", jar);
        builder.environment().put("AEM_DEBUG_ARTIFACTS_DIR", temp.toString());
        builder.redirectErrorStream(false);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            Thread.sleep(1500);
            assertThat(process.isAlive()).as("server should wait for MCP input").isTrue();
            process.getOutputStream().close();
            boolean exited = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(exited).as("server exits after stdin EOF").isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .as("nothing but MCP messages may be written to stdout").isEmpty();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
