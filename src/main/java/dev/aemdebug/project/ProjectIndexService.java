package dev.aemdebug.project;

import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and indexes the local AEM project(s) behind the running AEM. Several roots can be used at once (for example two
 * repositories deployed into the same AEM); they are indexed together. Roots come from this chat (project_index
 * workspace/projectDir), AEM_PROJECT_DIR, the folders remembered for this AEM, the client's workspace roots and the
 * server's working folder. Copilot CLI shares neither the chat's folder nor workspace roots with MCP servers, which is
 * why the skill passes the chat's folder explicitly.
 */
public final class ProjectIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectIndexService.class);
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    public static final String MEMORY_FILE = "projects.json";

    /** A project root and why it is used. */
    public record Root(Path path, String source) {
    }

    private final Config config;
    private final ProjectMemory memory;
    private final String environment;
    private final List<Root> chatRoots = new CopyOnWriteArrayList<>();
    private volatile ProjectIndex index;
    private volatile List<Path> indexedPaths = List.of();
    private volatile String lastTrace;

    public ProjectIndexService(Config config) {
        this.config = config;
        this.memory = new ProjectMemory(config.artifactsDir().resolve(MEMORY_FILE));
        this.environment = environmentKey(config);
    }

    /** Key under which named projects are remembered: the author URL (or the publish URL). */
    static String environmentKey(Config config) {
        URI uri = config.authorUrl() != null ? config.authorUrl() : config.publishUrl();
        return uri == null ? "default" : Text.stripTrailingSlash(uri.toString());
    }

    public String environment() {
        return environment;
    }

    public Path memoryFile() {
        return memory.file();
    }

    /** The project roots for this chat, in order of preference; empty when none is known. */
    public List<Root> roots(ToolContext context) {
        List<Root> roots = new ArrayList<>();
        StringBuilder trace = new StringBuilder("chat ").append(paths(chatRoots));
        for (Root root : chatRoots) {
            addRoot(roots, root);
        }
        Path configured = config.projectDir();
        if (configured != null) {
            Path root = detectRoot(configured, true);
            trace.append("; AEM_PROJECT_DIR ").append(configured).append(" -> ").append(root);
            if (root != null) {
                addRoot(roots, new Root(root, "AEM_PROJECT_DIR"));
            }
        }
        for (Path remembered : memory.roots(environment)) {
            Path root = detectRoot(remembered, true);
            trace.append("; remembered ").append(remembered).append(" -> ").append(root);
            if (root != null) {
                addRoot(roots, new Root(root, "remembered for " + environment));
            }
        }
        List<Path> clientRoots = context == null ? List.of() : context.roots();
        trace.append("; client roots ").append(clientRoots);
        for (Path clientRoot : clientRoots) {
            Path root = detectRoot(clientRoot, false);
            if (root != null) {
                addRoot(roots, new Root(root, "client workspace"));
            }
        }
        Path workingFolder = Paths.get(System.getProperty("user.dir"));
        Path workingRoot = detectRoot(workingFolder, false);
        trace.append("; working folder ").append(workingFolder).append(" -> ").append(workingRoot);
        if (workingRoot != null) {
            addRoot(roots, new Root(workingRoot, "server working folder"));
        }
        String summary = trace + " => " + paths(roots);
        if (!summary.equals(lastTrace)) {
            lastTrace = summary;
            LOG.info("Project roots: {}", summary);
        }
        return roots;
    }

    /** The index of all roots, refreshed every 5 minutes or when the roots change; null when no root is known. */
    public synchronized ProjectIndex get(ToolContext context) {
        List<Path> paths = paths(roots(context));
        if (paths.isEmpty()) {
            return null;
        }
        ProjectIndex current = index;
        if (current != null && paths.equals(indexedPaths)
                && System.currentTimeMillis() - current.indexedAt < MAX_AGE_MS) {
            return current;
        }
        return build(paths);
    }

    /** Re-scans all roots now. */
    public synchronized ProjectIndex refresh(ToolContext context) {
        List<Path> paths = paths(roots(context));
        return paths.isEmpty() ? null : build(paths);
    }

    /** Index without refreshing, or null. */
    public ProjectIndex cached() {
        return index;
    }

    public ProjectIndex require(ToolContext context) {
        ProjectIndex current = get(context);
        if (current == null) {
            throw new ToolException(notFoundMessage());
        }
        return current;
    }

    public String notFoundMessage() {
        return "No local AEM project is known for " + environment + ". Ask the user where the source of the AEM "
                + "project(s) deployed on this AEM is (a repository folder; one aggregator repository or several "
                + "repositories are fine), then call project_index projectDir=<folder> for each. It is remembered for "
                + "later chats. project_index workspace=<your working folder> picks up a project the chat runs in.";
    }

    /** Uses the chat's working folder for this chat when it is, or is inside, an AEM project; returns the root. */
    public Path useWorkspace(Path folder) {
        Path root = detectRoot(folder, false);
        if (root != null) {
            addChatRoot(new Root(root, "this chat's folder"));
        }
        return root;
    }

    /** Uses a project the user named for this chat and remembers it for this AEM; returns the project root. */
    public synchronized Path addProject(Path folder, boolean replace) {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new ToolException("Folder does not exist: " + folder);
        }
        Path root = detectRoot(folder, true);
        if (root == null) {
            throw new ToolException(folder + " is not inside a Maven project (no pom.xml in it or its parent folders). "
                    + "Name the folder of the AEM project's repository.");
        }
        if (replace) {
            chatRoots.clear();
            memory.replace(environment, root);
        } else {
            memory.add(environment, root);
        }
        addChatRoot(new Root(root, "named in this chat"));
        return root;
    }

    /** Stops using a project in this chat and forgets it for this AEM. */
    public synchronized boolean removeProject(Path folder) {
        Path normalized = folder.toAbsolutePath().normalize();
        Path root = Files.isDirectory(normalized) ? detectRoot(normalized, true) : null;
        boolean removed = chatRoots.removeIf(r -> r.path().equals(normalized) || r.path().equals(root));
        removed |= memory.remove(environment, normalized);
        if (root != null && !root.equals(normalized)) {
            removed |= memory.remove(environment, root);
        }
        return removed;
    }

    private synchronized void addChatRoot(Root root) {
        List<Root> updated = new ArrayList<>(chatRoots);
        addRoot(updated, root);
        chatRoots.clear();
        chatRoots.addAll(updated);
    }

    private ProjectIndex build(List<Path> paths) {
        List<ProjectIndex> parts = new ArrayList<>();
        for (Path path : paths) {
            try {
                long start = System.currentTimeMillis();
                ProjectIndex part = ProjectIndexer.index(path);
                LOG.info("Indexed {} in {} ms: {}", path, System.currentTimeMillis() - start, part.summary());
                parts.add(part);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Indexing {} failed: {}", path, e.toString());
            }
        }
        if (parts.isEmpty()) {
            return paths.equals(indexedPaths) ? index : null;
        }
        ProjectIndex merged = ProjectIndex.merge(parts);
        index = merged;
        indexedPaths = List.copyOf(paths);
        return merged;
    }

    static List<Path> paths(List<Root> roots) {
        List<Path> paths = new ArrayList<>();
        for (Root root : roots) {
            paths.add(root.path());
        }
        return paths;
    }

    /** Adds a root unless a listed root already covers it; a root covering listed ones takes the first one's place. */
    static void addRoot(List<Root> roots, Root candidate) {
        for (Root existing : roots) {
            if (candidate.path().startsWith(existing.path())) {
                return;
            }
        }
        int position = -1;
        for (int i = roots.size() - 1; i >= 0; i--) {
            if (roots.get(i).path().startsWith(candidate.path())) {
                roots.remove(i);
                position = i;
            }
        }
        if (position < 0) {
            roots.add(candidate);
        } else {
            roots.add(position, candidate);
        }
    }

    /** Strict detection: the AEM project root for a folder, or null. */
    static Path findProjectRoot(Path start) {
        return detectRoot(start, false);
    }

    /**
     * The project root for a folder: the nearest Maven project at or above it, widened to the outermost aggregator that
     * lists it as a module. Accepted when it is, or aggregates, an AEM project; with {@code lenient} any Maven project.
     */
    public static Path detectRoot(Path start, boolean lenient) {
        Path dir;
        try {
            dir = start == null ? null : start.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        Path nearest = null;
        int levels = 0;
        for (Path candidate = dir; candidate != null && levels <= 12; candidate = candidate.getParent(), levels++) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                nearest = candidate;
                break;
            }
        }
        if (nearest == null) {
            return null;
        }
        Path top = nearest;
        for (int i = 0; i < 6; i++) {
            Path parent = top.getParent();
            if (parent == null || !listsModule(parent, top)) {
                break;
            }
            top = parent;
        }
        if (containsAemProject(top, 3)) {
            return top;
        }
        if (!top.equals(nearest) && containsAemProject(nearest, 3)) {
            return nearest;
        }
        return lenient ? top : null;
    }

    static boolean looksLikeAemProject(Path dir) {
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
            return false;
        }
        return Files.isDirectory(dir.resolve("ui.apps")) || Files.isDirectory(dir.resolve("core"))
                || Files.isDirectory(dir.resolve("all")) || Files.isDirectory(dir.resolve("ui.config"))
                || Files.isDirectory(dir.resolve("src").resolve("main").resolve("content"));
    }

    /** True when the folder is an AEM project or aggregates one within {@code depth} module levels. */
    static boolean containsAemProject(Path dir, int depth) {
        if (looksLikeAemProject(dir)) {
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        for (String module : modules(dir)) {
            Path child = dir.resolve(module).normalize();
            if (child.startsWith(dir) && !child.equals(dir) && Files.isDirectory(child)
                    && containsAemProject(child, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean listsModule(Path parent, Path child) {
        for (String module : modules(parent)) {
            try {
                if (parent.resolve(module).normalize().equals(child)) {
                    return true;
                }
            } catch (RuntimeException e) {
                LOG.debug("Ignoring module {} of {}: {}", module, parent, e.toString());
            }
        }
        return false;
    }

    /** Module folders listed in a folder's pom.xml (all profiles), comments removed. */
    static List<String> modules(Path dir) {
        List<String> modules = new ArrayList<>();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return modules;
        }
        try {
            String text = COMMENT.matcher(new String(Files.readAllBytes(pom), StandardCharsets.UTF_8)).replaceAll("");
            Matcher matcher = MODULE.matcher(text);
            while (matcher.find()) {
                String module = matcher.group(1).trim().replace('\\', '/');
                if (!module.isEmpty() && !modules.contains(module)) {
                    modules.add(module);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cannot read modules of {}: {}", pom, e.toString());
        }
        return modules;
    }
}
