package dev.aemdebug.tools.impl;

import dev.aemdebug.config.Config;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.Tool;
import dev.aemdebug.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the plugin's aem-debugger agent in sync with the MCP server's tools. */
class PluginAgentTest {

    private static final Path PLUGIN = Paths.get(System.getProperty("basedir", ".")).resolve("plugin");
    private static final Path AGENT = PLUGIN.resolve("com.github.copilot/agents/aem-debugger.agent.md");

    @Test
    void agentGetsEveryDebugToolExceptTheToggleAndNoWriteTools() throws IOException {
        List<String> lines = Files.readAllLines(AGENT, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("---");
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        assertThat(end).as("closing front matter delimiter").isPositive();
        List<String> frontMatter = lines.subList(1, end);

        assertThat(frontMatter).contains("name: aem-debugger");
        assertThat(frontMatter).anySatisfy(line -> assertThat(line).startsWith("description: ").hasSizeGreaterThan(80));
        List<String> tools = new ArrayList<>();
        boolean inTools = false;
        for (String line : frontMatter) {
            if (line.equals("tools:")) {
                inTools = true;
            } else if (inTools && line.startsWith("  - ")) {
                tools.add(line.substring(4).trim());
            } else {
                inTools = false;
            }
        }
        assertThat(tools).contains("read", "search")
                .doesNotContain("edit", "execute", "shell", "bash", "powershell", "*", "aem-debug/*");

        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) Json.parseObject(
                Files.readString(PLUGIN.resolve("mcp.json"), StandardCharsets.UTF_8)).get("mcpServers");
        assertThat(servers).as("agent tool prefix must match the MCP server name").containsKey("aem-debug");

        Set<String> agentTools = new TreeSet<>();
        for (String tool : tools) {
            if (tool.startsWith("aem-debug/")) {
                agentTools.add(tool.substring("aem-debug/".length()));
            }
        }
        Config config = Config.builder().artifactsDir(Paths.get("target", "agent-test").toAbsolutePath()).build();
        Set<String> serverTools = new TreeSet<>();
        for (Tool tool : ToolFactory.createRegistry(new Services(config)).tools()) {
            serverTools.add(tool.name());
        }
        assertThat(serverTools).containsAll(agentTools);
        Set<String> missing = new TreeSet<>(serverTools);
        missing.removeAll(agentTools);
        assertThat(missing).as("server tools the agent cannot use").containsExactly("aem_debug_toggle");

        String body = String.join("\n", lines.subList(end + 1, lines.size()));
        assertThat(body.length()).isLessThan(30_000);
        assertThat(body).contains("debug_cleanup");
    }
}
