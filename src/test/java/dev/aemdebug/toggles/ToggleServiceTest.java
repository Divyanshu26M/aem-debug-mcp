package dev.aemdebug.toggles;

import dev.aemdebug.aem.AemClient;
import dev.aemdebug.aem.AemHttpClient;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.CookieJar;
import dev.aemdebug.config.Config;
import dev.aemdebug.testsupport.FakeAem;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the Felix configMgr protocol against a stateful fake. */
class ToggleServiceTest {

    @TempDir
    Path temp;

    private FakeAem fake;
    private final Map<String, Map<String, String>> configs = new ConcurrentHashMap<>();
    private final List<String> posts = new ArrayList<>();
    private int created;

    @BeforeEach
    void setUp() throws Exception {
        fake = FakeAem.start();
        fake.json("/libs/granite/csrf/token.json", "{\"token\":\"csrf-123\"}");
        String wcm = ToggleService.WCM_DEBUG_PID;
        fake.route("/system/console/configMgr/" + wcm + ".json", r -> configJson(wcm));
        fake.route("/system/console/configMgr/" + wcm, r -> {
            posts.add("POST " + wcm + " " + r.form);
            if ("true".equals(r.form.get("delete"))) {
                configs.remove(wcm);
            } else {
                apply(wcm, r.form);
            }
            return new FakeAem.Reply(200, "application/json", "{\"status\":true}");
        });
        fake.route("/system/console/configMgr/%5BTemporary%20PID%20replaced%20by%20real%20PID%20upon%20save%5D", r -> {
            String pid = ToggleService.LOG_FACTORY + ".fake" + (++created);
            apply(pid, r.form);
            posts.add("CREATE " + pid + " " + r.form);
            String route = "/system/console/configMgr/" + pid;
            fake.route(route, r2 -> {
                posts.add("POST " + pid + " " + r2.form);
                if ("true".equals(r2.form.get("delete"))) {
                    configs.remove(pid);
                }
                return new FakeAem.Reply(200, "application/json", "{\"status\":true}");
            });
            return new FakeAem.Reply(200, "application/json", "{\"status\":true,\"pid\":\"" + pid + "\"}");
        });
        String filter = Text.urlEncode("(service.factoryPid=" + ToggleService.LOG_FACTORY + ")").replace("+", "%20");
        fake.route("/system/console/configMgr/" + filter + ".json", r -> {
            List<Object> list = new ArrayList<>();
            for (String pid : configs.keySet()) {
                if (pid.startsWith(ToggleService.LOG_FACTORY)) {
                    list.add(Map.of("pid", pid));
                }
            }
            return new FakeAem.Reply(200, "application/json", Json.write(list));
        });
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private void apply(String pid, Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>(configs.getOrDefault(pid, Map.of()));
        for (String key : form.getOrDefault("propertylist", "").split(",")) {
            if (!key.isBlank()) {
                values.put(key, form.get(key));
            }
        }
        configs.put(pid, values);
    }

    private FakeAem.Reply configJson(String pid) {
        Map<String, String> values = configs.get(pid);
        if (values == null) {
            return new FakeAem.Reply(200, "application/json", "[]");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            properties.put(entry.getKey(), Map.of("is_set", true, "value", entry.getValue()));
        }
        return new FakeAem.Reply(200, "application/json", Json.write(List.of(Map.of("pid", pid, "properties", properties))));
    }

    private ToggleService service(RevertJournal journal) {
        Config config = Config.builder().authorUrl(fake.baseUrl()).credentials("admin", "admin").artifactsDir(temp)
                .build();
        AemHttpClient http = new AemHttpClient(new CookieJar());
        AuthManager auth = new AuthManager(config, http);
        AemInstances instances = new AemInstances(config);
        return new ToggleService(new AemClient(instances, http, auth), instances, journal);
    }

    @Test
    void enablesAndRevertsWcmDebugFilterWithJournal() {
        Path journalFile = temp.resolve("journal.json");
        ToggleService toggles = service(new RevertJournal(journalFile));
        AemInstance author = new AemInstances(Config.builder().authorUrl(fake.baseUrl()).build()).author();
        ToolContext context = ToolContext.detached();

        ToggleService.Plan plan = toggles.plan(context, author, ToggleService.WCM_DEBUG, null, null, 200, null);
        assertThat(plan.existed).isFalse();
        assertThat(plan.describe()).contains("Modify PID com.day.cq.wcm.core.impl.WCMDebugFilter (no explicit")
                .contains("wcmdbgfilter.enabled: (default) -> true").contains("delete the configuration again");

        RevertJournal.Entry entry = toggles.apply(context, plan);
        assertThat(entry.id).isEqualTo("t1");
        assertThat(entry.created).isTrue();
        assertThat(configs.get(ToggleService.WCM_DEBUG_PID)).containsEntry("wcmdbgfilter.enabled", "true");
        assertThat(posts.get(0)).contains("propertylist=wcmdbgfilter.enabled").contains("apply=true");
        assertThat(Files.exists(journalFile)).isTrue();
        assertThat(new RevertJournal(journalFile).active()).hasSize(1);

        assertThat(toggles.plan(context, author, ToggleService.WCM_DEBUG, null, null, 200, null).noOp()).isTrue();

        List<String> lines = toggles.revertAll(context, null);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("t1").contains("configuration deleted");
        assertThat(configs).doesNotContainKey(ToggleService.WCM_DEBUG_PID);
        assertThat(new RevertJournal(journalFile).active()).isEmpty();
    }

    @Test
    void createsAndDeletesLoggerConfiguration() {
        ToggleService toggles = service(new RevertJournal(temp.resolve("journal.json")));
        AemInstance author = new AemInstances(Config.builder().authorUrl(fake.baseUrl()).build()).author();
        ToolContext context = ToolContext.detached();
        ToggleService.Plan plan = toggles.plan(context, author, ToggleService.LOG_LEVEL, List.of("com.mysite"), "TRACE",
                200, null);
        assertThat(plan.create).isTrue();
        assertThat(plan.describe()).contains("Create configuration of factory " + ToggleService.LOG_FACTORY)
                .contains("org.apache.sling.commons.log.level = trace");
        RevertJournal.Entry entry = toggles.apply(context, plan);
        assertThat(entry.pid).isEqualTo(ToggleService.LOG_FACTORY + ".fake1");
        assertThat(configs.get(entry.pid)).containsEntry("org.apache.sling.commons.log.names", "com.mysite");
        assertThat(toggles.revert(context, entry)).contains("configuration deleted");
        assertThat(configs).doesNotContainKey(entry.pid);
    }

    @Test
    void revertsTogglesLeftOverByPreviousRunAfterLogin() throws Exception {
        configs.put(ToggleService.WCM_DEBUG_PID, new LinkedHashMap<>(Map.of("wcmdbgfilter.enabled", "true")));
        Path journalFile = temp.resolve("journal.json");
        RevertJournal previous = new RevertJournal(journalFile);
        RevertJournal.Entry leftOver = new RevertJournal.Entry();
        leftOver.id = "t7";
        leftOver.toggle = ToggleService.WCM_DEBUG;
        leftOver.instance = "author";
        leftOver.origin = new AemInstances(Config.builder().authorUrl(fake.baseUrl()).build()).author().origin();
        leftOver.pid = ToggleService.WCM_DEBUG_PID;
        leftOver.created = true;
        leftOver.appliedAt = System.currentTimeMillis();
        previous.add(leftOver);

        ToggleService toggles = service(new RevertJournal(journalFile));
        assertThat(toggles.fromPreviousRun()).containsExactly("t7");
        toggles.recover();
        long deadline = System.currentTimeMillis() + 10_000;
        while (configs.containsKey(ToggleService.WCM_DEBUG_PID) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(configs).doesNotContainKey(ToggleService.WCM_DEBUG_PID);
        assertThat(new RevertJournal(journalFile).active()).isEmpty();
    }

    @Test
    void formEncodesMultiValuesAndDetectsNewPids() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("names", List.of("a", "b"));
        values.put("level", "debug");
        List<String[]> form = ToggleService.form(values, "factory.x", false);
        List<String> flat = new ArrayList<>();
        for (String[] pair : form) {
            flat.add(pair[0] + "=" + pair[1]);
        }
        assertThat(flat).contains("factoryPid=factory.x", "propertylist=names,level", "names=a", "names=b", "level=debug");
        assertThat(ToggleService.sameValue(List.of("x"), List.of("x"))).isTrue();
        assertThat(ToggleService.sameValue("TRUE", Boolean.TRUE)).isTrue();
        assertThat(ToggleService.sameValue(null, Boolean.TRUE)).isFalse();
    }
}
