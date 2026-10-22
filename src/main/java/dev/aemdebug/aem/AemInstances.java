package dev.aemdebug.aem;

import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolException;

import java.util.Locale;

/** Resolves the instance argument ("author" / "publish") of tools to a configured instance. */
public final class AemInstances {

    private final Config config;
    private final AemInstance author;
    private final AemInstance publish;

    public AemInstances(Config config) {
        this.config = config;
        this.author = config.authorUrl() == null ? null : new AemInstance("author", config.authorUrl());
        this.publish = config.publishUrl() == null ? null : new AemInstance("publish", config.publishUrl());
    }

    public AemInstance author() {
        if (author == null) {
            throw new ToolException("AEM author URL is not usable: " + String.join("; ", config.problems()));
        }
        return author;
    }

    public AemInstance publish() {
        if (publish == null) {
            throw new ToolException("AEM publish URL is not usable: " + String.join("; ", config.problems()));
        }
        return publish;
    }

    public AemInstance resolve(String name) {
        if (name == null || name.isBlank()) {
            return author();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("author")) {
            return author();
        }
        if (normalized.equals("publish")) {
            return publish();
        }
        throw new ToolException("instance must be 'author' or 'publish' (was '" + name + "')");
    }

    public AemInstance authorOrNull() {
        return author;
    }

    public AemInstance publishOrNull() {
        return publish;
    }
}
