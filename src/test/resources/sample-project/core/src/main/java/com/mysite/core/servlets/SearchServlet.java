package com.mysite.core.servlets;

import javax.servlet.Servlet;

import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.osgi.service.component.annotations.Component;

@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/mysite/search",
        ServletResolverConstants.SLING_SERVLET_METHODS + "=POST"
})
public class SearchServlet extends SlingAllMethodsServlet {
    private static final long serialVersionUID = 1L;
}
