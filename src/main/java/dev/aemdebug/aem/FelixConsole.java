package dev.aemdebug.aem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only access to the Felix web console of local AEM: bundles, DS components, configurations and status
 * printers. Parsers are static and tolerant so they can be verified with fixtures.
 */
public final class FelixConsole {

    private static final Logger LOG = LoggerFactory.getLogger(FelixConsole.class);

    private final AemClient aem;

    public FelixConsole(AemClient aem) {
        this.aem = aem;
    }

    // ------------------------------------------------------------------ bundles

    public List<BundleInfo> bundles(ToolContext context, AemInstance instance) {
        return parseBundles(aem.getJsonObject(context, instance, "/system/console/bundles.json"));
    }

    public static List<BundleInfo> parseBundles(Map<String, Object> json) {
        List<BundleInfo> bundles = new ArrayList<>();
        for (Object item : Json.asList(json.get("data"))) {
            Map<String, Object> b = Json.asMap(item);
            bundles.add(new BundleInfo(
                    Json.asLong(b.get("id"), -1),
                    Json.asString(b.get("symbolicName")),
                    Json.asString(b.get("name")),
                    Json.asString(b.get("version")),
                    Json.asString(b.get("state")),
                    (int) Json.asLong(b.get("stateRaw"), -1),
                    Json.asBoolean(b.get("fragment"), false)));
        }
        return bundles;
    }

    /** Key/value properties of one bundle (e.g. "Last Modification", "Bundle Location"). */
    public Map<String, String> bundleDetail(ToolContext context, AemInstance instance, long bundleId) {
        Map<String, Object> json = aem.getJsonObject(context, instance, "/system/console/bundles/" + bundleId + ".json");
        return parseBundleDetail(json);
    }

