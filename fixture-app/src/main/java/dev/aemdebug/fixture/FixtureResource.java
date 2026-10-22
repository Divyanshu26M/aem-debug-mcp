package dev.aemdebug.fixture;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import java.util.HashMap;
import java.util.Map;

/** Resource implementation backed by a map, mimicking a JCR resource. */
public final class FixtureResource implements Resource {

    private final String path;
    private final String resourceType;
    private final FixtureValueMap properties;

    public FixtureResource(String path, String resourceType, Map<String, Object> properties) {
        this.path = path;
        this.resourceType = resourceType;
        this.properties = new FixtureValueMap(properties);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }

    @Override
    public ValueMap getValueMap() {
        return properties;
    }

    @Override
    public String toString() {
        return "FixtureResource{" + path + "}";
    }

    /** ValueMap over a HashMap. */
    static final class FixtureValueMap extends HashMap<String, Object> implements ValueMap {
        private static final long serialVersionUID = 1L;

        FixtureValueMap(Map<String, Object> values) {
            super(values);
        }

        @Override
        public <T> T get(String name, Class<T> type) {
            Object value = get(name);
            return type.isInstance(value) ? type.cast(value) : null;
        }
    }
}
