package dev.aemdebug.mapping;

import dev.aemdebug.util.Hashing;

/**
 * Deterministic HTML ids of AEM Core Components: {@code <last segment of resourceType>-<sha256(path)[0..10]>}
 * (ComponentUtils.generateId), unless the author set an explicit "id" property.
 */
public final class CoreComponentIds {

    public static final int HASH_LENGTH = 10;

    private CoreComponentIds() {
    }

    public static String id(String resourceType, String path) {
        if (resourceType == null || path == null) {
            return null;
        }
        String prefix = resourceType.contains("/") ? resourceType.substring(resourceType.lastIndexOf('/') + 1)
                : resourceType;
        return prefix + "-" + Hashing.sha256Hex(path).substring(0, HASH_LENGTH);
    }

    /** True if the id looks like a generated Core Components id. */
    public static boolean looksGenerated(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]+-[0-9a-f]{" + HASH_LENGTH + "}");
    }
}
