package dev.aemdebug.tools.impl;

import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectModel;
import dev.aemdebug.server.Services;

import java.util.List;

/** Bridges tools to the local project index (only uses an index that already exists; never scans). */
final class ProjectHooks {

    private ProjectHooks() {
    }

    /** Markdown lines listing local ui.config files that define the PID, or null. */
    static String osgiConfigFiles(Services s, String pid) {
        ProjectIndex index = s.projects.cached();
        if (index == null) {
            return null;
        }
        List<ProjectModel.OsgiConfigFile> files = index.osgiConfigsFor(pid);
        if (files.isEmpty()) {
            return "Local project: no config file for " + pid + " in " + index.root + "\n";
        }
        StringBuilder sb = new StringBuilder("Local project files for ").append(pid).append(":\n");
        for (ProjectModel.OsgiConfigFile file : files) {
            sb.append("- ").append(index.relative(file.file)).append(" (run modes: ").append(file.runModes)
                    .append(")\n");
        }
        return sb.toString();
    }
}
