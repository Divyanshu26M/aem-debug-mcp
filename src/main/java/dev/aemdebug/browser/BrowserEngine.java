package dev.aemdebug.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.config.Config;
import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Playwright-Java browser engine using an installed Edge/Chrome channel. Playwright is not thread safe, so every
 * Playwright call runs on one dedicated thread. Provides headless renders and the visible login window.
 */
public final class BrowserEngine implements AuthManager.LoginLauncher, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserEngine.class);
    private static final long LOGIN_WINDOW_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final String[] BUNDLED_NODE_CANDIDATES = {
        "driver/win32_x64/node.exe", "driver/mac/node", "driver/mac-arm64/node", "driver/linux/node",
        "driver/linux-arm64/node"
    };

    private final Config config;
    private final ScheduledExecutorService executor;

    // Accessed only on the browser thread.
    private Playwright playwright;
    private Browser headless;
    private Browser headed;
    private String channelInUse;
    private final Map<String, LoginSession> logins = new HashMap<>();

    private static final class LoginSession {
        final AemInstance instance;
        final BrowserContext context;
        final Page page;
        final AuthManager.PendingLogin pending;
        final long startedAt = System.currentTimeMillis();
        boolean done;

        LoginSession(AemInstance instance, BrowserContext context, Page page, AuthManager.PendingLogin pending) {
            this.instance = instance;
            this.context = context;
            this.page = page;
            this.pending = pending;
        }
    }

    public BrowserEngine(Config config) {
        this.config = config;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aemdbg-browser");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Channel used by the last launch (msedge/chrome/chromium), or null before the first launch. */
    public String channelInUse() {
        return channelInUse;
    }

    // ------------------------------------------------------------------ rendering

    public RenderResult render(RenderRequest request) {
        URI target = URI.create(request.url());
        HostPolicy.requireLocal(target);
        return onBrowserThread(() -> doRender(request), Duration.ofMillis(request.timeoutMs() + 60_000L));
    }

    private RenderResult doRender(RenderRequest request) {
        Browser browser = headlessBrowser();
        RenderResult result = new RenderResult();
        result.requestedUrl = request.url();
        result.channel = channelInUse;
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(request.width(), request.height())
                .setDeviceScaleFactor(1)
                .setIgnoreHTTPSErrors(true);
        try (BrowserContext context = browser.newContext(options)) {
            List<Cookie> cookies = new ArrayList<>();
            for (Map.Entry<String, String> cookie : request.cookies().entrySet()) {
                cookies.add(new Cookie(cookie.getKey(), cookie.getValue()).setUrl(request.origin()));
            }
            if (!cookies.isEmpty()) {
                context.addCookies(cookies);
            }
            if (request.blockExternal()) {
                context.route(url -> !isLocalUrl(url), route -> route.abort());
            }
            Page page = context.newPage();
            page.setDefaultTimeout(request.timeoutMs());
            Map<Request, NetworkEntry> byRequest = new IdentityHashMap<>();
            page.onRequest(r -> {
                NetworkEntry entry = new NetworkEntry(r.method(), r.url(), r.resourceType(),
                        System.currentTimeMillis(), r.isNavigationRequest());
                byRequest.put(r, entry);
                result.network.add(entry);
            });
            page.onResponse(response -> {
                NetworkEntry entry = byRequest.get(response.request());
                if (entry != null) {
                    entry.setStatus(response.status());
                    entry.setContentType(response.headers().get("content-type"));
                }
            });
            page.onRequestFinished(r -> {
                NetworkEntry entry = byRequest.get(r);
                if (entry != null) {
                    entry.setFinishedAt(System.currentTimeMillis());
                }
            });
            page.onRequestFailed(r -> {
                NetworkEntry entry = byRequest.get(r);
                if (entry != null) {
                    entry.setFailure(java.util.Objects.toString(r.failure(), "failed"));
                    entry.setFinishedAt(System.currentTimeMillis());
                }
            });
            page.onConsoleMessage(message -> result.console.add(new ConsoleEntry(String.valueOf(message.type()),
                    message.text(), java.util.Objects.toString(message.location(), null),
                    System.currentTimeMillis())));
            page.onPageError(error -> result.console.add(new ConsoleEntry("pageerror", String.valueOf(error), null,
                    System.currentTimeMillis())));

            result.startedAt = System.currentTimeMillis();
            Response main = null;
            try {
                main = page.navigate(request.url(), new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.LOAD)
                        .setTimeout(request.timeoutMs()));
            } catch (TimeoutError e) {
                result.navigationError = "Navigation did not reach the load event within " + request.timeoutMs() + " ms";
            } catch (PlaywrightException e) {
                result.navigationError = firstLine(e.getMessage());
            }
            if (main != null) {
                result.status = main.status();
            }
            if (result.navigationError == null) {
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(Math.min(10_000, request.timeoutMs())));
                } catch (TimeoutError e) {
                    result.networkIdle = false;
                } catch (PlaywrightException e) {
                    LOG.debug("waitForLoadState failed: {}", e.getMessage());
                }
                if (request.settleMs() > 0) {
                    page.waitForTimeout(request.settleMs());
                }
            }
            result.loadedAt = System.currentTimeMillis();
            capturePage(page, request, result);
        }
        return result;
    }

    private void capturePage(Page page, RenderRequest request, RenderResult result) {
        try {
            result.finalUrl = page.url();
            result.title = page.title();
        } catch (PlaywrightException e) {
            LOG.debug("title/url failed: {}", e.getMessage());
        }
        try {
            result.html = page.content();
        } catch (PlaywrightException e) {
            LOG.debug("content failed: {}", e.getMessage());
        }
        try {
            result.viewportJpeg = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.JPEG).setQuality(80).setTimeout(20_000));
        } catch (PlaywrightException e) {
            LOG.info("viewport screenshot failed: {}", e.getMessage());
        }
        if (request.fullPage()) {
            try {
                result.fullPagePng = page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true).setType(ScreenshotType.PNG).setTimeout(30_000));
            } catch (PlaywrightException e) {
                LOG.info("full page screenshot failed: {}", e.getMessage());
            }
        }
        try {
            Object timings = page.evaluate("() => { const n = performance.getEntriesByType('navigation')[0];"
                    + " if (!n) return null; return { ttfbMs: Math.round(n.responseStart),"
                    + " domContentLoadedMs: Math.round(n.domContentLoadedEventEnd),"
                    + " loadMs: Math.round(n.loadEventEnd), transferSize: n.transferSize }; }");
            if (timings instanceof Map<?, ?>) {
                result.timings = Json.asMap(timings);
            }
        } catch (PlaywrightException e) {
            LOG.debug("timings failed: {}", e.getMessage());
        }
        if (request.probeScript() != null) {
            try {
                result.probeResult = page.evaluate(request.probeScript(), request.probeArg());
            } catch (PlaywrightException e) {
                result.probeError = firstLine(e.getMessage());
            }
        }
        for (String selector : request.elementShots()) {
            try {
                Locator locator = page.locator(selector).first();
                byte[] shot = locator.screenshot(new Locator.ScreenshotOptions()
                        .setType(ScreenshotType.JPEG).setQuality(80).setTimeout(5_000));
                result.elementShots.put(selector, shot);
            } catch (PlaywrightException e) {
                LOG.debug("element screenshot {} failed: {}", selector, e.getMessage());
            }
        }
    }

    private static boolean isLocalUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return true;
            }
            return HostPolicy.isLocalHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ interactive login

    @Override
    public void openLoginWindow(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
        HostPolicy.requireLocal(URI.create(loginUrl));
        onBrowserThread(() -> {
            doOpenLogin(instance, loginUrl, pending);
            return null;
        }, Duration.ofSeconds(90));
    }

    @Override
    public void closeLoginWindow(AemInstance instance) {
        onBrowserThread(() -> {
            LoginSession session = logins.get(instance.origin());
            if (session != null) {
                finishLogin(session, null, "cancelled");
            }
            return null;
        }, Duration.ofSeconds(30));
    }

    private void doOpenLogin(AemInstance instance, String loginUrl, AuthManager.PendingLogin pending) {
        LoginSession existing = logins.get(instance.origin());
        if (existing != null && !existing.done) {
            finishLogin(existing, null, "replaced by a new login window");
        }
        if (headed == null || !headed.isConnected()) {
            headed = launch(false);
        }
        BrowserContext context = headed.newContext(new Browser.NewContextOptions()
                .setViewportSize(null)
                .setIgnoreHTTPSErrors(true));
        Page page = context.newPage();
        LoginSession session = new LoginSession(instance, context, page, pending);
        logins.put(instance.origin(), session);
        try {
            page.navigate(loginUrl, new Page.NavigateOptions().setTimeout(30_000));
            page.bringToFront();
        } catch (PlaywrightException e) {
            finishLogin(session, null, "login page did not load: " + firstLine(e.getMessage()));
            return;
        }
        LOG.info("Opened interactive login window for {}", instance.name());
        schedulePoll(session);
    }

    private void schedulePoll(LoginSession session) {
        executor.schedule(() -> pollLogin(session), 700, TimeUnit.MILLISECONDS);
    }

    private void pollLogin(LoginSession session) {
        if (session.done) {
            return;
        }
        try {
            if (session.page.isClosed()) {
                finishLogin(session, null, "the login window was closed before signing in");
                return;
            }
            for (Cookie cookie : session.context.cookies(session.instance.url("/"))) {
                if (AuthManager.LOGIN_TOKEN.equals(cookie.name) && cookie.value != null && !cookie.value.isEmpty()) {
                    finishLogin(session, cookie.value, null);
                    return;
                }
            }
            if (System.currentTimeMillis() - session.startedAt > LOGIN_WINDOW_TIMEOUT_MS) {
                finishLogin(session, null, "the login window timed out");
                return;
            }
            schedulePoll(session);
        } catch (PlaywrightException e) {
            finishLogin(session, null, "login window error: " + firstLine(e.getMessage()));
        }
    }

    private void finishLogin(LoginSession session, String token, String error) {
        session.done = true;
        if (logins.get(session.instance.origin()) == session) {
            logins.remove(session.instance.origin());
        }
        if (token != null) {
            session.pending.complete(token);
            LOG.info("Captured AEM login token for {}", session.instance.name());
        } else {
            session.pending.fail(error);
            LOG.info("Interactive login for {} ended: {}", session.instance.name(), error);
        }
        try {
            session.context.close();
        } catch (PlaywrightException e) {
            LOG.debug("closing login context failed: {}", e.getMessage());
        }
        if (logins.isEmpty() && headed != null) {
            try {
                headed.close();
            } catch (PlaywrightException e) {
                LOG.debug("closing headed browser failed: {}", e.getMessage());
            }
            headed = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private Browser headlessBrowser() {
        if (headless == null || !headless.isConnected()) {
            headless = launch(true);
        }
        return headless;
    }

    private Playwright playwright() {
        if (playwright == null) {
            Map<String, String> env = new HashMap<>();
            // Installed Edge/Chrome channels are used, so never download Playwright's own browsers.
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            if (System.getenv("PLAYWRIGHT_NODEJS_PATH") == null && !bundledNodePresent()) {
                String node = findOnPath(isWindows() ? "node.exe" : "node");
                if (node != null) {
                    env.put("PLAYWRIGHT_NODEJS_PATH", node);
                    LOG.info("Using Node.js from PATH for the Playwright driver: {}", node);
                }
            }
            try {
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            } catch (RuntimeException e) {
                throw new ToolException("Cannot start the Playwright driver: " + firstLine(e.getMessage())
                        + ". If the error mentions Node.js, install Node.js 18+ or set PLAYWRIGHT_NODEJS_PATH.", e);
            }
        }
        return playwright;
    }

    private Browser launch(boolean headlessMode) {
        List<String> channels = new ArrayList<>();
        if (config.browserChannel() != null) {
            channels.add(config.browserChannel());
        } else if (isWindows()) {
            channels.add("msedge");
            channels.add("chrome");
        } else {
            channels.add("chrome");
            channels.add("msedge");
            channels.add("chromium");
        }
        RuntimeException last = null;
        for (String channel : channels) {
            try {
                BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                        .setHeadless(headlessMode)
                        .setTimeout(60_000);
                if (!"chromium".equals(channel)) {
                    options.setChannel(channel);
                }
                if (!headlessMode) {
                    options.setArgs(List.of("--window-size=1100,860"));
                }
                Browser browser = playwright().chromium().launch(options);
                channelInUse = channel;
                LOG.info("Launched {} browser via channel {}", headlessMode ? "headless" : "visible", channel);
                return browser;
            } catch (ToolException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                LOG.info("Browser channel {} failed: {}", channel, firstLine(e.getMessage()));
            }
        }
        throw new ToolException("Could not launch a browser (tried " + channels + "): "
                + (last == null ? "unknown error" : firstLine(last.getMessage()))
                + ". Install Microsoft Edge or Google Chrome, or set AEM_DEBUG_BROWSER_CHANNEL.");
    }

    private <T> T onBrowserThread(Callable<T> task, Duration timeout) {
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ToolException("Browser operation did not finish within " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolException) {
                throw (ToolException) cause;
            }
            if (cause instanceof PlaywrightException) {
                throw new ToolException("Browser error: " + firstLine(cause.getMessage()), cause);
            }
            throw new ToolException("Browser error: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while waiting for the browser");
        }
    }

    /** Closes all browsers and the driver. Safe to call multiple times. */
    @Override
    public void close() {
        try {
            onBrowserThread(() -> {
                for (LoginSession session : new ArrayList<>(logins.values())) {
                    finishLogin(session, null, "server shutting down");
                }
                closeQuietly(headless);
                closeQuietly(headed);
                headless = null;
                headed = null;
                if (playwright != null) {
                    try {
                        playwright.close();
                    } catch (RuntimeException e) {
                        LOG.debug("closing playwright failed: {}", e.getMessage());
                    }
                    playwright = null;
                }
                return null;
            }, Duration.ofSeconds(20));
        } catch (RuntimeException e) {
            LOG.debug("browser close failed: {}", e.getMessage());
        }
    }

    /** Closes browsers but keeps the engine usable (used by debug_cleanup). */
    public String closeBrowsers() {
        return onBrowserThread(() -> {
            int count = 0;
            for (LoginSession session : new ArrayList<>(logins.values())) {
                finishLogin(session, null, "closed by debug_cleanup");
            }
            if (headless != null) {
                closeQuietly(headless);
                headless = null;
                count++;
            }
            if (headed != null) {
                closeQuietly(headed);
                headed = null;
                count++;
            }
            return count == 0 ? "no browser was open" : "closed " + Text.plural(count, "browser");
        }, Duration.ofSeconds(30));
    }

    public void shutdown() {
        close();
        executor.shutdownNow();
    }

    private static void closeQuietly(Browser browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (RuntimeException e) {
            LOG.debug("closing browser failed: {}", e.getMessage());
        }
    }

    private static boolean bundledNodePresent() {
        ClassLoader loader = BrowserEngine.class.getClassLoader();
        for (String candidate : BUNDLED_NODE_CANDIDATES) {
            if (loader.getResource(candidate) != null) {
                return true;
            }
        }
        return false;
    }

    static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(dir.trim().replace("\"", "")).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException e) {
                LOG.debug("Skipping PATH entry {}", dir);
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        String trimmed = message.trim();
        int newline = trimmed.indexOf('\n');
        String line = newline > 0 ? trimmed.substring(0, newline) : trimmed;
        return Text.oneLine(line, 400);
    }
}
