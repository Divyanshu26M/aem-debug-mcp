package dev.aemdebug.project;

import dev.aemdebug.aem.BundleInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Checks whether the OSGi bundles built by the local project(s) are installed in an AEM instance. */
public final class ProjectMatch {

    private ProjectMatch() {
    }

    /** One line per project root, e.g. "mysite: deployed (mysite.core Active)". */
    public static List<String> describe(ProjectIndex index, List<ProjectIndexService.Root> roots,
                                        List<BundleInfo> bundles) {
        Map<String, BundleInfo> installed = new LinkedHashMap<>();
        for (BundleInfo bundle : bundles) {
            if (bundle.symbolicName != null) {
                installed.put(bundle.symbolicName, bundle);
            }
        }
        List<String> lines = new ArrayList<>();
        for (ProjectIndexService.Root root : roots) {
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> similar = new ArrayList<>();
            for (ProjectModel.Module module : index.modules) {
                if (!module.dir.startsWith(root.path())) {
                    continue;
                }
                List<String> names = expectedBundleNames(module);
                if (names.isEmpty()) {
                    continue;
                }
                BundleInfo match = find(installed, names, module.artifactId);
                if (match != null) {
                    found.add(match.symbolicName + " " + match.state);
                } else {
                    missing.add(names.get(0));
                    for (String name : similar(installed, module.artifactId)) {
                        if (!similar.contains(name)) {
                            similar.add(name);
                        }
                    }
                }
            }
            Path fileName = root.path().getFileName();
            String name = fileName == null ? root.path().toString() : fileName.toString();
            if (found.isEmpty() && missing.isEmpty()) {
                lines.add(name + ": builds no OSGi bundles, not checked");
            } else if (missing.isEmpty()) {
                lines.add(name + ": deployed (" + String.join(", ", found) + ")");
            } else if (found.isEmpty()) {
                lines.add(name + ": NOT deployed here - none of its bundles is installed (expected "
                        + String.join(", ", missing) + ")"
                        + (similar.isEmpty() ? "" : "; similar installed bundles: " + String.join(", ", similar))
                        + ". Wrong folder, or the project is not deployed on this instance.");
            } else {
                lines.add(name + ": partly deployed - installed " + String.join(", ", found) + "; not installed "
                        + String.join(", ", missing));
            }
        }
        return lines;
    }

    /** Bundle symbolic names a module may produce: the configured name, else the bnd-maven-plugin defaults. */
    static List<String> expectedBundleNames(ProjectModel.Module module) {
        List<String> names = new ArrayList<>();
        if (module.bundleSymbolicName != null) {
            String name = module.bundleSymbolicName
                    .replace("${project.artifactId}", String.valueOf(module.artifactId))
                    .replace("${project.groupId}", String.valueOf(module.groupId));
            if (!name.contains("${")) {
                names.add(name);
                return names;
            }
        }
        if (module.artifactId == null || !buildsBundle(module)) {
            return names;
        }
        names.add(module.artifactId);
        if (module.groupId != null) {
            names.add(module.groupId + "." + module.artifactId);
        }
        return names;
    }

    static boolean buildsBundle(ProjectModel.Module module) {
        if ("bundle".equals(module.packaging)) {
            return true;
        }
        if (module.packaging != null && !module.packaging.equals("jar")) {
            return false;
        }
        if (!Files.isDirectory(module.dir.resolve("src").resolve("main").resolve("java"))) {
            return false;
        }
        if (Files.isRegularFile(module.dir.resolve("bnd.bnd"))) {
            return true;
        }
        String pom = read(module.dir.resolve("pom.xml"));
        return pom.contains("bnd-maven-plugin") || pom.contains("maven-bundle-plugin");
    }

    private static BundleInfo find(Map<String, BundleInfo> installed, List<String> names, String artifactId) {
        for (String name : names) {
            BundleInfo bundle = installed.get(name);
            if (bundle != null) {
                return bundle;
            }
        }
        if (artifactId != null) {
            for (BundleInfo bundle : installed.values()) {
                if (bundle.symbolicName.endsWith("." + artifactId)) {
                    return bundle;
                }
            }
        }
        return null;
    }

    private static List<String> similar(Map<String, BundleInfo> installed, String artifactId) {
        List<String> similar = new ArrayList<>();
        if (artifactId == null) {
            return similar;
        }
        String base = artifactId.contains(".") ? artifactId.substring(0, artifactId.indexOf('.')) : artifactId;
        if (base.length() < 4) {
            return similar;
        }
        String needle = base.toLowerCase(Locale.ROOT);
        for (BundleInfo bundle : installed.values()) {
            if (bundle.symbolicName.toLowerCase(Locale.ROOT).contains(needle) && similar.size() < 3) {
                similar.add(bundle.symbolicName);
            }
        }
        return similar;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
