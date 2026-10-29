package dev.aemdebug.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets (passwords, tokens, keys, cookies, AEM encrypted values) in structured values and free text
 * before anything is returned to the MCP client or written to an artifact.
 */
public final class SecretMasker {

    public static final String MASK = "****";

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(passw(or)?d|pwd|secret|token|api[_-]?key|private[_-]?key|encryption[_-]?key|signing[_-]?key"
                    + "|credential|passphrase|access[_-]?key|cookie|authorization|hmac)");

    /** AEM/Granite crypto-protected values look like {hex...}. */
    private static final Pattern ENCRYPTED_VALUE = Pattern.compile("\\{[0-9a-fA-F]{32,}\\}");

    private static final Pattern KEY_VALUE_IN_TEXT = Pattern.compile(
            "(?i)(login-token|j_password|password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
                    + "|client[_-]?secret|private[_-]?key)(\"?\\s*[=:]\\s*\"?)([^\\s\"',;&<>]+)");

    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(basic|bearer)\\s+[A-Za-z0-9+/=._~-]+");

    /** user:password@ in a URL. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)\\b(https?://)[^/\\s:@\"'<>]+:[^/\\s@\"'<>]*@");

    private SecretMasker() {
    }

    public static boolean isSecretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).find();
    }

    /** Returns a deep copy of a Map/List tree with secret values masked. */
    public static Object maskTree(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (child != null && isSecretKey(key) && !(child instanceof Map<?, ?>) && !(child instanceof Boolean)) {
                    copy.put(key, MASK);
                } else {
                    copy.put(key, maskTree(child));
                }
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(maskTree(item));
            }
            return copy;
        }
        if (value instanceof String) {
            return maskValue((String) value);
        }
        return value;
    }

    public static String maskValue(String value) {
        if (value == null) {
            return null;
        }
        String masked = URL_CREDENTIALS.matcher(value).replaceAll("$1" + MASK + "@");
        return ENCRYPTED_VALUE.matcher(masked).replaceAll("{" + MASK + "}");
    }

    /** Masks key=value style secrets, auth headers and encrypted values inside free text (logs, HTML, RPT). */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTH_HEADER.matcher(text).replaceAll("$1$2 " + MASK);
        Matcher matcher = KEY_VALUE_IN_TEXT.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + MASK));
        }
        matcher.appendTail(sb);
        return maskValue(sb.toString());
    }
}
