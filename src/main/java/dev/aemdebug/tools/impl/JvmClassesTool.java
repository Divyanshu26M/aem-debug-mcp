package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.jdi.JdiEngine;
import dev.aemdebug.project.ClassFileInfo;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** jvm_classes: which version of a class runs in AEM, compared with the local build. */
public final class JvmClassesTool extends AbstractTool {

    private final Services s;

    public JvmClassesTool(Services services) {
        super("jvm_classes", "Loaded class versions",
                "Answers 'is my code change deployed?'. Lists every version of a class loaded in the AEM JVM (owning "
                        + "bundle id, symbolic name and revision, per-method line ranges) and compares each with the "
                        + "class compiled in the local project (target/classes): equal line tables mean AEM runs your "
                        + "local build; differences mean the bundle in AEM is stale or the local build was not deployed; "
                        + "several loaded versions mean an old bundle revision is still wired. Also warns when the local "
                        + "source is newer than the local build. Needs the JDWP connection (attaches if the port is "
                        + "free).",
                Schema.object()
                        .requiredStr("class", "Fully qualified class name (simple names work but are slower), e.g. "
                                + "com.mysite.core.models.HeroModel")
                        .enumStr("instance", "JVM of author (default) or publish", "author", "publish"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String className = args.requireStr("class").trim();
        AemInstance instance = JvmTools.instance(s, args);
        JdiEngine engine = JvmTools.engine(s, instance);
        String attached = JvmTools.ensureAttached(s, instance, true);
        ProjectIndex index = JvmTools.projectIndex(s, context);
        if (!className.contains(".") && index != null) {
            ProjectModel.JavaClass local = index.classByName(className);
            if (local != null) {
                className = local.fqn;
            }
        }
        List<JdiEngine.ClassVersion> versions = engine.classVersions(className);
        StringBuilder out = new StringBuilder();
        if (attached != null) {
            out.append(attached).append('\n');
        }
        if (versions.isEmpty()) {
            out.append(className).append(" is not loaded in the AEM JVM. Classes load on first use: render a page that "
                    + "uses it (debug_page) and retry. If it never loads, check that its bundle is active and its "
                    + "package exported (aem_osgi_status).\n");
            return ToolResult.of(out.toString());
        }
        out.append(versions.size() == 1 ? "1 loaded version of " : versions.size() + " loaded versions of ")
                .append(className).append(":\n");
        for (JdiEngine.ClassVersion version : versions) {
            out.append("- ").append(version.name).append(" - ").append(version.loader).append('\n');
            out.append("  source ").append(version.sourceName == null ? "?" : version.sourceName);
            if (version.firstLine >= 0) {
                out.append(", lines ").append(version.firstLine).append('-').append(version.lastLine);
            }
            out.append(", ").append(Text.plural(version.methodLines.size(), "method")).append(" with line info\n");
            for (String line : compareWithLocal(index, version).split("\n")) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (versions.size() > 1) {
            out.append("Several versions are loaded: an old bundle revision may still be wired (refresh the bundle or "
                    + "restart AEM; aem_osgi_status shows bundle states).\n");
        }
        return ToolResult.of(out.toString());
    }

    static String compareWithLocal(ProjectIndex index, JdiEngine.ClassVersion version) {
        if (index == null) {
            return "local build: no local project known - cannot compare (project_index workspace=<your folder> or "
                    + "projectDir=<project folder> sets it).";
        }
        Path classFile = index.classFile(version.name);
        if (classFile == null || !Files.isRegularFile(classFile)) {
            return "local build: no compiled " + version.name + " in the project (run mvn compile/install first).";
        }
        ClassFileInfo local;
        try {
            local = ClassFileInfo.read(classFile);
        } catch (IOException e) {
            return "local build: cannot read " + classFile + ": " + e.getMessage();
        }
        List<String> lines = new ArrayList<>();
        List<String> diffs = differences(version, local);
        String built = modified(classFile);
        if (diffs.isEmpty()) {
            lines.add("local build: MATCHES " + index.relative(classFile) + " (built " + built + ")");
        } else {
            lines.add("local build: DIFFERS from " + index.relative(classFile) + " (built " + built + ") - AEM runs a "
                    + "different build. Redeploy the bundle (e.g. mvn clean install -PautoInstallBundle in the module) "
                    + "and check jvm_classes again.");
            for (int i = 0; i < Math.min(8, diffs.size()); i++) {
                lines.add("  - " + diffs.get(i));
            }
            if (diffs.size() > 8) {
                lines.add("  - ... " + (diffs.size() - 8) + " more");
            }
        }
        String outer = version.name.contains("$") ? version.name.substring(0, version.name.indexOf('$')) : version.name;
        ProjectModel.JavaClass source = index.classByName(outer);
        if (source != null && source.file != null) {
            try {
                long sourceTime = Files.getLastModifiedTime(source.file).toMillis();
                long classTime = Files.getLastModifiedTime(classFile).toMillis();
                if (sourceTime > classTime + 1000) {
                    lines.add("local source " + index.relative(source.file) + " changed after the last build ("
                            + Text.formatDateTime(sourceTime) + "): rebuild before comparing.");
                }
            } catch (IOException e) {
                lines.add("(source timestamps unavailable: " + e.getMessage() + ")");
            }
        }
        return String.join("\n", lines);
    }

    /** Per-method line range differences between the class loaded in AEM and a local class file. */
    static List<String> differences(JdiEngine.ClassVersion deployed, ClassFileInfo local) {
        Map<String, int[]> localLines = new LinkedHashMap<>();
        for (ClassFileInfo.MethodLines method : local.methods.values()) {
            if (!method.lines.isEmpty()) {
                localLines.put(method.name + method.descriptor, new int[] {method.first(), method.last()});
            }
        }
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : localLines.entrySet()) {
            int[] remote = deployed.methodLines.get(entry.getKey());
            int[] mine = entry.getValue();
            if (remote == null) {
                diffs.add(entry.getKey() + " only in the local build (lines " + mine[0] + "-" + mine[1] + ")");
            } else if (remote[0] != mine[0] || remote[1] != mine[1]) {
                diffs.add(entry.getKey() + ": AEM lines " + remote[0] + "-" + remote[1] + ", local "
                        + mine[0] + "-" + mine[1]);
            }
        }
        for (Map.Entry<String, int[]> entry : deployed.methodLines.entrySet()) {
            if (!localLines.containsKey(entry.getKey())) {
                int[] remote = entry.getValue();
                diffs.add(entry.getKey() + " only in AEM (lines " + remote[0] + "-" + remote[1] + ")");
            }
        }
        return diffs;
    }

    private static String modified(Path file) {
        try {
            return Text.formatDateTime(Files.getLastModifiedTime(file).toMillis());
        } catch (IOException e) {
            return "?";
        }
    }
}
