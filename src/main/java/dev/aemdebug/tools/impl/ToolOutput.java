package dev.aemdebug.tools.impl;

import dev.aemdebug.server.Services;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.SecretMasker;
import dev.aemdebug.util.Text;

/** Output helpers shared by tools: masked JSON blocks and spilling large text into report artifacts. */
final class ToolOutput {

    static final int INLINE_LIMIT = 12_000;

    private ToolOutput() {
    }

    static String maskedJson(Object value) {
        return Json.pretty(SecretMasker.maskTree(value));
    }

    /**
     * Returns the text inline when small; otherwise saves the full text as an artifact and returns a truncated
     * version with a pointer to the artifact.
     */
    static String inlineOrArtifact(Services s, String text, int limit, String artifactName) {
        if (text.length() <= limit) {
            return text;
        }
        String reportId = s.artifacts.newReport();
        s.artifacts.write(reportId, artifactName, text);
        return text.substring(0, limit) + "\n... [" + (text.length() - limit) + " more chars; full output: "
                + s.artifacts.uri(reportId, artifactName) + " = " + s.artifacts.resolve(reportId, artifactName) + "]";
    }

    static String fenced(String language, String body) {
        return "```" + language + "\n" + body + (body.endsWith("\n") ? "" : "\n") + "```\n";
    }

    static String oneLine(String text, int max) {
        return Text.oneLine(text, max);
    }
}
