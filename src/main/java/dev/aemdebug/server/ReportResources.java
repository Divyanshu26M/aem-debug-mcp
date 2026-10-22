package dev.aemdebug.server;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import dev.aemdebug.report.ArtifactStore;
import dev.aemdebug.util.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/** MCP resources for report artifacts: aemdbg://reports/{id}/{name} and an index of recent reports. */
public final class ReportResources {

    public static final String INDEX_URI = "aemdbg://reports/index";

    private ReportResources() {
    }

    public static McpServerFeatures.SyncResourceTemplateSpecification template(ArtifactStore store) {
        McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate
                .builder(ArtifactStore.URI_PREFIX + "{id}/{name}", "report-artifact")
                .description("Artifact of an aem-debug-mcp report (page.html, screenshot-full.png, error-log.txt, "
                        + "rpt.txt, snapshots.json, ...). Report ids look like rpt-0007.")
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(template,
                (exchange, request) -> read(store, request.uri()));
    }

    public static McpServerFeatures.SyncResourceSpecification index(ArtifactStore store) {
        McpSchema.Resource resource = McpSchema.Resource.builder(INDEX_URI, "report-index")
                .description("Recent aem-debug-mcp reports and their artifacts")
                .mimeType("text/markdown")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            List<McpSchema.ResourceContents> contents = new ArrayList<>();
            contents.add(McpSchema.TextResourceContents.builder(INDEX_URI, indexText(store))
                    .mimeType("text/markdown").build());
            return McpSchema.ReadResourceResult.builder(contents).build();
        });
    }

    static McpSchema.ReadResourceResult read(ArtifactStore store, String uri) {
        String[] parts = ArtifactStore.parseUri(uri);
        byte[] data = store.read(parts[0], parts[1]);
        String mime = ArtifactStore.mimeType(parts[1]);
        List<McpSchema.ResourceContents> contents = new ArrayList<>();
        if (ArtifactStore.isText(parts[1])) {
            contents.add(McpSchema.TextResourceContents.builder(uri, new String(data, java.nio.charset.StandardCharsets.UTF_8))
                    .mimeType(mime).build());
        } else {
            contents.add(McpSchema.BlobResourceContents.builder(uri, Base64.getEncoder().encodeToString(data))
                    .mimeType(mime).build());
        }
        return McpSchema.ReadResourceResult.builder(contents).build();
    }

    static String indexText(ArtifactStore store) {
        StringBuilder sb = new StringBuilder("# aem-debug-mcp reports\n\nDirectory: ").append(store.reportsDir())
                .append("\n\n");
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(store.reportsDir())) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (Exception e) {
            return sb.append("(cannot list reports: ").append(e.getMessage()).append(")\n").toString();
        }
        dirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        int shown = 0;
        for (Path dir : dirs) {
            if (shown++ >= 20) {
                break;
            }
            String id = dir.getFileName().toString();
            sb.append("- ").append(id).append(": ");
            List<String> names = store.list(id);
            List<String> uris = new ArrayList<>();
            for (String name : names) {
                uris.add(store.uri(id, name));
            }
            sb.append(uris.isEmpty() ? "(empty)" : String.join(", ", uris)).append('\n');
        }
        if (dirs.isEmpty()) {
            sb.append("No reports yet. Run page_render or debug_page.\n");
        }
        return Text.truncate(sb.toString(), 20_000);
    }
}
