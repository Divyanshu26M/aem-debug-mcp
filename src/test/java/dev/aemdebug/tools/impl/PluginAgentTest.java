package dev.aemdebug.tools.impl;

import dev.aemdebug.config.Config;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.Tool;
import dev.aemdebug.util.Json;
import org.junit.jupiter.api.BeforeAll;
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

/** Keeps the plugin's aem-debugger agents (Copilot, Claude Code, Antigravity) in sync with the MCP server's tools. */
class PluginAgentTest {

    private static final Path PLUGIN = Paths.get(System.getProperty("basedir", ".")).resolve("plugin");
    private static final Path COPILOT_AGENT = PLUGIN.resolve("com.github.copilot/agents/aem-debugger.agent.md");
    private static final Path CLAUDE_AGENT = PLUGIN.resolve("claude-code/agents/aem-debugger.md");
    private static final Path ANTIGRAVITY_AGENT = PLUGIN.resolve("agents/aem-debugger.md");
    private static final String SERVER = "aem-debug";
    private static final String TOGGLE = "aem_debug_toggle";

    /** The server's tools minus the toggle: what the Copilot and Claude Code agents list one by one. */
    private static Set<String> agentDebugTools;

    @BeforeAll
    static void readServerTools() {
        Config config = Config.builder().artifactsDir(Paths.get("target", "agent-test").toAbsolutePath()).build();
        Set<String> serverTools = new TreeSet<>();
        for (Tool tool : ToolFactory.createRegistry(new Services(config)).tools()) {
            serverTools.add(tool.name());
        }
        assertThat(serverTools).contains(TOGGLE);
        serverTools.remove(TOGGLE);
        agentDebugTools = serverTools;
    }

    @Test
    void copilotAgentGetsEveryDebugToolExceptTheToggleAndNoWriteTools() throws IOException {
        Agent agent = Agent.read(COPILOT_AGENT);
        assertThat(agent.field("name")).isEqualTo("aem-debugger");
        assertThat(agent.field("description")).hasSizeGreaterThan(80);
        assertThat(agent.tools).contains("read", "search")
                .doesNotContain("edit", "execute", "shell", "bash", "powershell", "*", SERVER + "/*");
        assertThat(mcpServers("mcp.json")).as("agent tool prefix must match the MCP server name").containsKey(SERVER);
        assertThat(agent.toolsWithPrefix(SERVER + "/")).as("debug tools of the Copilot agent")
                .containsExactlyInAnyOrderElementsOf(agentDebugTools);
        assertThat(agent.body.length()).isLessThan(30_000);
        assertThat(agent.body).contains("debug_cleanup");
    }

    @Test
    void claudeCodeAgentGetsEveryDebugToolExceptTheToggleAndNoWriteTools() throws IOException {
        Agent agent = Agent.read(CLAUDE_AGENT);
        String prefix = "mcp__" + SERVER + "__";
        assertThat(agent.tools).contains("Read", "Grep", "Glob")
                .doesNotContain("Edit", "MultiEdit", "Write", "NotebookEdit", "Bash", "PowerShell", "*",
                        "mcp__" + SERVER, prefix + "*");
        assertThat(agent.toolsWithPrefix(prefix)).as("debug tools of the Claude Code agent")
                .containsExactlyInAnyOrderElementsOf(agentDebugTools);
    }

    @Test
    void antigravityAgentGetsReadOnlyBuiltInToolsAndTheServerThroughInheritMcp() throws IOException {
        Agent agent = Agent.read(ANTIGRAVITY_AGENT);
        // Antigravity accepts only built-in tools here; inheritMcp gives the agent the MCP servers' tools.
        assertThat(agent.tools).containsExactlyInAnyOrder("view_file", "grep_search", "find_by_name", "list_dir");
        assertThat(agent.field("inheritMcp")).isEqualTo("true");
    }

    @Test
    void allAgentsShareNameDescriptionAndInstructions() throws IOException {
        Agent copilot = Agent.read(COPILOT_AGENT);
        String expectedBody = copilot.body.replace("the main Copilot conversation", "the main conversation");
        assertThat(expectedBody).as("the Copilot-only wording that the other agents leave out")
                .isNotEqualTo(copilot.body);
        for (Path path : List.of(CLAUDE_AGENT, ANTIGRAVITY_AGENT)) {
            Agent agent = Agent.read(path);
            assertThat(agent.field("name")).as("name in " + path).isEqualTo(copilot.field("name"));
            assertThat(agent.field("description")).as("description in " + path)
                    .isEqualTo(copilot.field("description"));
            assertThat(agent.body).as("instructions in " + path).isEqualTo(expectedBody);
        }
    }

    @Test
    void antigravityMcpConfigStartsTheSameServerAsMcpJson() throws IOException {
        Map<String, Object> pluginServers = mcpServers("mcp.json");
        Map<String, Object> antigravityServers = mcpServers("mcp_config.json");
        assertThat(antigravityServers).containsOnlyKeys(pluginServers.keySet()).containsKey(SERVER);
        Map<String, Object> expected = asMap(pluginServers.get(SERVER));
        Map<String, Object> actual = asMap(antigravityServers.get(SERVER));
        for (String key : List.of("command", "args", "env")) {
            assertThat(actual.get(key)).as(key).isNotNull().isEqualTo(expected.get(key));
        }
    }

    private static Map<String, Object> mcpServers(String file) throws IOException {
        String json = Files.readString(PLUGIN.resolve(file), StandardCharsets.UTF_8);
        return asMap(Json.parseObject(json).get("mcpServers"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** An agent file: YAML front matter (flat keys plus the tools list) and the instructions below it. */
    private static final class Agent {
        final List<String> frontMatter;
        final List<String> tools = new ArrayList<>();
        final String body;

        private Agent(List<String> lines) {
            assertThat(lines.get(0)).isEqualTo("---");
            int end = lines.subList(1, lines.size()).indexOf("---") + 1;
            assertThat(end).as("closing front matter delimiter").isPositive();
            frontMatter = lines.subList(1, end);
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
            body = String.join("\n", lines.subList(end + 1, lines.size()));
        }

        static Agent read(Path path) throws IOException {
            return new Agent(Files.readAllLines(path, StandardCharsets.UTF_8));
        }

        String field(String key) {
            for (String line : frontMatter) {
                if (line.startsWith(key + ": ")) {
                    return line.substring(key.length() + 2).trim();
                }
            }
            return null;
        }

        Set<String> toolsWithPrefix(String prefix) {
            Set<String> names = new TreeSet<>();
            for (String tool : tools) {
                if (tool.startsWith(prefix)) {
                    names.add(tool.substring(prefix.length()));
                }
            }
            return names;
        }
    }
}