package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectIndexService;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** project_index: find and scan the local AEM project(s). */
public final class ProjectIndexTool extends AbstractTool {

    private final Services s;

    public ProjectIndexTool(Services services) {
        super("project_index", "Local AEM project",
                "Finds and indexes the local source of the AEM project(s) deployed on the local AEM, so the other tools "
                        + "can link what AEM runs to your files: Sling Models with their resource types and injected "
                        + "fields, components and HTL scripts with data-sly-use objects, servlets, OSGi config files per "
                        + "run mode, client libraries (categories, allowProxy) and Maven modules/bundles. Call it at the "
                        + "start of every chat with workspace=<your current working folder>: the MCP client does not tell "
                        + "the server which folder the chat runs in. If no project is found, ask the user where the AEM "
                        + "project is and pass projectDir=<folder>; it is remembered for this AEM, so later chats find it. "
                        + "Several projects can be used together (for example two repositories deployed into the same "
                        + "AEM), and a repository that aggregates several AEM projects is indexed as a whole. When logged "
                        + "in, the result shows which projects' bundles are installed in AEM.",
                Schema.object()
                        .str("workspace", "Absolute path of your current working folder; used for this chat when it is, "
                                + "or is inside, an AEM project")
                        .str("projectDir", "AEM project folder the user named (repository root or any folder inside "
                                + "it); used and remembered for this AEM")
                        .bool("replace", "With projectDir: use only this project and forget the other remembered "
                                + "projects for this AEM (default false)")
                        .str("remove", "Project folder to stop using and forget for this AEM")
                        .bool("refresh", "Re-scan even if a recent index exists (default false)")
                        .enumStr("show", "What to list (default summary)", "summary", "models", "components",
                                "servlets", "configs", "clientlibs", "all")
                        .str("filter", "Only list items containing this text"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) throws IOException {
        List<String> notes = new ArrayList<>();
        String workspace = blankToNull(args.str("workspace"));
        if (workspace != null) {
            Path folder = path(workspace, "workspace");
            Path root = s.projects.useWorkspace(folder);
            notes.add(root == null
                    ? "This chat's folder " + folder + " is not inside an AEM project."
                    : "This chat's folder is inside the AEM project " + root + ".");
        }
        String remove = blankToNull(args.str("remove"));
        if (remove != null) {
            Path folder = path(remove, "remove");
            notes.add(s.projects.removeProject(folder)
                    ? "Stopped using " + folder + " and forgot it for " + s.projects.environment() + "."
                    : folder + " was neither used in this chat nor remembered (projects from AEM_PROJECT_DIR or the "
                    + "client workspace cannot be removed here).");
        }
        String dir = blankToNull(args.str("projectDir"));
        if (dir != null) {
            boolean replace = args.bool("replace", false);
            Path root = s.projects.addProject(path(dir, "projectDir"), replace);
            notes.add("Using " + root + (replace ? " as the only remembered project" : "") + "; remembered for "
                    + s.projects.environment() + " in " + s.projects.memoryFile() + ".");
        }
        List<ProjectIndexService.Root> roots = s.projects.roots(context);
        ProjectIndex index = args.bool("refresh", false) ? s.projects.refresh(context) : s.projects.get(context);
        if (index == null) {
            throw new ToolException((notes.isEmpty() ? "" : String.join(" ", notes) + " ")
                    + s.projects.notFoundMessage());
        }
        String show = args.choice("show", "summary", "summary", "models", "components", "servlets", "configs",
                "clientlibs", "all");
        String filter = args.str("filter");
        StringBuilder out = new StringBuilder("### Local AEM project\n");
        for (String note : notes) {
            out.append(note).append('\n');
        }
        out.append(ProjectStatus.roots(s, roots));
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
        out.append(deployment(context, index, roots));
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

    /** Which projects are installed on author, without opening a login window. */
    private String deployment(ToolContext context, ProjectIndex index, List<ProjectIndexService.Root> roots) {
        AemInstance author = s.instances.authorOrNull();
        if (author == null) {
            return "";
        }
        if (!s.auth.hasSession(author)) {
            return "- deployment on " + author.name() + ": not checked (log in with aem_auth action=login to see which "
                    + "of these projects is installed)\n";
        }
        try {
            return ProjectStatus.deployment(index, roots, s.felix.bundles(context, author),
                    "- on " + author.name() + ": ");
        } catch (RuntimeException e) {
            return "- deployment on " + author.name() + ": unavailable (" + Text.oneLine(e.getMessage(), 160) + ")\n";
        }
    }

    private static Path path(String value, String argument) {
        try {
            return Paths.get(value.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ToolException("Invalid " + argument + " path: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
