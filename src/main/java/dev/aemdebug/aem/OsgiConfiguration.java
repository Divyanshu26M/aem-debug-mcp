package dev.aemdebug.aem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One OSGi configuration (PID, factory PID and properties). */
public final class OsgiConfiguration {

    public final String pid;
    public final String factoryPid;
    public final String bundleLocation;
    public final Map<String, Object> properties;
    /** Where the data came from (configMgr JSON or Configurations status printer). */
    public final String source;

    public OsgiConfiguration(String pid, String factoryPid, String bundleLocation, Map<String, Object> properties,
                             String source) {
        this.pid = pid;
        this.factoryPid = factoryPid;
        this.bundleLocation = bundleLocation;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.source = source;
    }

    public List<String> propertyNames() {
        return new ArrayList<>(properties.keySet());
    }
}
