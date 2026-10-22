package com.mysite.core.models;

import javax.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Exporter;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ChildResource;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * Hero component model. Example usage in Javadoc: @Model(adaptables = Resource.class)
 */
@Model(adaptables = {SlingHttpServletRequest.class, Resource.class},
        adapters = {Hero.class},
        resourceType = HeroModel.RESOURCE_TYPE,
        defaultInjectionStrategy = DefaultInjectionStrategy.REQUIRED)
@Exporter(name = "jackson", extensions = "json")
public class HeroModel implements Hero {

    static final String RESOURCE_TYPE = "mysite/components/hero";

    // the bug: content stores jcr:title
    @ValueMapValue
    private String title;

    @ValueMapValue(name = "jcr:description", injectionStrategy = InjectionStrategy.OPTIONAL)
    private String description;

    @ChildResource
    private List<Resource> items;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL)
    private Map<String, Object> settings;

    private String computed;

    @PostConstruct
    protected void init() {
        computed = title.toUpperCase();
    }

    @Override
    public String getTitle() {
        return title;
    }
}
