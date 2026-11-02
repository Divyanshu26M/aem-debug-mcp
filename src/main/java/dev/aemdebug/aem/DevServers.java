package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local front-end dev servers (for example a React or webpack dev server on localhost:3000) and the AEM instance each
 * one proxies to. Mappings come from AEM_DEV_SERVERS or are remembered in dev-servers.json when the agent names the
 * instance for a dev-server URL. The file is read on every access because each chat runs its own server process.
 */
public final class DevServers {

    private static final Logger LOG = LoggerFactory.getLogger(DevServers.class);
    public static final String FILE_NAME = "dev-servers.json";

    /** The instance behind a dev server and where that knowledge comes from ("remembered" or "AEM_DEV_SERVERS"). */
    public static final class Mapping {
        public final String instance;
        public final String source;

        Mapping(String instance, String source) {
            this.instance = instance;
            this.source = source;
        }
    }

    private final Path file;
    private final Map<String, String> configured;

    public DevServers(Path dir, Map<String, String> configured) {
        this.file = dir.resolve(FILE_NAME);
        this.configured = configured;
    }

    /** Mapping for a normalized origin (see HostPolicy.originKey), or null when unknown. */
    public synchronized Mapping lookup(String originKey) {
        String remembered = Json.asString(Json.asMap(load().get(originKey)).get("instance"));
        if (remembered != null) {
            return new Mapping(remembered, "remembered");
        }
        String fromConfig = configured.get(originKey);
        return fromConfig == null ? null : new Mapping(fromConfig, "AEM_DEV_SERVERS");
    }

    /** All known dev servers, remembered ones overriding the configuration. */
    public synchronized Map<String, Mapping> all() {
        Map<String, Mapping> all = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            all.put(entry.getKey(), new Mapping(entry.getValue(), "AEM_DEV_SERVERS"));
        }
        for (Map.Entry<String, Object> entry : load().entrySet()) {
            String instance = Json.asString(Json.asMap(entry.getValue()).get("instance"));
            if (instance != null) {
                all.put(entry.getKey(), new Mapping(instance, "remembered"));
            }
        }
        return all;
    }

    public synchronized void remember(String originKey, String instance) {
        Map<String, Object> servers = load();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("instance", instance);
        entry.put("updatedAt", System.currentTimeMillis());
        servers.put(originKey, entry);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("description", "Local front-end dev servers used by aem-debug-mcp and the AEM instance each "
                + "one proxies to");
        content.put("devServers", servers);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            Files.write(temp, Json.pretty(content).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Cannot write {}: {}", file, e.toString());
        }
    }

    private Map<String, Object> load() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Object servers = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .get("devServers");
            return new LinkedHashMap<>(Json.asMap(servers));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot read {}: {}", file, e.toString());
            return new LinkedHashMap<>();
        }
    }
}
