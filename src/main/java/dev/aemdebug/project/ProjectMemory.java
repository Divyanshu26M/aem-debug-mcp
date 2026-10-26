package dev.aemdebug.project;

import dev.aemdebug.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project folders the user named, remembered per AEM instance in projects.json (paths only, no secrets). The file is
 * read on every access because each Copilot chat runs its own server process.
 */
final class ProjectMemory {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectMemory.class);
    static final int MAX_ROOTS = 8;

    private final Path file;

    ProjectMemory(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    synchronized List<Path> roots(String environment) {
        List<Path> roots = new ArrayList<>();
        for (Object value : Json.asList(Json.asMap(load().get(environment)).get("roots"))) {
            try {
                roots.add(Paths.get(String.valueOf(value)).toAbsolutePath().normalize());
            } catch (RuntimeException e) {
                LOG.debug("Ignoring remembered project {}: {}", value, e.toString());
            }
        }
        return roots;
    }

    /** Remembers a root as the most recent one for the environment. */
    synchronized void add(String environment, Path root) {
        List<Path> roots = roots(environment);
        roots.remove(root);
        roots.add(0, root);
        while (roots.size() > MAX_ROOTS) {
            roots.remove(roots.size() - 1);
        }
        store(environment, roots);
    }

    synchronized void replace(String environment, Path root) {
        List<Path> roots = new ArrayList<>();
        roots.add(root);
        store(environment, roots);
    }

    synchronized boolean remove(String environment, Path root) {
        List<Path> roots = roots(environment);
        boolean removed = roots.remove(root);
        if (removed) {
            store(environment, roots);
        }
        return removed;
    }

    private Map<String, Object> load() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Object environments = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .get("environments");
            return new LinkedHashMap<>(Json.asMap(environments));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot read {}: {}", file, e.toString());
            return new LinkedHashMap<>();
        }
    }

    private void store(String environment, List<Path> roots) {
        Map<String, Object> environments = load();
        if (roots.isEmpty()) {
            environments.remove(environment);
        } else {
            List<String> values = new ArrayList<>();
            for (Path root : roots) {
                values.add(root.toString());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("roots", values);
            entry.put("updatedAt", System.currentTimeMillis());
            environments.put(environment, entry);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("description", "Local AEM project folders used by aem-debug-mcp, per AEM author URL");
        content.put("environments", environments);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            Files.write(temp, Json.pretty(content).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Cannot write {}: {}", file, e.toString());
        }
    }
}
