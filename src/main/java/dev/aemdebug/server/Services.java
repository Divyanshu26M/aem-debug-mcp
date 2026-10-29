package dev.aemdebug.server;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemHttpClient;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.ContentService;
import dev.aemdebug.aem.CookieJar;
import dev.aemdebug.aem.DevServers;
import dev.aemdebug.aem.FelixConsole;
import dev.aemdebug.aem.LoginStore;
import dev.aemdebug.aem.PageTargets;
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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    public final DevServers devServers;
    public final PageTargets pageTargets;
    /** One engine per AEM instance (author, publish); empty when the runtime has no jdk.jdi module. */
    private final Map<String, JdiEngine> jdiEngines = new LinkedHashMap<>();
    private final Map<String, Integer> jdwpPortOverrides = new ConcurrentHashMap<>();

    public Services(Config config) {
        this.config = config;
        this.activity = new ActivityTracker();
        this.artifacts = new ArtifactStore(config.artifactsDir());
        this.instances = new AemInstances(config);
        this.cookies = new CookieJar();
        this.http = new AemHttpClient(cookies);
        this.auth = new AuthManager(config, http,
                config.rememberLogins() ? LoginStore.in(config.artifactsDir()) : LoginStore.disabled());
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
        this.devServers = new DevServers(config.artifactsDir(), config.devServers());
        this.pageTargets = new PageTargets(instances, devServers);
        if (JdiSupport.available()) {
            jdiEngines.put("author", new JdiEngine(config.idleDetachMinutes(), "author"));
            jdiEngines.put("publish", new JdiEngine(config.idleDetachMinutes(), "publish"));
        }
        this.cleanup = new CleanupCoordinator();
        for (Map.Entry<String, JdiEngine> entry : jdiEngines.entrySet()) {
            this.cleanup.register("author".equals(entry.getKey()) ? entry.getValue() : quietWhenUnused(entry.getValue()));
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

    /** The JVM engine of an instance ("author" or "publish"), or null when jdk.jdi is not available. */
    public JdiEngine jdi(String instance) {
        return jdiEngines.get(instance);
    }

    public Collection<JdiEngine> jdiEngines() {
        return Collections.unmodifiableCollection(jdiEngines.values());
    }

    /** JDWP port of an instance: a port given to jvm_connection in this session, else the configuration; -1 if none. */
    public int jdwpPort(String instance) {
        Integer override = jdwpPortOverrides.get(instance);
        return override != null ? override : config.jdwpPort(instance);
    }

    public void overrideJdwpPort(String instance, int port) {
        jdwpPortOverrides.put(instance, port);
    }

    /** Cleanup of an engine that reports nothing while it was never attached and holds no definitions. */
    private static CleanupCoordinator.Participant quietWhenUnused(JdiEngine engine) {
        return new CleanupCoordinator.Participant() {
            @Override
            public String name() {
                return engine.name();
            }

            @Override
            public String cleanup(dev.aemdebug.tools.ToolContext context, boolean shutdown) {
                if (!engine.isAttached() && engine.breakpointList().isEmpty() && engine.watchList().isEmpty()) {
                    return null;
                }
                return engine.cleanup(context, shutdown);
            }

            @Override
            public String onIdle() {
                return engine.onIdle();
            }

            @Override
            public void recover() {
                engine.recover();
            }
        };
    }
}
