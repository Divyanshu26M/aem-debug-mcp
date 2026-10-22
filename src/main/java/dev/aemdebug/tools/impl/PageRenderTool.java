package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.PageUrls;
import dev.aemdebug.browser.ImageUtil;
import dev.aemdebug.browser.RenderReport;
import dev.aemdebug.browser.RenderRequest;
import dev.aemdebug.browser.RenderResult;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
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
                "Renders a page of the local AEM instance in a headless browser (installed Edge/Chrome) the way a "
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
                .requiredStr("path", "Page path or local URL, e.g. /content/wknd/us/en or /content/wknd/us/en.html?x=1")
                .enumStr("instance", "author (default) or publish", "author", "publish")
                .enumStr("wcmmode", "WCM mode on author (default disabled; none = no wcmmode parameter)",
                        "disabled", "edit", "preview", "none")
                .integer("width", "Viewport width in CSS pixels (default 1440)", 320, 3840)
                .integer("height", "Viewport height in CSS pixels (default 900)", 240, 2400)
                .bool("blockExternal", "Block requests to non-local hosts (default false)")
                .integer("settleMs", "Extra wait after the network is idle (default 500)", 0, 15000)
                .integer("timeoutSeconds", "Navigation timeout in seconds (default 45)", 5, 180);
    }

    /** Builds the render request from the common arguments; ensures an author session first. */
    static RenderRequest buildRequest(Services s, ToolContext context, Args args, AemInstance instance) {
        String requestPath = PageUrls.toRequestPath(args.requireStr("path"));
        String mode = args.choice("wcmmode", "disabled", "disabled", "edit", "preview", "none");
        String url = instance.url(instance.isAuthor() ? PageUrls.withWcmMode(requestPath, mode) : requestPath);
        if (instance.isAuthor()) {
            s.auth.ensureSession(context, instance);
        }
        Map<String, String> cookies = s.auth.sessionCookies(instance);
        return new RenderRequest(url, instance.origin())
                .viewport(args.integer("width", 1440, 320, 3840), args.integer("height", 900, 240, 2400))
                .blockExternal(args.bool("blockExternal", false))
                .settleMs(args.integer("settleMs", 500, 0, 15000))
                .timeoutMs(args.integer("timeoutSeconds", 45, 5, 180) * 1000)
                .cookies(cookies);
    }

    @Override
    public ToolResult call(ToolContext context, Args args) throws IOException {
        AemInstance instance = s.instances.resolve(args.str("instance"));
        RenderRequest request = buildRequest(s, context, args, instance).fullPage(args.bool("fullPage", true));
        context.progress("Rendering " + request.url());
        RenderResult result = s.browser.render(request);
        String reportId = s.artifacts.newReport();
        Map<String, String> saved = RenderReport.saveArtifacts(s.artifacts, reportId, result);

        StringBuilder out = new StringBuilder();
        out.append("## Render ").append(reportId).append("  ").append(request.url()).append('\n');
        out.append(instance.name()).append(", ").append(request.width()).append('x').append(request.height())
                .append(", browser ").append(result.channel()).append(" - HTTP ").append(result.status())
                .append(" - load ").append(Text.formatDuration(result.loadedAt() - result.startedAt()));
        if (result.title() != null && !result.title().isBlank()) {
            out.append(" - title \"").append(Text.oneLine(result.title(), 100)).append('"');
        }
        out.append('\n');
        if (result.finalUrl() != null && !result.finalUrl().equals(request.url())) {
            out.append("Final URL: ").append(result.finalUrl()).append('\n');
        }
        out.append(RenderReport.summary(result, 15));
        out.append(RenderReport.artifactsBlock(s.artifacts, reportId, saved));
        ToolResult toolResult = ToolResult.of(out.toString());
        if (args.bool("screenshot", true) && result.viewportJpeg() != null) {
            toolResult.image(ImageUtil.toJpeg(result.viewportJpeg(), 1280, 0.75f), "image/jpeg");
        }
        return toolResult;
    }
}
