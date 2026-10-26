package dev.aemdebug.tools.impl;

import dev.aemdebug.Version;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemResponse;
import dev.aemdebug.aem.AemUnavailableException;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.AuthPendingException;
import dev.aemdebug.aem.BundleInfo;
import dev.aemdebug.aem.RequestOptions;
import dev.aemdebug.aem.SlingSettings;
import dev.aemdebug.jdi.JdwpPortProbe;
import dev.aemdebug.logs.LogLocator;
import dev.aemdebug.project.ProjectIndex;
import dev.aemdebug.project.ProjectIndexService;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** aem_status: setup check for local debugging. */
public final class AemStatusTool extends AbstractTool {

    private final Services s;

    public AemStatusTool(Services services) {
        super("aem_status", "AEM debug status",
                "Checks the local AEM debugging setup: reachability of author/publish, authentication state, AEM "
                        + "version, run modes, sling.home and error.log location, bundle health, the local AEM project "
                        + "in use (and, when logged in, whether its bundles are installed), and whether the JDWP debug "
                        + "port is free or held by another debugger (e.g. IntelliJ). Call this first, after project_index "
                        + "workspace=<your working folder>. Read-only; it only opens the AEM login window when login=true.",
                Schema.object()
                        .enumStr("instance", "Instance to check (default all configured)", "author", "publish", "all")
                        .bool("login", "Open the interactive AEM login window if not authenticated (default false)")
                        .bool("checkJdwp", "Probe the JDWP port (default true)"));
        this.s = services;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String which = args.choice("instance", "all", "author", "publish", "all");
        boolean login = args.bool("login", false);
        boolean checkJdwp = args.bool("checkJdwp", true);

        StringBuilder out = new StringBuilder("## aem-debug-mcp status\n");
        out.append("- server ").append(Version.get()).append(" on Java ").append(Runtime.version()).append('\n');
        out.append("- artifacts: ").append(s.artifacts.baseDir()).append('\n');
        out.append("- server log: ").append(System.getProperty("aemdebug.logFile", "(stderr)")).append('\n');
        if (!s.config.problems().isEmpty()) {
            out.append("- configuration problems:\n");
            for (String problem : s.config.problems()) {
                out.append("  - ").append(problem).append('\n');
            }
        }
        List<AemInstance> selected = new ArrayList<>();
        if (!which.equals("publish") && s.instances.authorOrNull() != null) {
            selected.add(s.instances.authorOrNull());
        }
        if (!which.equals("author") && s.instances.publishOrNull() != null) {
            selected.add(s.instances.publishOrNull());
        }
        List<ProjectIndexService.Root> roots = s.projects.roots(context);
        ProjectIndex project = roots.isEmpty() ? null : s.projects.get(context);
        for (AemInstance instance : selected) {
            out.append(instanceStatus(context, instance, login, roots, project));
        }
        out.append("\n### Local AEM project\n").append(ProjectStatus.roots(s, roots));
        if (project != null) {
            out.append("- index: ").append(project.summary()).append('\n');
        }
        if (checkJdwp) {
            out.append(StatusSections.jdwp(s));
        }
        return ToolResult.of(out.toString());
    }

    private String instanceStatus(ToolContext context, AemInstance instance, boolean login,
                                  List<ProjectIndexService.Root> roots, ProjectIndex project) {
        StringBuilder sb = new StringBuilder("\n### ").append(instance.name()).append(' ')
                .append(instance.baseUrl()).append('\n');
        AemResponse loginPage;
        try {
            loginPage = s.http.get(instance, AemResponse.LOGIN_PAGE,
                    RequestOptions.defaults().anonymous().withTimeout(Duration.ofSeconds(8)));
        } catch (AemUnavailableException e) {
            sb.append("- reachable: NO - ").append(e.getMessage()).append('\n');
            return sb.toString();
        }
        sb.append("- reachable: yes (login page HTTP ").append(loginPage.status()).append(", ")
                .append(loginPage.durationMs()).append(" ms)\n");

        AuthManager.Status status = s.auth.status(instance, true);
        if (!status.authenticated()) {
            try {
                if (login) {
                    s.auth.ensureSession(context, instance);
                } else if (s.config.hasCredentials()) {
                    s.auth.loginWithCredentials(instance);
                }
            } catch (AuthPendingException e) {
                sb.append("- auth: ").append(e.getMessage()).append('\n');
                return sb.toString();
            } catch (RuntimeException e) {
                sb.append("- auth: login failed - ").append(e.getMessage()).append('\n');
            }
            status = s.auth.status(instance, false);
        }
        if (!status.authenticated()) {
            sb.append("- auth: not logged in");
            if (status.loginWindowOpen) {
                sb.append(" (a login window is open, waiting for the user)");
            }
            sb.append(". Tools that need AEM data open a login window automatically; or call aem_auth action=login.\n");
            if (status.lastError != null) {
                sb.append("  - last auth problem: ").append(status.lastError).append('\n');
            }
            return sb.toString();
        }
        sb.append("- auth: logged in as ").append(status.user).append(" (")
                .append(StatusSections.methodLabel(status.method)).append(")\n");

        try {
            String version = s.felix.productVersion(context, instance);
            sb.append("- AEM: ").append(version == null ? "version not reported" : version).append('\n');
        } catch (RuntimeException e) {
            sb.append("- AEM: version unavailable (").append(Text.oneLine(e.getMessage(), 160)).append(")\n");
        }
        try {
            SlingSettings settings = s.felix.slingSettings(context, instance);
            sb.append("- run modes: ").append(settings.runModes.isEmpty() ? "?" : String.join(", ", settings.runModes))
                    .append('\n');
            sb.append("- sling.home: ").append(settings.slingHome == null ? "?" : settings.slingHome).append('\n');
        } catch (RuntimeException e) {
            sb.append("- sling settings unavailable (").append(Text.oneLine(e.getMessage(), 160)).append(")\n");
        }
        try {
            LogLocator.Location location = s.logLocator.locate(context, instance);
            if (location.readable()) {
                sb.append("- error.log: ").append(location.errorLog).append(" (")
                        .append(Text.formatBytes(Files.size(location.errorLog))).append(", ").append(location.note)
                        .append(")\n");
            } else {
                sb.append("- error.log: not readable - ").append(location.note)
                        .append(" (aem_logs falls back to the Sling log tailer)\n");
            }
        } catch (Exception e) {
            sb.append("- error.log: unknown (").append(Text.oneLine(e.getMessage(), 160)).append(")\n");
        }
        try {
            List<BundleInfo> bundles = s.felix.bundles(context, instance);
            List<BundleInfo> unhealthy = new ArrayList<>();
            for (BundleInfo bundle : bundles) {
                if (!bundle.healthy()) {
                    unhealthy.add(bundle);
                }
            }
            sb.append("- bundles: ").append(bundles.size()).append(" total, ").append(unhealthy.size())
                    .append(" not active");
            if (!unhealthy.isEmpty()) {
                sb.append(" (see aem_osgi_status):");
                for (int i = 0; i < Math.min(8, unhealthy.size()); i++) {
                    sb.append("\n  - ").append(unhealthy.get(i));
                }
            }
            sb.append('\n');
            sb.append(ProjectStatus.deployment(project, roots, bundles, "- local project "));
        } catch (RuntimeException e) {
            sb.append("- bundles: unavailable (").append(Text.oneLine(e.getMessage(), 160)).append(")\n");
        }
        return sb.toString();
    }
}
