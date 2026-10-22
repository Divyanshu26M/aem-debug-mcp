package dev.aemdebug.toggles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemHttpClient;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.aem.RequestOptions;
import dev.aemdebug.server.CleanupCoordinator;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug-related OSGi toggles: WCM Debug Filter, Recent Requests buffer, RPT logging and per-logger levels.
 * Each change is planned, confirmed by the user, verified, recorded in the {@link RevertJournal} and reverted
 * by debug_cleanup, after inactivity, on shutdown or at the next start.
 */
public final class ToggleService implements CleanupCoordinator.Participant {

    private static final Logger LOG = LoggerFactory.getLogger(ToggleService.class);

    public static final String WCM_DEBUG = "wcm-debug-filter";
    public static final String RECENT_REQUESTS = "recent-requests";
    public static final String RPT_LOG = "rpt-log";
    public static final String LOG_LEVEL = "log-level";
    public static final List<String> TOGGLES = List.of(WCM_DEBUG, RECENT_REQUESTS, RPT_LOG, LOG_LEVEL);

    static final String WCM_DEBUG_PID = "com.day.cq.wcm.core.impl.WCMDebugFilter";
    static final String[] RECENT_REQUESTS_PIDS = {
        "org.apache.sling.engine.impl.SlingMainServlet",
        "org.apache.sling.engine.impl.debug.RequestInfoProviderImpl"
    };
    static final String LOG_FACTORY = "org.apache.sling.commons.log.LogManager.factory.config";
    static final String RPT_LOGGER = "org.apache.sling.engine.impl.debug.RequestProgressTrackerLogFilter";
    static final String NEW_FACTORY_PATH =
            "/system/console/configMgr/%5BTemporary%20PID%20replaced%20by%20real%20PID%20upon%20save%5D";

    /** What a toggle would change. */
    public static final class Plan {
        public String toggle;
        public AemInstance instance;
        public String pid;
        public String factoryPid;
        public boolean create;
        public boolean existed;
        public Map<String, Object> current = new LinkedHashMap<>();
        public Map<String, Object> effective = new LinkedHashMap<>();
        public Map<String, Object> changes = new LinkedHashMap<>();

        public boolean noOp() {
            if (create) {
                return false;
            }
            for (Map.Entry<String, Object> change : changes.entrySet()) {
                if (!sameValue(effective.get(change.getKey()), change.getValue())) {
                    return false;
                }
            }
            return true;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Toggle ").append(toggle).append(" on AEM ").append(instance).append('\n');
            if (create) {
                sb.append("Create configuration of factory ").append(factoryPid).append(":\n");
                for (Map.Entry<String, Object> change : changes.entrySet()) {
                    sb.append("  ").append(change.getKey()).append(" = ").append(format(change.getValue())).append('\n');
                }
                sb.append("Revert: delete the created configuration\n");
            } else {
                sb.append("Modify PID ").append(pid).append(existed ? "" : " (no explicit configuration yet)")
                        .append(":\n");
                for (Map.Entry<String, Object> change : changes.entrySet()) {
                    sb.append("  ").append(change.getKey()).append(": ")
                            .append(format(effective.get(change.getKey()))).append(" -> ")
                            .append(format(change.getValue())).append('\n');
                }
                sb.append(existed ? "Revert: restore the previous values\n"
                        : "Revert: delete the configuration again (defaults apply)\n");
            }
            return sb.toString();
        }
    }

