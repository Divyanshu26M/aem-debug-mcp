package dev.aemdebug.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Holds the index of the local AEM project (AEM_PROJECT_DIR, MCP roots or the working directory). */
public final class ProjectIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectIndexService.class);
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;

    private final Config config;
    private volatile ProjectIndex index;
    private volatile Path explicitDir;

    public ProjectIndexService(Config config) {
        this.config = config;
    }

    /** The current index, building or refreshing it if needed; null when no project can be found. */
    public ProjectIndex get(ToolContext context) {
        ProjectIndex current = index;
        if (current != null && System.currentTimeMillis() - current.indexedAt < MAX_AGE_MS) {
            return current;
        }
        Path dir = current != null ? current.root : locate(context);
        if (dir == null) {
            return null;
        }
        try {
            return reindex(dir);
        } catch (IOException e) {
            LOG.warn("Indexing {} failed: {}", dir, e.toString());
            return current;
        }
    }

    /** Index without refreshing, or null. */
    public ProjectIndex cached() {
        return index;
    }

    public ProjectIndex reindex(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new ToolException("Project directory does not exist: " + dir);
        }
        long start = System.currentTimeMillis();
        ProjectIndex fresh = ProjectIndexer.index(dir);
        LOG.info("Indexed {} in {} ms: {}", dir, System.currentTimeMillis() - start, fresh.summary());
        this.index = fresh;
        return fresh;
    }

    public void setProjectDir(Path dir) {
        this.explicitDir = dir;
        this.index = null;
    }

    /** Explains how the project directory is chosen; used in error messages. */
    public Path locate(ToolContext context) {
        List<Path> candidates = new ArrayList<>();
        if (explicitDir != null) {
            candidates.add(explicitDir);
        }
        if (config.projectDir() != null) {
            candidates.add(config.projectDir());
        }
        candidates.addAll(context.roots());
        candidates.add(Paths.get(System.getProperty("user.dir")));
        for (Path candidate : candidates) {
            Path project = findProjectRoot(candidate);
            if (project != null) {
                return project;
            }
        }
        return null;
    }

    /** The directory itself or its nearest parent that looks like an AEM Maven project. */
    static Path findProjectRoot(Path start) {
        if (start == null) {
            return null;
        }
        Path dir = start.toAbsolutePath().normalize();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (looksLikeAemProject(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    static boolean looksLikeAemProject(Path dir) {
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
            return false;
        }
        return Files.isDirectory(dir.resolve("ui.apps")) || Files.isDirectory(dir.resolve("core"))
                || Files.isDirectory(dir.resolve("all")) || Files.isDirectory(dir.resolve("ui.config"))
                || Files.isDirectory(dir.resolve("src").resolve("main").resolve("content"));
    }

    public ProjectIndex require(ToolContext context) {
        ProjectIndex current = get(context);
        if (current == null) {
            throw new ToolException("No local AEM project found. Set AEM_PROJECT_DIR in the MCP server environment, "
                    + "open the project as the workspace, or pass projectDir to project_index.");
        }
        return current;
    }
}
