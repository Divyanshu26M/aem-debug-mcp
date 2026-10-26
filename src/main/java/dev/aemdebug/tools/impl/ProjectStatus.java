package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.BundleInfo;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectIndexService;
import dev.aemdebug.project.ProjectMatch;
import dev.aemdebug.server.Services;

import java.util.List;

/** The "local AEM project" parts of aem_status and project_index. */
final class ProjectStatus {

    private ProjectStatus() {
    }

    /** The project roots with the reason each one is used, or what to do when none is known. */
    static String roots(Services s, List<ProjectIndexService.Root> roots) {
        if (roots.isEmpty()) {
            return "- none known. " + s.projects.notFoundMessage() + "\n";
        }
        StringBuilder sb = new StringBuilder();
        for (ProjectIndexService.Root root : roots) {
            sb.append("- ").append(root.path()).append(" (").append(root.source()).append(")\n");
        }
        return sb.toString();
    }

    /** Which project roots are deployed on an instance, judged by its installed bundles. */
    static String deployment(ProjectIndex index, List<ProjectIndexService.Root> roots, List<BundleInfo> bundles,
                             String prefix) {
        if (index == null || roots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : ProjectMatch.describe(index, roots, bundles)) {
            sb.append(prefix).append(line).append('\n');
        }
        return sb.toString();
    }
}
