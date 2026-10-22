package dev.aemdebug.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Small JSON facade over Jackson 2. Values are handled as plain {@code Map}/{@code List}/scalar trees so that
 * the rest of the code base does not depend on Jackson types.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Parses any JSON document into Map/List/String/Number/Boolean/null. */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("Expected a JSON object but got " + typeName(value));
    }

    public static List<Object> parseArray(String json) {
        Object value = parse(json);
        if (value instanceof List<?>) {
            return asList(value);
        }
        throw new IllegalArgumentException("Expected a JSON array but got " + typeName(value));
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize value of type " + typeName(value), e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize value of type " + typeName(value), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        if (value instanceof Collection<?>) {
            return new ArrayList<>((Collection<Object>) value);
        }
        return List.of();
    }

    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static long asLong(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return defaultValue;
    }

    /** Navigates nested maps, e.g. {@code at(root, "data", "0", "name")}; list indexes are given as numbers. */
    public static Object at(Object root, String... path) {
        Object current = root;
        for (String segment : path) {
            if (current instanceof Map<?, ?>) {
                current = ((Map<?, ?>) current).get(segment);
            } else if (current instanceof List<?>) {
                List<?> list = (List<?>) current;
                try {
                    int index = Integer.parseInt(segment);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    public static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }
}
