package dev.aemdebug.aem;

import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;

import java.util.Map;

/**
 * Read access to AEM for tools: author requests use (and if necessary establish) an authenticated session;
 * publish requests are tried anonymously first. 401/login redirects trigger re-authentication once.
 */
public final class AemClient {

    private final AemInstances instances;
    private final AemHttpClient http;
    private final AuthManager auth;

    public AemClient(AemInstances instances, AemHttpClient http, AuthManager auth) {
        this.instances = instances;
        this.http = http;
        this.auth = auth;
    }

    public AemInstances instances() {
        return instances;
    }

    public AemHttpClient http() {
        return http;
    }

    public AuthManager auth() {
        return auth;
    }

    public AemResponse get(ToolContext context, AemInstance instance, String pathAndQuery) {
        return get(context, instance, pathAndQuery, RequestOptions.defaults());
    }

    public AemResponse get(ToolContext context, AemInstance instance, String pathAndQuery, RequestOptions options) {
        if (instance.isAuthor() && options.sendSession() && !auth.hasSession(instance)) {
            auth.ensureSession(context, instance);
        }
        AemResponse response = http.get(instance, pathAndQuery, options);
        if (options.sendSession() && (response.isLoginRequired()
                || (response.status() == 403 && !auth.hasSession(instance)))) {
            auth.ensureSession(context, instance);
            response = http.get(instance, pathAndQuery, options);
            if (response.isLoginRequired()) {
                throw new ToolException("AEM " + instance.name() + " still requires authentication for "
                        + pathAndQuery + " after login (HTTP " + response.status() + ").");
            }
        }
        return response;
    }

    /** GET that must succeed with a JSON object body. */
    public Map<String, Object> getJsonObject(ToolContext context, AemInstance instance, String pathAndQuery) {
        AemResponse response = get(context, instance, pathAndQuery);
        requireOk(response, pathAndQuery);
        try {
            return response.jsonObject();
        } catch (IllegalArgumentException e) {
            throw new ToolException("AEM returned non-JSON content for " + pathAndQuery + ": "
                    + response.snippet(200));
        }
    }

    /** GET that must succeed with any JSON body. */
    public Object getJson(ToolContext context, AemInstance instance, String pathAndQuery) {
        AemResponse response = get(context, instance, pathAndQuery);
        requireOk(response, pathAndQuery);
        try {
            return Json.parse(response.text());
        } catch (IllegalArgumentException e) {
            throw new ToolException("AEM returned non-JSON content for " + pathAndQuery + ": "
                    + response.snippet(200));
        }
    }

    public String getText(ToolContext context, AemInstance instance, String pathAndQuery) {
        AemResponse response = get(context, instance, pathAndQuery);
        requireOk(response, pathAndQuery);
        return response.text();
    }

    public static void requireOk(AemResponse response, String what) {
        if (response.ok()) {
            return;
        }
        String hint = "";
        if (response.status() == 404) {
            hint = " (not found, or not readable by the current user)";
        } else if (response.status() == 403) {
            hint = " (forbidden for the current user; admin rights may be required)";
        } else if (response.status() >= 500) {
            hint = " (server error; check aem_logs)";
        }
        throw new ToolException("AEM answered HTTP " + response.status() + " for " + what + hint);
    }
}
