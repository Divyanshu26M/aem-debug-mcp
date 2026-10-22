package dev.aemdebug.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fluent builder for JSON Schema (2020-12 compatible) tool input schemas. */
public final class Schema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private Schema() {
    }

    public static Schema object() {
        return new Schema();
    }

    public Schema str(String name, String description) {
        return add(name, property("string", description), false);
    }

    public Schema requiredStr(String name, String description) {
        return add(name, property("string", description), true);
    }

    public Schema enumStr(String name, String description, String... values) {
        Map<String, Object> property = property("string", description);
        property.put("enum", Arrays.asList(values));
        return add(name, property, false);
    }

    public Schema requiredEnum(String name, String description, String... values) {
        Map<String, Object> property = property("string", description);
        property.put("enum", Arrays.asList(values));
        return add(name, property, true);
    }

    public Schema integer(String name, String description, int minimum, int maximum) {
        Map<String, Object> property = property("integer", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return add(name, property, false);
    }

    public Schema bool(String name, String description) {
        return add(name, property("boolean", description), false);
    }

    public Schema strArray(String name, String description) {
        Map<String, Object> property = property("array", description);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        property.put("items", items);
        return add(name, property, false);
    }

    public Schema objArray(String name, String description, Map<String, Object> itemSchema) {
        Map<String, Object> property = property("array", description);
        property.put("items", itemSchema);
        return add(name, property, false);
    }

    /** Free-form object argument (string values expected). */
    public Schema freeObject(String name, String description) {
        Map<String, Object> property = property("object", description);
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("type", "string");
        property.put("additionalProperties", additional);
        return add(name, property, false);
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    private Schema add(String name, Map<String, Object> property, boolean isRequired) {
        properties.put(name, property);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        if (description != null) {
            property.put("description", description);
        }
        return property;
    }
}
