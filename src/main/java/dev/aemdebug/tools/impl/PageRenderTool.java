package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.PageTarget;
import dev.aemdebug.aem.PageUrls;
import dev.aemdebug.browser.ImageUtil;
import dev.aemdebug.browser.NetworkEntry;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.browser.SiteSession;
import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.util.Map;

/** page_render: headless render with screenshot, failures and console errors. */
public final class PageRenderTool extends AbstractTool {

    private final Services s;

    public PageRenderTool(Services services) {
        super("page_render", "Render page",
                "Renders a page of the local AEM instance - or of a local front-end dev server that proxies to it, "
                        + "e.g. http://localhost:3000/... - in a headless browser (installed Edge/Chrome) the way a "
                        + "visitor sees it and returns a screenshot plus a compact summary: HTTP status, timings, failed "
                        + "requests (e.g. clientlib 404s) and console/JavaScript errors. Full HTML, a full-page PNG and "
                        + "the network log are saved as report artifacts. Author pages use wcmmode=disabled by default. "
                        + "Use page_component_map to see which component produced which part of the page.",
                commonRenderSchema()
                        .bool("screenshot", "Include the screenshot image in the result (default true)")
                        .bool("fullPage", "Also save a full-page PNG artifact (default true)"));
        this.s = services;
    }

    static Schema commonRenderSchema() {
        return Schema.object()
                .requiredStr("path", "Page path or local URL, e.g. /content/wknd/us/en or /content/wknd/us/en.html?x=1; "
                        + "page editor, Sites console and classic UI (cf#) URLs work too. The URL of a local front-end "
                        + "dev server (e.g. http://localhost:3000/...) is rendered as given and debugged on the AEM "
                        + "instance it proxies to")
                .enumStr("instance", "author (default) or publish. For a dev-server URL: the AEM instance that dev "
                        + "server proxies to (remembered for later calls)", "author", "publish")
                .enumStr("session", "Who the browser is: auto (default; author: the AEM login; publish and dev "
                        + "servers: the site session captured with aem_auth action=site-login if there is one, else "
                        + "anonymous), aem (AEM login of the instance), site (the captured site session) or none "
                        + "(anonymous)", "auto", "aem", "site", "none")
                .enumStr("wcmmode", "WCM mode on author (default disabled; none = no wcmmode parameter)",
                        "disabled", "edit", "preview", "none")
                .integer("width", "Viewport width in CSS pixels (default 1440)", 320, 3840)
                .integer("height", "Viewport height in CSS pixels (default 900)", 240, 2400)
                .bool("blockExternal", "Block requests to non-local hosts (default false)")
                .integer("settleMs", "Extra wait after the network is idle (default 500)", 0, 15000)
                .integer("timeoutSeconds", "Navigation timeout in seconds (default 45)", 5, 180);
    }

    /** The page the path and instance arguments point to: an AEM page or a local dev-server URL. */
    static PageTarget target(Services s, Args args) {
        return s.pageTargets.resolve(args.requireStr("path"), args.str("instance"));
    }

    /** Builds the render request for a target and attaches the browser session chosen by the session argument. */
    static RenderRequest buildRequest(Services s, ToolContext context, Args args, PageTarget target) {
        AemInstance instance = target.instance();
        String mode = args.choice("wcmmode", "disabled", "disabled", "edit", "preview", "none");
        String url;
        if (target.devServer()) {
            url = target.renderUrl();
        } else {
            url = instance.url(instance.isAuthor() ? PageUrls.withWcmMode(target.requestPath(), mode)
                    : target.requestPath());
        }
        RenderRequest request = new RenderRequest(url, target.origin())
                .viewport(args.integer("width", 1440, 320, 3840), args.integer("height", 900, 240, 2400))
                .blockExternal(args.bool("blockExternal", false))
                .settleMs(args.integer("settleMs", 500, 0, 15000))
                .timeoutMs(args.integer("timeoutSeconds", 45, 5, 180) * 1000);
        applySession(s, context, args.choice("session", "auto", "auto", "aem", "site", "none"), target, request);
        return request;
    }

