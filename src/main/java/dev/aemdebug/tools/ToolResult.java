package dev.aemdebug.tools;

import io.modelcontextprotocol.spec.McpSchema;

import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** Tool output: markdown text parts and images. Converted to an MCP CallToolResult at the edge. */
public final class ToolResult {

    /** Marker for result parts. */
    public interface Part {
    }

    public static final class TextPart implements Part {
        private final String text;

        public TextPart(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public static final class ImagePart implements Part {
        private final byte[] data;
        private final String mimeType;

        public ImagePart(byte[] data, String mimeType) {
            this.data = data;
            this.mimeType = mimeType;
        }

        public byte[] data() {
            return data;
        }

        public String mimeType() {
            return mimeType;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private boolean error;

    public static ToolResult of(String text) {
        return new ToolResult().text(text);
    }

    public static ToolResult error(String text) {
        ToolResult result = new ToolResult().text(text);
        result.error = true;
        return result;
    }

    public ToolResult text(String text) {
        if (text != null && !text.isEmpty()) {
            parts.add(new TextPart(text));
        }
        return this;
    }

    public ToolResult image(byte[] data, String mimeType) {
        if (data != null && data.length > 0) {
            parts.add(new ImagePart(data, mimeType));
        }
        return this;
    }

    public ToolResult markError() {
        this.error = true;
        return this;
    }

    public boolean isError() {
        return error;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(parts);
    }

    /** All text parts joined, for tests and logging. */
    public String allText() {
        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            if (part instanceof TextPart) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(((TextPart) part).text());
            }
        }
        return sb.toString();
    }

    public int imageCount() {
        int count = 0;
        for (Part part : parts) {
            if (part instanceof ImagePart) {
                count++;
            }
        }
        return count;
    }

    /** Converts to the MCP wire type; text is masked for secrets and budgeted. */
    public McpSchema.CallToolResult toMcp(int maxTextChars) {
        List<McpSchema.Content> content = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof TextPart) {
                String text = SecretMasker.maskText(((TextPart) part).text());
                content.add(new McpSchema.TextContent(Text.truncate(text, maxTextChars)));
            } else if (part instanceof ImagePart) {
                ImagePart image = (ImagePart) part;
                String base64 = Base64.getEncoder().encodeToString(image.data());
                content.add(McpSchema.ImageContent.builder(base64, image.mimeType()).build());
            }
        }
        if (content.isEmpty()) {
            content.add(new McpSchema.TextContent("(no output)"));
        }
        return McpSchema.CallToolResult.builder().content(content).isError(error).build();
    }
}
