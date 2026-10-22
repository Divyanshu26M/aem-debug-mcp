package dev.aemdebug.fixture.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/** Mimics a Sling Model with a classic bug: it reads "title" although the content stores "jcr:title". */
public class HeroModel {

    private final Resource resource;
    private final String title;
    private final String subtitle;
    private int renderCount;

    public HeroModel(Resource resource) {
        this.resource = resource;
        ValueMap properties = resource.getValueMap();
        this.title = properties.get("title", String.class); // BP:hero-init
        this.subtitle = (String) properties.get("subtitle");
    }

    public String getTitle() {
        renderCount++;
        String result = title; // BP:hero-title
        return result;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public Resource getResource() {
        return resource;
    }
}
