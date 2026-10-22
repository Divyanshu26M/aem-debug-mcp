package dev.aemdebug.jdi;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * Describes the class loader of a loaded class. For Apache Felix bundle class loaders it reads (without invoking
 * code) BundleClassLoader.m_wiring.m_revision to get the bundle symbolic name, version and revision id
 * ("bundleId.revision"), which identifies stale class versions after redeploys.
 */
final class BundleInfoResolver {

    /** Loader description. */
    static final class LoaderInfo {
        String bundleId;
        String revision;
        String symbolicName;
        String version;
        String loaderType;

        String describe() {
            if (symbolicName != null) {
                return "bundle " + (bundleId == null ? "?" : bundleId) + " " + symbolicName
                        + (version == null ? "" : " " + version) + (revision == null ? "" : " (revision " + revision + ")");
            }
            return loaderType == null ? "bootstrap class loader" : loaderType;
        }

        int revisionNumber() {
            if (revision == null || !revision.contains(".")) {
                return 0;
            }
            try {
                return Integer.parseInt(revision.substring(revision.indexOf('.') + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private BundleInfoResolver() {
    }

    static LoaderInfo resolve(ClassLoaderReference loader) {
        LoaderInfo info = new LoaderInfo();
        if (loader == null) {
            return info;
        }
        try {
            info.loaderType = ValueRenderer.shortName(loader.referenceType().name()) + "@" + loader.uniqueID();
            ObjectReference wiring = ref(ValueRenderer.field(loader, "m_wiring"));
            ObjectReference revision = wiring == null ? null : ref(ValueRenderer.field(wiring, "m_revision"));
            if (revision == null) {
                return info;
            }
            info.symbolicName = string(ValueRenderer.field(revision, "m_symbolicName"));
            info.revision = string(ValueRenderer.field(revision, "m_id"));
            if (info.revision != null) {
                info.bundleId = info.revision.contains(".") ? info.revision.substring(0, info.revision.indexOf('.'))
                        : info.revision;
            }
            ObjectReference version = ref(ValueRenderer.field(revision, "m_version"));
            if (version != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(intValue(ValueRenderer.field(version, "major"))).append('.')
                        .append(intValue(ValueRenderer.field(version, "minor"))).append('.')
                        .append(intValue(ValueRenderer.field(version, "micro")));
                String qualifier = string(ValueRenderer.field(version, "qualifier"));
                if (qualifier != null && !qualifier.isEmpty()) {
                    sb.append('.').append(qualifier);
                }
                info.version = sb.toString();
            }
        } catch (RuntimeException e) {
            return info;
        }
        return info;
    }

    private static ObjectReference ref(Value value) {
        return value instanceof ObjectReference && !(value instanceof StringReference) ? (ObjectReference) value : null;
    }

    private static String string(Value value) {
        return value instanceof StringReference ? ((StringReference) value).value() : null;
    }

    private static int intValue(Value value) {
        return value instanceof IntegerValue ? ((IntegerValue) value).value() : 0;
    }
}
