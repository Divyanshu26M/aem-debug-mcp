package org.apache.sling.api.resource;

/** Minimal stand-in for the Sling Resource API (fixture only, used to test AEM-aware snapshot rendering). */
public interface Resource {

    String getPath();

    String getResourceType();

    ValueMap getValueMap();
}