    private final AemClient aem;
    private final AemHttpClient http;
    private final AemInstances instances;
    private final RevertJournal journal;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "aemdbg-toggle-revert");
        thread.setDaemon(true);
        return thread;
    });

    public ToggleService(AemClient aem, AemInstances instances, RevertJournal journal) {
        this.aem = aem;
        this.http = aem.http();
        this.instances = instances;
        this.journal = journal;
        for (RevertJournal.Entry entry : journal.active()) {
            fromPreviousRun.add(entry.id);
        }
        aem.auth().addLoginListener(instance -> {
            if (hasPreviousRunEntries(instance)) {
                background.submit(() -> revertPreviousRun(instance));
            }
        });
    }

    private final Set<String> fromPreviousRun = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean hasPreviousRunEntries(AemInstance instance) {
        for (RevertJournal.Entry entry : activeFor(instance)) {
            if (fromPreviousRun.contains(entry.id)) {
                return true;
            }
        }
        return false;
    }

    /** Reverts toggles left active by a previous (crashed) server run. */
    private void revertPreviousRun(AemInstance instance) {
        for (RevertJournal.Entry entry : activeFor(instance)) {
            if (!fromPreviousRun.contains(entry.id)) {
                continue;
            }
            try {
                revert(ToolContext.detached(), entry);
                fromPreviousRun.remove(entry.id);
            } catch (RuntimeException e) {
                LOG.info("Recovery revert of {} failed: {}", entry.id, e.getMessage());
            }
        }
    }

    /** Ids of toggles that a previous server run left active (reverted as soon as a session exists). */
    public Set<String> fromPreviousRun() {
        return fromPreviousRun;
    }

    public RevertJournal journal() {
        return journal;
    }

    public List<RevertJournal.Entry> activeFor(AemInstance instance) {
        List<RevertJournal.Entry> result = new ArrayList<>();
        for (RevertJournal.Entry entry : journal.active()) {
            if (instance.origin().equals(entry.origin)) {
                result.add(entry);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ planning

    public Plan plan(ToolContext context, AemInstance instance, String toggle, List<String> loggers, String level,
                     int bufferSize, String storePattern) {
        Plan plan = new Plan();
        plan.toggle = toggle;
        plan.instance = instance;
        switch (toggle) {
            case WCM_DEBUG: {
                plan.pid = WCM_DEBUG_PID;
                fillCurrent(context, plan);
                plan.changes.put("wcmdbgfilter.enabled", Boolean.TRUE);
                break;
            }
            case RECENT_REQUESTS: {
                plan.pid = choosePid(context, instance, RECENT_REQUESTS_PIDS, "sling.max.record.requests");
                fillCurrent(context, plan);
                plan.changes.put("sling.max.record.requests", bufferSize);
                if (storePattern != null) {
                    plan.changes.put("sling.store.pattern.requests", List.of(storePattern));
                }
                break;
            }
            case RPT_LOG:
                planLogger(plan, List.of(RPT_LOGGER), "debug");
                break;
            case LOG_LEVEL: {
                if (loggers == null || loggers.isEmpty()) {
                    throw new ToolException("log-level needs 'loggers', e.g. [\"com.mysite\"] or "
                            + "[\"org.apache.sling.models.impl\"]");
                }
                planLogger(plan, loggers, level == null ? "debug" : level.toLowerCase(Locale.ROOT));
                break;
            }
            default:
                throw new ToolException("Unknown toggle '" + toggle + "'. Available: " + TOGGLES);
        }
        return plan;
    }

    private static void planLogger(Plan plan, List<String> loggers, String level) {
        if (!List.of("trace", "debug", "info", "warn", "error").contains(level)) {
            throw new ToolException("level must be trace, debug, info, warn or error");
        }
        plan.create = true;
        plan.factoryPid = LOG_FACTORY;
        plan.changes.put("org.apache.sling.commons.log.level", level);
        plan.changes.put("org.apache.sling.commons.log.file", "logs/error.log");
        plan.changes.put("org.apache.sling.commons.log.names", new ArrayList<>(loggers));
        plan.changes.put("org.apache.sling.commons.log.additiv", "false");
    }

    private String choosePid(ToolContext context, AemInstance instance, String[] candidates, String property) {
        for (String candidate : candidates) {
            ConfigState state = readConfig(context, instance, candidate);
            if (state.exists && state.effective.containsKey(property)) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private void fillCurrent(ToolContext context, Plan plan) {
        ConfigState state = readConfig(context, plan.instance, plan.pid);
        plan.existed = state.exists;
        plan.current.putAll(state.set);
        plan.effective.putAll(state.effective);
    }

    /** Configuration as reported by configMgr JSON: explicitly set values and effective values. */
    static final class ConfigState {
        boolean exists;
        final Map<String, Object> set = new LinkedHashMap<>();
        final Map<String, Object> effective = new LinkedHashMap<>();
    }

    ConfigState readConfig(ToolContext context, AemInstance instance, String pid) {
        ConfigState state = new ConfigState();
        AemResponse response = aem.get(context, instance,
                "/system/console/configMgr/" + Text.urlEncode(pid).replace("+", "%20") + ".json");
        if (!response.ok()) {
            return state;
        }
        Object json;
        try {
            json = response.json();
        } catch (IllegalArgumentException e) {
            return state;
        }
        parseConfigState(json, pid, state);
        return state;
    }

    static void parseConfigState(Object json, String pid, ConfigState state) {
        List<Object> items = json instanceof List<?> ? Json.asList(json) : List.of(json);
        for (Object item : items) {
            Map<String, Object> config = Json.asMap(item);
            String itemPid = Json.asString(config.get("pid"));
            if (itemPid != null && !itemPid.equals(pid)) {
                continue;
            }
            for (Map.Entry<String, Object> entry : Json.asMap(config.get("properties")).entrySet()) {
                Object descriptor = entry.getValue();
                Object value = descriptor;
                boolean isSet = true;
                if (descriptor instanceof Map<?, ?>) {
                    Map<String, Object> d = Json.asMap(descriptor);
                    value = d.containsKey("values") ? d.get("values") : d.get("value");
                    isSet = Json.asBoolean(d.get("is_set"), true);
                }
                state.effective.put(entry.getKey(), value);
                if (isSet) {
                    state.set.put(entry.getKey(), value);
                    state.exists = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ apply / revert

    public synchronized RevertJournal.Entry apply(ToolContext context, Plan plan) {
        RevertJournal.Entry entry = new RevertJournal.Entry();
        entry.id = journal.nextId();
        entry.toggle = plan.toggle;
        entry.instance = plan.instance.name();
        entry.origin = plan.instance.origin();
        entry.factoryPid = plan.factoryPid;
        entry.applied = new LinkedHashMap<>(plan.changes);
        entry.changedKeys = new ArrayList<>(plan.changes.keySet());
        entry.appliedAt = System.currentTimeMillis();
        if (plan.create) {
            Set<String> before = factoryPids(context, plan.instance, plan.factoryPid);
            AemResponse response = post(context, plan.instance, NEW_FACTORY_PATH,
                    form(plan.changes, plan.factoryPid, false));
            String pid = newPid(response, before, factoryPids(context, plan.instance, plan.factoryPid));
            if (pid == null) {
                throw new ToolException("AEM did not confirm the new configuration (HTTP " + response.status()
                        + "). Nothing recorded; check /system/console/configMgr manually.");
            }
            entry.pid = pid;
            entry.created = true;
        } else {
            entry.pid = plan.pid;
            entry.created = !plan.existed;
            entry.previous = new LinkedHashMap<>(plan.current);
            for (String key : plan.changes.keySet()) {
                if (!entry.previous.containsKey(key) && plan.effective.get(key) != null) {
                    entry.previous.put(key, plan.effective.get(key));
                }
            }
            Map<String, Object> values = new LinkedHashMap<>(plan.current);
            values.putAll(plan.changes);
            AemResponse response = post(context, plan.instance, configPath(plan.pid), form(values, null, false));
            if (response.status() >= 400) {
                throw new ToolException("AEM rejected the configuration change (HTTP " + response.status() + "): "
                        + response.snippet(200));
            }
            ConfigState after = readConfig(context, plan.instance, plan.pid);
            for (Map.Entry<String, Object> change : plan.changes.entrySet()) {
                if (!sameValue(after.effective.get(change.getKey()), change.getValue())) {
                    journal.add(entry);
                    throw new ToolException("The change was sent but " + change.getKey() + " reads back as "
                            + format(after.effective.get(change.getKey())) + ". Recorded for revert as " + entry.id + ".");
                }
            }
        }
        journal.add(entry);
        LOG.info("Applied debug toggle {} as {} on {} (pid {})", entry.toggle, entry.id, entry.instance, entry.pid);
        return entry;
    }

    public synchronized String revert(ToolContext context, RevertJournal.Entry entry) {
        if (entry.reverted) {
            return entry.id + " already reverted";
        }
        AemInstance instance = instanceFor(entry);
        try {
            if (entry.created) {
                AemResponse response = post(context, instance, configPath(entry.pid), deleteForm());
                if (response.status() >= 400) {
                    throw new ToolException("delete failed with HTTP " + response.status());
                }
            } else {
                Map<String, Object> values = new LinkedHashMap<>(entry.previous);
                for (String key : entry.changedKeys) {
                    if (!values.containsKey(key)) {
                        values.put(key, "");
                    }
                }
                AemResponse response = post(context, instance, configPath(entry.pid), form(values, null, false));
                if (response.status() >= 400) {
                    throw new ToolException("restore failed with HTTP " + response.status());
                }
            }
            entry.reverted = true;
            entry.revertError = null;
            journal.update();
            LOG.info("Reverted debug toggle {} ({}) on {}", entry.id, entry.toggle, entry.instance);
            return entry.id + " " + entry.toggle + " reverted (" + (entry.created ? "configuration deleted"
                    : "previous values restored") + ")";
        } catch (RuntimeException e) {
            entry.revertError = e.getMessage();
            journal.update();
            throw e;
        }
    }

    /** Reverts all active toggles of an instance (or all instances when null); never throws. */
    public List<String> revertAll(ToolContext context, AemInstance only) {
        List<String> lines = new ArrayList<>();
        for (RevertJournal.Entry entry : journal.active()) {
            if (only != null && !only.origin().equals(entry.origin)) {
                continue;
            }
            try {
                lines.add(revert(context, entry));
            } catch (RuntimeException e) {
                lines.add(entry.id + " " + entry.toggle + " NOT reverted: " + e.getMessage()
                        + " (it stays pending; it is retried after the next login or debug_cleanup)");
            }
        }
        return lines;
    }

    private AemInstance instanceFor(RevertJournal.Entry entry) {
        for (AemInstance candidate : new AemInstance[] {instances.authorOrNull(), instances.publishOrNull()}) {
            if (candidate != null && candidate.origin().equals(entry.origin)) {
                return candidate;
            }
        }
        throw new ToolException("The instance " + entry.origin + " of " + entry.id + " is no longer configured");
    }

    // ------------------------------------------------------------------ cleanup participant

    @Override
    public String name() {
        return "debug toggles";
    }

    @Override
    public String cleanup(ToolContext context, boolean shutdown) {
        if (journal.active().isEmpty()) {
            return shutdown ? null : "no active toggles";
        }
        if (shutdown) {
            List<String> lines = new ArrayList<>();
            for (RevertJournal.Entry entry : journal.active()) {
                try {
                    AemInstance instance = instanceFor(entry);
                    if (!aem.auth().hasSession(instance)) {
                        lines.add(entry.id + " pending (no session at shutdown; reverted at next start/login)");
                        continue;
                    }
                    lines.add(revert(context, entry));
                } catch (RuntimeException e) {
                    lines.add(entry.id + " NOT reverted: " + e.getMessage());
                }
            }
            return String.join("; ", lines);
        }
        return String.join("; ", revertAll(context, null));
    }

    @Override
    public String onIdle() {
        List<String> lines = new ArrayList<>();
        for (RevertJournal.Entry entry : journal.active()) {
            AemInstance instance;
            try {
                instance = instanceFor(entry);
            } catch (ToolException e) {
                continue;
            }
            if (aem.auth().hasSession(instance)) {
                try {
                    lines.add(revert(ToolContext.detached(), entry));
                } catch (RuntimeException e) {
                    lines.add(entry.id + " NOT reverted: " + e.getMessage());
                }
            }
        }
        return lines.isEmpty() ? null : String.join("; ", lines);
    }

    @Override
    public void recover() {
        if (fromPreviousRun.isEmpty()) {
            return;
        }
        LOG.warn("{} debug toggle(s) from a previous run are still active; reverting when a session is available",
                fromPreviousRun.size());
        background.submit(() -> {
            for (AemInstance instance : new AemInstance[] {instances.authorOrNull(), instances.publishOrNull()}) {
                if (instance == null || !hasPreviousRunEntries(instance)) {
                    continue;
                }
                try {
                    if (aem.auth().hasSession(instance) || aem.auth().loginWithCredentials(instance)) {
                        revertPreviousRun(instance);
                    }
                } catch (RuntimeException e) {
                    LOG.info("Recovery revert postponed for {}: {}", instance, e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ Felix configMgr protocol

    private AemResponse post(ToolContext context, AemInstance instance, String path, List<String[]> form) {
        if (!aem.auth().hasSession(instance)) {
            aem.auth().ensureSession(context, instance);
        }
        RequestOptions options = RequestOptions.defaults()
                .withHeader("Referer", instance.url("/system/console/configMgr"));
        String csrf = csrfToken(instance);
        if (csrf != null) {
            options = options.withHeader("CSRF-Token", csrf);
        }
        AemResponse response = http.postForm(instance, path, form, AemHttpClient.WriteReason.CONFIRMED_DEBUG_TOGGLE,
                options);
        if (response.isLoginRequired()) {
            throw new ToolException("AEM asked for authentication while changing the configuration; log in "
                    + "(aem_auth action=login) and retry");
        }
        return response;
    }

    private String csrfToken(AemInstance instance) {
        try {
            AemResponse response = http.get(instance, "/libs/granite/csrf/token.json");
            if (response.ok() && response.contentType().contains("json")) {
                return Json.asString(response.jsonObject().get("token"));
            }
        } catch (RuntimeException e) {
            LOG.debug("No CSRF token: {}", e.getMessage());
        }
        return null;
    }

    static String configPath(String pid) {
        return "/system/console/configMgr/" + Text.urlEncode(pid).replace("+", "%20");
    }

    static List<String[]> form(Map<String, Object> values, String factoryPid, boolean delete) {
        List<String[]> form = new ArrayList<>();
        form.add(new String[] {"apply", "true"});
        form.add(new String[] {"action", "ajaxConfigManager"});
        if (factoryPid != null) {
            form.add(new String[] {"factoryPid", factoryPid});
        }
        form.add(new String[] {"propertylist", String.join(",", values.keySet())});
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Collection<?>) {
                for (Object item : (Collection<?>) value) {
                    form.add(new String[] {entry.getKey(), String.valueOf(item)});
                }
            } else {
                form.add(new String[] {entry.getKey(), value == null ? "" : String.valueOf(value)});
            }
        }
        return form;
    }

    static List<String[]> deleteForm() {
        List<String[]> form = new ArrayList<>();
        form.add(new String[] {"apply", "true"});
        form.add(new String[] {"delete", "true"});
        form.add(new String[] {"action", "ajaxConfigManager"});
        return form;
    }

    private Set<String> factoryPids(ToolContext context, AemInstance instance, String factoryPid) {
        Set<String> pids = new LinkedHashSet<>();
        String filter = "(service.factoryPid=" + factoryPid + ")";
        AemResponse response = aem.get(context, instance,
                "/system/console/configMgr/" + Text.urlEncode(filter).replace("+", "%20") + ".json");
        if (response.ok()) {
            try {
                for (Object item : Json.asList(response.json())) {
                    String pid = Json.asString(Json.asMap(item).get("pid"));
                    if (pid != null) {
                        pids.add(pid);
                    }
                }
            } catch (IllegalArgumentException e) {
                LOG.debug("factory listing not JSON");
            }
        }
        return pids;
    }

    static String newPid(AemResponse response, Set<String> before, Set<String> after) {
        try {
            Object json = response.json();
            String pid = Json.asString(Json.asMap(json).get("pid"));
            if (pid != null && !pid.isBlank()) {
                return pid;
            }
        } catch (IllegalArgumentException e) {
            // not JSON
        }
        String location = response.location();
        if (location != null && location.contains("/configMgr/")) {
            String last = location.substring(location.lastIndexOf('/') + 1);
            int query = last.indexOf('?');
            if (query >= 0) {
                last = last.substring(0, query);
            }
            String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8);
            if (!decoded.isBlank() && !decoded.startsWith("[")) {
                return decoded;
            }
        }
        for (String pid : after) {
            if (!before.contains(pid)) {
                return pid;
            }
        }
        return null;
    }

    static boolean sameValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return Objects.equals(actual, expected);
        }
        if (expected instanceof Collection<?> && actual instanceof Collection<?>) {
            List<String> a = new ArrayList<>();
            for (Object item : (Collection<?>) actual) {
                a.add(String.valueOf(item));
            }
            List<String> e = new ArrayList<>();
            for (Object item : (Collection<?>) expected) {
                e.add(String.valueOf(item));
            }
            return a.equals(e);
        }
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    static String format(Object value) {
        if (value == null) {
            return "(default)";
        }
        return value instanceof Collection<?> ? value.toString() : String.valueOf(value);
    }

    public void shutdown() {
        background.shutdownNow();
    }
}