    /**
     * Gives the render exactly one identity: the AEM login of the target's instance, the captured site session, or
     * none. Cookies are not port specific, so two instances' logins are never put into one browser context.
     */
    static void applySession(Services s, ToolContext context, String session, PageTarget target,
                             RenderRequest request) {
        AemInstance instance = target.instance();
        String mode = session;
        SiteSession site = null;
        if ("auto".equals(mode)) {
            if (instance.isAuthor()) {
                mode = "aem";
            } else {
                site = s.browser.siteSession();
                mode = site != null && !site.isEmpty() ? "site" : "none";
            }
        }
        switch (mode) {
            case "aem": {
                s.auth.ensureSession(context, instance);
                String user = s.auth.status(instance, false).user;
                request.cookies(s.auth.sessionCookies(instance))
                        .sessionNote("AEM login of " + instance.name() + (user == null ? "" : " (" + user + ")"));
                break;
            }
            case "site": {
                if (site == null) {
                    site = s.browser.siteSession();
                }
                if (site == null || site.isEmpty()) {
                    throw new ToolException("No site session has been captured. Call aem_auth action=site-login url="
                            + target.renderUrl() + " and ask the user to sign in in the window that opens (any "
                            + "login flow, including an external identity provider), then retry.");
                }
                request.siteSession(site).sessionNote(site.label());
                break;
            }
            default:
                request.sessionNote("anonymous");
                break;
        }
    }

    /**
     * Hint when local requests of a publish or dev-server page were refused (401/403) while rendering without the
     * AEM login: the page probably needs a signed-in visitor. Null when it does not apply.
     */
    static String accessHint(PageTarget target, RenderRequest request, RenderResult render) {
        if (render == null || (target.instance().isAuthor() && !target.devServer()) || !request.cookies().isEmpty()) {
            return null;
        }
        int refused = 0;
        String example = null;
        for (NetworkEntry entry : render.network()) {
            if ((entry.status() == 401 || entry.status() == 403) && HostPolicy.isLocalHost(entry.host())) {
                refused++;
                if (example == null) {
                    example = entry.method() + " " + Text.truncate(entry.path(), 120) + " -> " + entry.status();
                }
            }
        }
        if (refused == 0) {
            return null;
        }
        String login = "aem_auth action=site-login url=" + target.renderUrl();
        if (request.siteSession() != null) {
            return Text.plural(refused, "local request") + " refused (e.g. " + example + ") although the site session "
                    + "was used: it may have expired or lack access. Ask the user to sign in again with " + login
                    + ", then render again.";
        }
        return Text.plural(refused, "local request") + " refused (e.g. " + example + "): the page probably needs a "
                + "signed-in visitor. Ask the user to sign in with " + login + " (a visible window; that session "
                + "stays in memory only), then render again - publish and dev-server renders use it automatically.";
    }

    /** "dev server http://localhost:3000 -> publish (...), 1440x900, browser msedge - HTTP 200 - load 1.2 s". */
    static String headline(PageTarget target, RenderRequest request, RenderResult render) {
        StringBuilder sb = new StringBuilder();
        sb.append(target.describe()).append(", ").append(request.width()).append('x').append(request.height())
                .append(", browser ").append(render.channel()).append(" - HTTP ").append(render.status())
                .append(" - load ").append(Text.formatDuration(render.loadedAt() - render.startedAt()));
        if (render.title() != null && !render.title().isBlank()) {
            sb.append(" - title \"").append(Text.oneLine(render.title(), 100)).append('"');
        }
        sb.append('\n').append("Session: ").append(request.sessionNote()).append('\n');
        return sb.toString();
    }

    @Override
    public ToolResult call(ToolContext context, Args args) throws IOException {
        PageTarget target = target(s, args);
        RenderRequest request = buildRequest(s, context, args, target).fullPage(args.bool("fullPage", true));
        context.progress("Rendering " + request.url());
        RenderResult result = s.browser.render(request);
        String reportId = s.artifacts.newReport();
        Map<String, String> saved = RenderReport.saveArtifacts(s.artifacts, reportId, result);

        StringBuilder out = new StringBuilder();
        out.append("## Render ").append(reportId).append("  ").append(request.url()).append('\n');
        out.append(headline(target, request, result));
        if (result.finalUrl() != null && !result.finalUrl().equals(request.url())) {
            out.append("Final URL: ").append(result.finalUrl()).append('\n');
        }
        out.append(RenderReport.summary(result, 15));
        String hint = accessHint(target, request, result);
        if (hint != null) {
            out.append("Hint: ").append(hint).append('\n');
        }
        out.append(RenderReport.artifactsBlock(s.artifacts, reportId, saved));
        ToolResult toolResult = ToolResult.of(out.toString());
        if (args.bool("screenshot", true) && result.viewportJpeg() != null) {
            toolResult.image(ImageUtil.toJpeg(result.viewportJpeg(), 1280, 0.75f), "image/jpeg");
        }
        return toolResult;
    }
}
