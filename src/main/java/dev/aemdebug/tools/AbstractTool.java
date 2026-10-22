package dev.aemdebug.tools;

import dev.aemdebug.util.Schema;

import java.util.Map;

/** Convenience base class holding the static tool metadata. */
public abstract class AbstractTool implements Tool {

    private final String name;
    private final String title;
    private final String description;
    private final Map<String, Object> inputSchema;

    protected AbstractTool(String name, String title, String description, Schema schema) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.inputSchema = schema.build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }
}
