package dev.aemdebug.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Result of indexing a local AEM project, with lookups used by the mapping and JVM tools. */
public final class ProjectIndex {

    public final Path root;
    public final long indexedAt;
    public final List<ProjectModel.Module> modules;
    public final List<ProjectModel.JavaClass> classes;
    public final List<ProjectModel.SlingModel> models;
    public final List<ProjectModel.Component> components;
    public final List<ProjectModel.HtlScript> scripts;
    public final List<ProjectModel.Servlet> servlets;
    public final List<ProjectModel.OsgiConfigFile> osgiConfigs;
    public final List<ProjectModel.ClientLib> clientLibs;
    public final List<String> warnings;
    private final Map<String, ProjectModel.JavaClass> byFqn = new LinkedHashMap<>();

    ProjectIndex(Path root, List<ProjectModel.Module> modules, List<ProjectModel.JavaClass> classes,
                 List<ProjectModel.SlingModel> models, List<ProjectModel.Component> components,
                 List<ProjectModel.HtlScript> scripts, List<ProjectModel.Servlet> servlets,
                 List<ProjectModel.OsgiConfigFile> osgiConfigs, List<ProjectModel.ClientLib> clientLibs,
                 List<String> warnings) {
        this.root = root;
        this.indexedAt = System.currentTimeMillis();
        this.modules = Collections.unmodifiableList(modules);
        this.classes = Collections.unmodifiableList(classes);
        this.models = Collections.unmodifiableList(models);
        this.components = Collections.unmodifiableList(components);
        this.scripts = Collections.unmodifiableList(scripts);
        this.servlets = Collections.unmodifiableList(servlets);
        this.osgiConfigs = Collections.unmodifiableList(osgiConfigs);
        this.clientLibs = Collections.unmodifiableList(clientLibs);
        this.warnings = Collections.unmodifiableList(warnings);
        for (ProjectModel.JavaClass javaClass : classes) {
            byFqn.put(javaClass.fqn, javaClass);
        }
    }

    public ProjectModel.JavaClass classByName(String name) {
        if (name == null) {
            return null;
        }
        String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        ProjectModel.JavaClass exact = byFqn.get(outer);
        if (exact != null) {
            return exact;
        }
        for (ProjectModel.JavaClass javaClass : classes) {
            if (javaClass.simpleName.equals(outer)) {
                return javaClass;
            }
        }
        return null;
    }

    /** Classes whose source file name matches, e.g. HeroModel.java. */
    public List<ProjectModel.JavaClass> classesForSourceFile(String fileName) {
        List<ProjectModel.JavaClass> result = new ArrayList<>();
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        for (ProjectModel.JavaClass javaClass : classes) {
            if (javaClass.file.getFileName().toString().equals(base)) {
                result.add(javaClass);
            }
        }
        return result;
    }

    public List<ProjectModel.SlingModel> modelsForResourceType(String resourceType) {
        List<ProjectModel.SlingModel> result = new ArrayList<>();
        for (ProjectModel.SlingModel model : models) {
            if (model.resourceTypes.contains(resourceType)) {
                result.add(model);
            }
        }
        return result;
    }

    public ProjectModel.SlingModel modelByClass(String className) {
        for (ProjectModel.SlingModel model : models) {
            if (model.className.equals(className) || model.className.endsWith("." + className)) {
                return model;
            }
        }
        return null;
    }

    /** Models implementing an interface (via adapters), e.g. a Core Component delegation. */
    public List<ProjectModel.SlingModel> modelsWithAdapter(String className) {
        List<ProjectModel.SlingModel> result = new ArrayList<>();
        for (ProjectModel.SlingModel model : models) {
            for (String adapter : model.adapters) {
                if (adapter.equals(className) || className.endsWith("." + adapter) || adapter.endsWith("." + className)) {
                    result.add(model);
                }
            }
        }
        return result;
    }

    public ProjectModel.Component component(String resourceType) {
        for (ProjectModel.Component component : components) {
            if (component.resourceType.equals(resourceType)) {
                return component;
            }
        }
        return null;
    }

    public List<ProjectModel.HtlScript> scriptsFor(String resourceType) {
        List<ProjectModel.HtlScript> result = new ArrayList<>();
        for (ProjectModel.HtlScript script : scripts) {
            if (resourceType.equals(script.resourceType)) {
                result.add(script);
            }
        }
        return result;
    }

    /** Local file of an /apps script path such as /apps/mysite/components/hero/hero.html. */
    public ProjectModel.HtlScript scriptByJcrPath(String jcrPath) {
        for (ProjectModel.HtlScript script : scripts) {
            if (script.jcrPath.equals(jcrPath)) {
                return script;
            }
        }
        return null;
    }

    public List<ProjectModel.OsgiConfigFile> osgiConfigsFor(String pid) {
        List<ProjectModel.OsgiConfigFile> result = new ArrayList<>();
        for (ProjectModel.OsgiConfigFile config : osgiConfigs) {
            if (config.pid.equals(pid) || config.name.equals(pid)) {
                result.add(config);
            }
        }
        return result;
    }

    public List<ProjectModel.ClientLib> clientLibsForCategory(String category) {
        List<ProjectModel.ClientLib> result = new ArrayList<>();
        for (ProjectModel.ClientLib lib : clientLibs) {
            if (lib.categories.contains(category)) {
                result.add(lib);
            }
        }
        return result;
    }

    /** Top-level Java packages of the project (e.g. com.mysite), used as default exception watch filters. */
    public List<String> projectPackages() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProjectModel.JavaClass javaClass : classes) {
            String[] parts = javaClass.fqn.split("\\.");
            if (parts.length >= 3) {
                counts.merge(parts[0] + "." + parts[1], 1, Integer::sum);
            }
        }
        List<String> result = new ArrayList<>();
        int total = classes.size();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() * 5 >= total) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** target/classes file of a class, if the project has been built. */
    public Path classFile(String fqn) {
        ProjectModel.JavaClass javaClass = classByName(fqn);
        if (javaClass == null) {
            return null;
        }
        Path file = javaClass.file;
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("src")) {
                Path classes = dir.getParent().resolve("target").resolve("classes")
                        .resolve(fqn.replace('.', '/') + ".class");
                return Files.isRegularFile(classes) ? classes : null;
            }
        }
        return null;
    }

    public ProjectModel.Module moduleOf(Path file) {
        ProjectModel.Module best = null;
        for (ProjectModel.Module module : modules) {
            if (file.startsWith(module.dir) && (best == null || module.dir.getNameCount() > best.dir.getNameCount())) {
                best = module;
            }
        }
        return best;
    }

    public String relative(Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    public String summary() {
        return String.format(Locale.ROOT, "%d modules, %d Java classes, %d Sling Models, %d components, %d HTL scripts, "
                        + "%d servlets, %d OSGi config files, %d client libraries",
                modules.size(), classes.size(), models.size(), components.size(), scripts.size(), servlets.size(),
                osgiConfigs.size(), clientLibs.size());
    }
}
