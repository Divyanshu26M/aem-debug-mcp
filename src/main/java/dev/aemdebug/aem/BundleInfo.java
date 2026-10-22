package dev.aemdebug.aem;

/** A bundle as reported by /system/console/bundles.json. */
public final class BundleInfo {

    public final long id;
    public final String symbolicName;
    public final String name;
    public final String version;
    public final String state;
    public final int stateRaw;
    public final boolean fragment;

    public BundleInfo(long id, String symbolicName, String name, String version, String state, int stateRaw,
                      boolean fragment) {
        this.id = id;
        this.symbolicName = symbolicName;
        this.name = name;
        this.version = version;
        this.state = state;
        this.stateRaw = stateRaw;
        this.fragment = fragment;
    }

    /** Active bundles and resolved fragments are healthy. */
    public boolean healthy() {
        if (fragment) {
            return stateRaw == 4 || "Fragment".equalsIgnoreCase(state) || "Resolved".equalsIgnoreCase(state);
        }
        return stateRaw == 32 || "Active".equalsIgnoreCase(state);
    }

    @Override
    public String toString() {
        return "[" + id + "] " + symbolicName + " " + version + " (" + state + ")";
    }
}
