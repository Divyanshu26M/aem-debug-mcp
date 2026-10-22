package com.mysite.core.servlets;

import javax.servlet.Servlet;

import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

@Component(service = Servlet.class)
@SlingServletResourceTypes(
        resourceTypes = "mysite/components/page",
        methods = "GET",
        selectors = "data",
        extensions = "json")
public class PageDataServlet extends SlingSafeMethodsServlet {
    private static final long serialVersionUID = 1L;
}