    public static Map<String, String> parseBundleDetail(Map<String, Object> json) {
        Map<String, String> result = new LinkedHashMap<>();
        Object first = Json.at(json, "data", "0");
        Map<String, Object> bundle = Json.asMap(first);
        for (String key : new String[] {"id", "name", "symbolicName", "version", "state"}) {
            if (bundle.get(key) != null) {
                result.put(key, Json.asString(bundle.get(key)));
            }
        }
        for (Object prop : Json.asList(bundle.get("props"))) {
            Map<String, Object> p = Json.asMap(prop);
            String key = Json.asString(p.get("key"));
            Object value = p.get("value");
            if (key == null) {
                continue;
            }
            if (value instanceof List<?>) {
                List<String> parts = new ArrayList<>();
                for (Object part : (List<?>) value) {
                    parts.add(String.valueOf(part));
                }
                result.put(key, String.join(", ", parts));
            } else {
                result.put(key, value == null ? "" : String.valueOf(value));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ DS components

    public static final class ComponentState {
        public final String id;
        public final String name;
        public final String state;
        public final long bundleId;
        public final String pid;

        public ComponentState(String id, String name, String state, long bundleId, String pid) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.bundleId = bundleId;
            this.pid = pid;
        }

        public boolean problematic() {
            String s = state == null ? "" : state.toLowerCase();
            return s.contains("unsatisfied") || s.contains("failed");
        }
    }

    public List<ComponentState> components(ToolContext context, AemInstance instance) {
        return parseComponents(aem.getJsonObject(context, instance, "/system/console/components.json"));
    }

    public static List<ComponentState> parseComponents(Map<String, Object> json) {
        List<ComponentState> result = new ArrayList<>();
        for (Object item : Json.asList(json.get("data"))) {
            Map<String, Object> c = Json.asMap(item);
            result.add(new ComponentState(Json.asString(c.get("id")), Json.asString(c.get("name")),
                    Json.asString(c.get("state")), Json.asLong(c.get("bundleId"), -1), Json.asString(c.get("pid"))));
        }
        return result;
    }

    // ------------------------------------------------------------------ status printers

    /** Text output of a web console status printer, e.g. "slingsettings" or "Configurations". */
    public String status(ToolContext context, AemInstance instance, String printer) {
        String encoded = Text.urlEncode(printer).replace("+", "%20");
        return aem.getText(context, instance, "/system/console/status-" + encoded + ".txt");
    }

    public SlingSettings slingSettings(ToolContext context, AemInstance instance) {
        return SlingSettings.parse(status(context, instance, "slingsettings"));
    }

    public SlingModelRegistry slingModels(ToolContext context, AemInstance instance) {
        return SlingModelRegistry.parse(status(context, instance, "slingmodels"));
    }

    /** Best-effort AEM product version; returns null when no source is available. */
    public String productVersion(ToolContext context, AemInstance instance) {
        try {
            AemResponse overview = aem.get(context, instance, "/libs/granite/operations/content/systemoverview/export.json");
            if (overview.ok() && overview.contentType().contains("json")) {
                String version = parseSystemOverviewVersion(overview.jsonObject());
                if (version != null) {
                    return version;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("system overview not available: {}", e.getMessage());
        }
        try {
            AemResponse info = aem.get(context, instance, "/system/console/status-productinfo.txt");
            if (info.ok()) {
                String version = parseProductInfo(info.text());
                if (version != null) {
                    return version;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("productinfo not available: {}", e.getMessage());
        }
        return null;
    }

    static String parseSystemOverviewVersion(Map<String, Object> json) {
        Map<String, Object> instanceInfo = Json.asMap(json.get("Instance"));
        for (Map.Entry<String, Object> entry : instanceInfo.entrySet()) {
            if (entry.getKey().toLowerCase().contains("experience manager")) {
                return entry.getKey() + " " + entry.getValue();
            }
        }
        return null;
    }

    static String parseProductInfo(String text) {
        Matcher m = Pattern.compile("(?im)^\\s*(Adobe Experience Manager[^\\n]*)$").matcher(text);
        if (m.find()) {
            String line = m.group(1).trim();
            Matcher v = Pattern.compile("(?i)version\\s*[:=]?\\s*(\\S+)").matcher(text.substring(m.end()));
            if (!line.matches(".*\\d.*") && v.find()) {
                return line + " " + v.group(1);
            }
            return line;
        }
        return null;
    }

    // ------------------------------------------------------------------ configurations

    /**
     * Reads one configuration via /system/console/configMgr/{pid}.json. Returns null when no explicit
     * configuration exists (metatype defaults apply).
     */
    public OsgiConfiguration config(ToolContext context, AemInstance instance, String pid) {
        AemResponse response = aem.get(context, instance,
                "/system/console/configMgr/" + Text.urlEncode(pid).replace("+", "%20") + ".json");
        if (response.ok() && looksLikeJson(response)) {
            List<OsgiConfiguration> configs = parseConfigMgrJson(response.json());
            for (OsgiConfiguration config : configs) {
                if (pid.equals(config.pid)) {
                    return config;
                }
            }
            return configs.size() == 1 && configs.get(0).pid == null ? configs.get(0) : null;
        }
        for (OsgiConfiguration config : configurations(context, instance)) {
            if (pid.equals(config.pid)) {
                return config;
            }
        }
        return null;
    }

    /** All configurations of a factory PID using an LDAP filter on configMgr, falling back to the printer. */
    public List<OsgiConfiguration> factoryConfigurations(ToolContext context, AemInstance instance, String factoryPid) {
        String filter = "(service.factoryPid=" + factoryPid + ")";
        AemResponse response = aem.get(context, instance,
                "/system/console/configMgr/" + Text.urlEncode(filter).replace("+", "%20") + ".json");
        List<OsgiConfiguration> result = new ArrayList<>();
        if (response.ok() && looksLikeJson(response)) {
            for (OsgiConfiguration config : parseConfigMgrJson(response.json())) {
                if (config.pid != null && config.pid.startsWith(factoryPid)) {
                    result.add(config);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        for (OsgiConfiguration config : configurations(context, instance)) {
            if (factoryPid.equals(config.factoryPid) || (config.pid != null && config.pid.startsWith(factoryPid + "."))
                    || (config.pid != null && config.pid.startsWith(factoryPid + "~"))) {
                result.add(config);
            }
        }
        return result;
    }

    public List<OsgiConfiguration> configurations(ToolContext context, AemInstance instance) {
        return parseConfigurationsText(status(context, instance, "Configurations"));
    }

    private static boolean looksLikeJson(AemResponse response) {
        String body = response.text().trim();
        return body.startsWith("[") || body.startsWith("{");
    }

    /** Parses the configMgr JSON (an array of configuration objects, or a single object). */
    public static List<OsgiConfiguration> parseConfigMgrJson(Object json) {
        List<OsgiConfiguration> result = new ArrayList<>();
        List<Object> items = json instanceof List<?> ? Json.asList(json) : List.of(json);
        for (Object item : items) {
            Map<String, Object> c = Json.asMap(item);
            if (c.isEmpty()) {
                continue;
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            Object props = c.get("properties");
            for (Map.Entry<String, Object> entry : Json.asMap(props).entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?>) {
                    Map<String, Object> descriptor = Json.asMap(value);
                    if (descriptor.containsKey("is_set") && !Json.asBoolean(descriptor.get("is_set"), true)) {
                        continue;
                    }
                    if (descriptor.containsKey("values")) {
                        properties.put(entry.getKey(), descriptor.get("values"));
                    } else {
                        properties.put(entry.getKey(), descriptor.get("value"));
                    }
                } else {
                    properties.put(entry.getKey(), value);
                }
            }
            result.add(new OsgiConfiguration(Json.asString(c.get("pid")),
                    Json.asString(c.containsKey("factoryPid") ? c.get("factoryPid") : c.get("factoryPID")),
                    Json.asString(c.get("bundleLocation")), properties, "configMgr"));
        }
        return result;
    }

    private static final Pattern PID_LINE = Pattern.compile("^\\s*PID\\s*[=:]\\s*(\\S+)\\s*$");
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s+([^=]+?)\\s*=\\s*(.*)$");

    /** Parses the "Configurations" status printer text output. */
    public static List<OsgiConfiguration> parseConfigurationsText(String text) {
        List<OsgiConfiguration> result = new ArrayList<>();
        String pid = null;
        String factoryPid = null;
        String location = null;
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            Matcher pidMatcher = PID_LINE.matcher(raw);
            if (pidMatcher.matches()) {
                if (pid != null) {
                    result.add(new OsgiConfiguration(pid, factoryPid, location, properties, "status-Configurations"));
                }
                pid = pidMatcher.group(1);
                factoryPid = null;
                location = null;
                properties = new LinkedHashMap<>();
                continue;
            }
            if (pid == null) {
                continue;
            }
            Matcher prop = PROPERTY_LINE.matcher(raw);
            if (!prop.matches()) {
                continue;
            }
            String key = prop.group(1).trim();
            String value = prop.group(2).trim();
            String normalized = key.replace(" ", "").toLowerCase();
            if (normalized.equals("factorypid") || normalized.equals("service.factorypid")) {
                factoryPid = value;
            } else if (normalized.equals("bundlelocation")) {
                location = value;
            } else if (!normalized.equals("service.pid") && !normalized.equals("pid")) {
                properties.put(key, value);
            }
        }
        if (pid != null) {
            result.add(new OsgiConfiguration(pid, factoryPid, location, properties, "status-Configurations"));
        }
        return result;
    }
}
