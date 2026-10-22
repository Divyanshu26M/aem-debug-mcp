package dev.aemdebug.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private static final class EchoTool extends AbstractTool {
        EchoTool() {
            super("echo_tool", "Echo", "Echoes the text argument",
                    Schema.object().requiredStr("text", "Text to echo").integer("times", "Repeat", 1, 5));
        }

        @Override
        public ToolResult call(ToolContext context, Args args) {
            String text = args.requireStr("text");
            if (text.equals("boom")) {
                throw new IllegalStateException("kaput");
            }
            if (text.equals("tool-error")) {
                throw new ToolException("nice message for the agent");
            }
            return ToolResult.of(text.repeat(args.integer("times", 1)) + " password=hunter2");
        }
    }

    @Test
    void buildsSpecificationsWithSchemasAndAnnotations() {
        ToolRegistry registry = new ToolRegistry(new ActivityTracker()).register(new EchoTool());
        List<McpServerFeatures.SyncToolSpecification> specs = registry.specifications();
        assertThat(specs).hasSize(1);
        McpSchema.Tool tool = specs.get(0).tool();
        assertThat(tool.name()).isEqualTo("echo_tool");
        assertThat(tool.description()).isEqualTo("Echoes the text argument");
        assertThat(tool.inputSchema()).containsEntry("type", "object");
        assertThat(tool.inputSchema().get("required")).isEqualTo(List.of("text"));
        assertThat(tool.annotations().readOnlyHint()).isTrue();
    }

    @Test
    void mapsResultsAndErrors() {
        ToolRegistry registry = new ToolRegistry(new ActivityTracker()).register(new EchoTool());
        ToolResult ok = registry.invoke("echo_tool", ToolContext.detached(), Map.of("text", "ab", "times", "2"));
        assertThat(ok.isError()).isFalse();
        assertThat(ok.allText()).startsWith("abab");

        McpSchema.CallToolResult wire = ok.toMcp(1000);
        assertThat(wire.isError()).isFalse();
        String wireText = ((McpSchema.TextContent) wire.content().get(0)).text();
        assertThat(wireText).contains("abab").contains("password=****").doesNotContain("hunter2");

        ToolResult unexpected = registry.invoke("echo_tool", ToolContext.detached(), Map.of("text", "boom"));
        assertThat(unexpected.isError()).isTrue();
        assertThat(unexpected.allText()).contains("IllegalStateException").contains("kaput");

        ToolResult friendly = registry.invoke("echo_tool", ToolContext.detached(), Map.of("text", "tool-error"));
        assertThat(friendly.isError()).isTrue();
        assertThat(friendly.allText()).isEqualTo("nice message for the agent");

        ToolResult missing = registry.invoke("echo_tool", ToolContext.detached(), Map.of());
        assertThat(missing.allText()).contains("Missing required argument 'text'");

        assertThat(registry.invoke("nope", ToolContext.detached(), Map.of()).isError()).isTrue();
    }

    @Test
    void imagesAreBase64Encoded() {
        McpSchema.CallToolResult wire = ToolResult.of("x").image(new byte[] {1, 2, 3}, "image/jpeg").toMcp(100);
        assertThat(wire.content()).hasSize(2);
        McpSchema.ImageContent image = (McpSchema.ImageContent) wire.content().get(1);
        assertThat(image.mimeType()).isEqualTo("image/jpeg");
        assertThat(image.data()).isEqualTo("AQID");
    }
}
