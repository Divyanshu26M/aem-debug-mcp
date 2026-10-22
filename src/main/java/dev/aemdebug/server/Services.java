package dev.aemdebug.server;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemHttpClient;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.CookieJar;
import dev.aemdebug.aem.FelixConsole;
import dev.aemdebug.aem.ResolverClient;
import dev.aemdebug.browser.BrowserEngine;
import dev.aemdebug.config.Config;
import dev.aemdebug.logs.LogLocator;
import dev.aemdebug.logs.LogService;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.jdi.JdiSupport;
import dev.aemdebug.mapping.ComponentMapper;
import dev.aemdebug.project.ProjectIndexService;
import dev.aemdebug.report.ArtifactStore;
import dev.aemdebug.rpt.RecentRequestsClient;
import dev.aemdebug.toggles.RevertJournal;
import dev.aemdebug.toggles.ToggleService;
import dev.aemdebug.tools.ActivityTracker;

/** Composition root: all long-lived services, shared by the tools. */
public final class Services {

    public final Config config;
    public final ActivityTracker activity;
    public final ArtifactStore artifacts;
    public final AemInstances instances;
    public final CookieJar cookies;
    public final AemHttpClient http;
    public final AuthManager auth;
    public final AemClient aem;
    public final FelixConsole felix;
    public final LogLocator logLocator;
    public final BrowserEngine browser;
    public final CleanupCoordinator cleanup;
    public final ContentService content;
    public final ResolverClient resolver;
    public final LogService logs;
    public final RecentRequestsClient recentRequests;
    public final ToggleService toggles;
    public final ProjectIndexService projects;
    public final ComponentMapper mapper;
    /** Null when the runtime has no jdk.jdi module. */
    public final JdiEngine jdi;

    public Services(Config config) {
        this.config = config;
        this.activity = new ActivityTracker();
        this.artifacts = new ArtifactStore(config.artifactsDir());
        this.instances = new AemInstances(config);
        this.cookies = new CookieJar();
        this.http = new AemHttpClient(cookies);
        this.auth = new AuthManager(config, http);
        this.aem = new AemClient(instances, http, auth);
        this.felix = new FelixConsole(aem);
        this.logLocator = new LogLocator(config, felix);
        this.browser = new BrowserEngine(config);
        this.auth.setLoginLauncher(browser);
        this.content = new ContentService(aem);
        this.resolver = new ResolverClient(aem, content);
        this.logs = new LogService(logLocator, aem);
        this.recentRequests = new RecentRequestsClient(aem);
        this.toggles = new ToggleService(aem, instances,
                new RevertJournal(config.artifactsDir().resolve("toggle-journal.json")));
        this.projects = new ProjectIndexService(config);
        this.mapper = new ComponentMapper(aem, content, felix, browser, projects);
        this.jdi = JdiSupport.available() ? new JdiEngine(config.idleDetachMinutes()) : null;
        this.cleanup = new CleanupCoordinator();
        if (jdi != null) {
            this.cleanup.register(jdi);
        }
        this.cleanup.register(toggles);
        this.cleanup.register(new CleanupCoordinator.Participant() {
            @Override
            public String name() {
                return "browser";
            }

            @Override
            public String cleanup(dev.aemdebug.tools.ToolContext context, boolean shutdown) {
                return shutdown ? null : browser.closeBrowsers();
            }
        });
    }
}
