package dev.aemdebug.tools.impl;

import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** project_index: scan the local AEM project. */
public final class ProjectIndexTool extends AbstractTool {

    private final Services s;

    public ProjectIndexTool(Services services) {
        super("project_index", "Index local AEM project",
                "Scans the local AEM project source (AEM_PROJECT_DIR, the client workspace root, or projectDir): Sling "
                        + "Models with their resource types and injected fields (and the JCR names they look up), "
                        + "components and HTL scripts with data-sly-use objects, servlets, OSGi config files per run "
                        + "mode, client libraries (categories, allowProxy) and Maven modules/bundles. The index maps "
                        + "AEM runtime data back to local source files for the other tools.",
                Schema.object()
                        .str("projectDir", "Project root to index (remembered for later calls)")
                        .bool("refresh", "Re-scan even if a recent index exists (default false)")
                        .enumStr("show", "What to list (default summary)", "summary", "models", "components",
                                "servlets", "configs", "clientlibs", "all")
                        .str("filter", "Only list items containing this text"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) throws IOException {
        String dir = args.str("projectDir");
        ProjectIndex index;
        if (dir != null) {
            Path path = Paths.get(dir);
            if (!Files.isDirectory(path)) {
                throw new ToolException("projectDir does not exist: " + dir);
            }
            s.projects.setProjectDir(path);
            index = s.projects.reindex(path);
        } else if (args.bool("refresh", false)) {
            Path located = s.projects.locate(context);
            if (located == null) {
                index = s.projects.require(context);
            } else {
                index = s.projects.reindex(located);
            }
        } else {
            index = s.projects.require(context);
        }
        String show = args.choice("show", "summary", "summary", "models", "components", "servlets", "configs",
                "clientlibs", "all");
        String filter = args.str("filter");
        StringBuilder out = new StringBuilder("### Project ").append(index.root).append('\n');
        out.append(index.summary()).append('\n');
        out.append("- project packages: ").append(index.projectPackages()).append('\n');
        for (ProjectModel.Module module : index.modules) {
            if (module.artifactId == null) {
                continue;
            }
            out.append("- module ").append(module.dir.equals(index.root) ? "(root)" : index.relative(module.dir))
                    .append(": ").append(module.groupId).append(':')
                    .append(module.artifactId).append(module.packaging == null ? "" : " (" + module.packaging + ")");
            if (module.bundleSymbolicName != null) {
                out.append(" BSN ").append(module.bundleSymbolicName);
            }
            Path classes = module.dir.resolve("target").resolve("classes");
            if (Files.isDirectory(classes)) {
                out.append(" - built ").append(Text.formatDateTime(Files.getLastModifiedTime(classes).toMillis()));
            }
            out.append('\n');
        }
        boolean all = show.equals("all");
        if (all || show.equals("models")) {
            out.append("\n#### Sling Models\n");
            for (ProjectModel.SlingModel model : index.models) {
                if (!matches(filter, model.className, model.resourceTypes.toString())) {
                    continue;
                }
                out.append("- ").append(model.className).append(" (").append(index.relative(model.file)).append(':')
                        .append(model.modelAnnotationLine).append(")\n    resourceType ").append(model.resourceTypes)
                        .append(", adaptables ").append(model.adaptables).append(", adapters ").append(model.adapters)
                        .append(model.exporter ? ", exporter" : "").append(model.optionalByDefault ? ", OPTIONAL default" : "")
                        .append('\n');
                for (ProjectModel.InjectedField field : model.fields) {
                    out.append("    - ").append(field.name).append(" <- @").append(field.annotation);
                    if (field.nameOverride != null) {
                        out.append("(name=\"").append(field.nameOverride).append("\")");
                    }
                    out.append(" ").append(field.type).append(field.optional ? " optional" : " REQUIRED")
                            .append(" line ").append(field.line).append('\n');
                }
                if (model.postConstructMethod != null) {
                    out.append("    - @PostConstruct ").append(model.postConstructMethod).append("() line ")
                            .append(model.postConstructLine).append('\n');
                }
            }
        }
        if (all || show.equals("components")) {
            out.append("\n#### Components\n");
            for (ProjectModel.Component component : index.components) {
                if (!matches(filter, component.resourceType, component.title)) {
                    continue;
                }
                out.append("- ").append(component.resourceType).append(component.title == null ? "" : " \"" + component.title + "\"");
                if (component.superType != null) {
                    out.append(" -> ").append(component.superType);
                }
                out.append('\n');
                for (ProjectModel.HtlScript script : index.scriptsFor(component.resourceType)) {
                    out.append("    - ").append(index.relative(script.file));
                    if (!script.useObjects.isEmpty()) {
                        out.append(" uses ").append(script.useObjects);
                    }
                    out.append('\n');
                }
                for (ProjectModel.SlingModel model : index.modelsForResourceType(component.resourceType)) {
                    out.append("    - model ").append(model.className).append('\n');
                }
            }
        }
        if (all || show.equals("servlets")) {
            out.append("\n#### Servlets\n");
            for (ProjectModel.Servlet servlet : index.servlets) {
                if (!matches(filter, servlet.className, servlet.resourceTypes + " " + servlet.paths)) {
                    continue;
                }
                out.append("- ").append(servlet.className).append(" types ").append(servlet.resourceTypes)
                        .append(" paths ").append(servlet.paths).append(" selectors ").append(servlet.selectors)
                        .append(" extensions ").append(servlet.extensions).append(" methods ").append(servlet.methods)
                        .append('\n');
            }
        }
        if (all || show.equals("configs")) {
            out.append("\n#### OSGi config files\n");
            for (ProjectModel.OsgiConfigFile config : index.osgiConfigs) {
                if (!matches(filter, config.name, config.runModes)) {
                    continue;
                }
                out.append("- ").append(config.name).append(" [run modes: ").append(config.runModes).append("] ")
                        .append(index.relative(config.file)).append('\n');
            }
        }
        if (all || show.equals("clientlibs")) {
            out.append("\n#### Client libraries\n");
            for (ProjectModel.ClientLib lib : index.clientLibs) {
                if (!matches(filter, lib.jcrPath, lib.categories.toString())) {
                    continue;
                }
                out.append("- ").append(lib.jcrPath).append(" categories ").append(lib.categories)
                        .append(lib.allowProxy ? " allowProxy" : " (no allowProxy: not served via /etc.clientlibs)")
                        .append('\n');
            }
        }
        if (!index.warnings.isEmpty()) {
            out.append("\nWarnings:\n");
            for (String warning : index.warnings) {
                out.append("- ").append(warning).append('\n');
            }
        }
        return ToolResult.of(ToolOutput.inlineOrArtifact(s, out.toString(), ToolOutput.INLINE_LIMIT, "project-index.md"));
    }

    private static boolean matches(String filter, String... values) {
        if (filter == null) {
            return true;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static String describeUse(Map<String, String> uses) {
        return uses.toString();
    }

    static String join(List<String> values) {
        return String.join(", ", values);
    }
}
