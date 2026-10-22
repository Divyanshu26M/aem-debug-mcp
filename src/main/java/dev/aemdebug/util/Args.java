package dev.aemdebug.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lenient, typed access to MCP tool arguments. Agents sometimes send numbers as strings or lists as
 * comma-separated strings, so conversions are forgiving while still producing clear errors.
 */
public final class Args {

    private final Map<String, Object> values;

    public Args(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    public static Args of(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return new Args(map);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public boolean has(String key) {
        Object value = values.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof String) {
            return !((String) value).isBlank();
        }
        return true;
    }

    public String str(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public String str(String key, String defaultValue) {
        String value = str(key);
        return value == null ? defaultValue : value;
    }

    public String requireStr(String key) {
        String value = str(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument '" + key + "'");
        }
        return value;
    }

    /** Lower-cased enum-like value validated against the allowed options. */
    public String choice(String key, String defaultValue, String... allowed) {
        String value = str(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String option : allowed) {
            if (option.equalsIgnoreCase(normalized)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be one of " + String.join(", ", allowed)
                + " but was '" + value + "'");
    }

    public Integer integer(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a number but was '" + text + "'");
        }
    }

    public int integer(String key, int defaultValue) {
        Integer value = integer(key);
        return value == null ? defaultValue : value;
    }

    public int integer(String key, int defaultValue, int min, int max) {
        int value = integer(key, defaultValue);
        return Math.max(min, Math.min(max, value));
    }

    public boolean bool(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return defaultValue;
        }
        if (text.equals("true") || text.equals("yes") || text.equals("1") || text.equals("on")) {
            return true;
        }
        if (text.equals("false") || text.equals("no") || text.equals("0") || text.equals("off")) {
            return false;
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be true or false but was '" + value + "'");
    }

    /** Accepts a JSON array or a comma/newline separated string. */
    public List<String> strList(String key) {
        Object value = values.get(key);
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        for (String part : String.valueOf(value).split("[,\\n]")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object value = values.get(key);
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Json.parseObject((String) value);
        }
        return Map.of();
    }

    /** List of objects; also accepts a JSON-encoded array string. */
    public List<Map<String, Object>> mapList(String key) {
        Object value = values.get(key);
        if (value instanceof String && !((String) value).isBlank()) {
            value = Json.parse((String) value);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                if (item instanceof Map<?, ?>) {
                    result.add(Json.asMap(item));
                } else if (item != null) {
                    Map<String, Object> wrapper = new LinkedHashMap<>();
                    wrapper.put("value", item);
                    result.add(wrapper);
                }
            }
        } else if (value instanceof Map<?, ?>) {
            result.add(Json.asMap(value));
        }
        return result;
    }

    @Override
    public String toString() {
        return String.valueOf(SecretMasker.maskTree(values));
    }
}
