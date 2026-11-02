package dev.aemdebug.aem;

import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.tools.ToolException;

import java.net.URI;
import java.util.Locale;

/**
 * Resolves the path/URL and instance arguments of the page tools. A full URL keeps its own origin: an AEM instance
 * URL selects that instance, any other local origin is a front-end dev server, rendered as given, whose requests are
 * debugged on the AEM instance it proxies to. Remote hosts are refused.
 */
public final class PageTargets {

    private final AemInstances instances;
    private final DevServers devServers;

    public PageTargets(AemInstances instances, DevServers devServers) {
        this.instances = instances;
        this.devServers = devServers;
    }

    public DevServers devServers() {
        return devServers;
    }

    public PageTarget resolve(String input, String instanceArg) {
        if (input == null || input.isBlank()) {
            throw new ToolException("A page path or URL is required, e.g. /content/mysite/us/en.html");
        }
        String value = input.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            AemInstance instance = instances.resolve(instanceArg);
            String requestPath = PageUrls.toRequestPath(value);
            return new PageTarget(instance, null, blank(instanceArg) ? "default" : "argument", requestPath,
                    instance.url(requestPath));
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new ToolException("Not a valid URL: " + value);
        }
        rejectCredentials(uri);
        HostPolicy.requireLocal(uri);
        String key = HostPolicy.originKey(uri);
        AemInstance byUrl = instanceWithOrigin(key);
        if (byUrl != null) {
            if (!blank(instanceArg) && !byUrl.name().equalsIgnoreCase(instanceArg.trim())) {
                String other = instanceArg.trim().toLowerCase(Locale.ROOT);
                throw new ToolException(value + " is " + withArticle(byUrl.name()) + " URL but instance=" + other
                        + " was given. Drop the instance argument or use " + withArticle(other) + " URL.");
            }
            String requestPath = PageUrls.toRequestPath(value);
            return new PageTarget(byUrl, null, "url", requestPath, byUrl.url(requestPath));
        }
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String requestPath = rawPath + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String renderUrl = origin + requestPath + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        if (!blank(instanceArg)) {
            AemInstance instance = instances.resolve(instanceArg);
            DevServers.Mapping known = devServers.lookup(key);
            if (known == null || !"remembered".equals(known.source) || !known.instance.equals(instance.name())) {
                devServers.remember(key, instance.name());
            }
            return new PageTarget(instance, origin, "argument", requestPath, renderUrl);
        }
        DevServers.Mapping mapping = devServers.lookup(key);
        if (mapping == null) {
            throw new ToolException(origin + " is not one of the configured AEM instances (" + configured() + "). If it "
                    + "is a local front-end dev server, pass instance=publish or instance=author to say which AEM it "
                    + "proxies to: check its proxy configuration (for example setupProxy.js, webpack devServer.proxy, "
                    + "vite server.proxy or the \"proxy\" field of package.json) or ask the user. The answer is "
                    + "remembered for later calls and chats.");
        }
        return new PageTarget(instances.resolve(mapping.instance), origin, mapping.source, requestPath, renderUrl);
    }

    private AemInstance instanceWithOrigin(String key) {
        AemInstance author = instances.authorOrNull();
        if (author != null && HostPolicy.originKey(author.baseUrl()).equals(key)) {
            return author;
        }
        AemInstance publish = instances.publishOrNull();
        if (publish != null && HostPolicy.originKey(publish.baseUrl()).equals(key)) {
            return publish;
        }
        return null;
    }

    private String configured() {
        StringBuilder sb = new StringBuilder();
        if (instances.authorOrNull() != null) {
            sb.append("author ").append(instances.authorOrNull().baseUrl());
        }
        if (instances.publishOrNull() != null) {
            sb.append(sb.length() == 0 ? "" : ", ").append("publish ").append(instances.publishOrNull().baseUrl());
        }
        return sb.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Passwords never go through tool arguments, so URLs with user:password@ are refused. */
    public static void rejectCredentials(URI uri) {
        if (uri.getRawUserInfo() != null) {
            throw new ToolException("URLs with credentials (user:password@) are not accepted. The user signs in in "
                    + "the login window instead: aem_auth action=login (AEM) or action=site-login (site visitor).");
        }
    }

    /** "an author" or "a publish". */
    private static String withArticle(String instanceName) {
        return (instanceName.startsWith("a") ? "an " : "a ") + instanceName;
    }
}
