package org.apache.sling.api.resource;

import java.util.Map;

/** Minimal stand-in for the Sling ValueMap API (fixture only). */
public interface ValueMap extends Map<String, Object> {

    <T> T get(String name, Class<T> type);
}
