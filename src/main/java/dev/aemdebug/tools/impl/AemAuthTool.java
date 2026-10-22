package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.AuthPendingException;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

/** aem_auth: login / status / logout of the in-memory AEM session. */
public final class AemAuthTool extends AbstractTool {

    private final Services s;

    public AemAuthTool(Services services) {
        super("aem_auth", "AEM authentication",
                "Manages the in-memory AEM session. action=login opens a visible browser window on the AEM login page "
                        + "(local admin or SSO) and captures the session cookie in memory only; action=status shows who "
                        + "is logged in; action=logout forgets the session. Passwords are never accepted as arguments: "
                        + "the user signs in in the browser window (or AEM_USER/AEM_PASSWORD are set in the server "
                        + "environment). Other tools prompt for login automatically when AEM requires it.",
                Schema.object()
                        .requiredEnum("action", "login, status or logout", "login", "status", "logout")
                        .enumStr("instance", "author (default) or publish", "author", "publish")
                        .integer("waitSeconds", "How long to wait for the user to finish signing in (default 60)", 0, 300)
                        .bool("interactive", "Use the browser login window even when AEM_USER/AEM_PASSWORD are set"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String action = args.choice("action", null, "login", "status", "logout");
        if (action == null) {
            throw new IllegalArgumentException("action is required (login, status or logout)");
        }
        AemInstance instance = s.instances.resolve(args.str("instance"));
        switch (action) {
            case "status":
                return ToolResult.of(describe(s.auth.status(instance, true), instance));
            case "logout":
                s.auth.logout(instance);
                return ToolResult.of("Forgot the in-memory session for " + instance + ". Nothing was stored on disk.");
            default:
                int wait = args.integer("waitSeconds", Math.max(30, s.config.loginWaitSeconds()), 0, 300);
                boolean interactive = args.bool("interactive", false);
                try {
                    AuthManager.Status status = s.auth.ensureSession(context, instance, wait, interactive);
                    return ToolResult.of(describe(status, instance));
                } catch (AuthPendingException e) {
                    return ToolResult.of("Awaiting login: " + e.getMessage());
                }
        }
    }

    private static String describe(AuthManager.Status status, AemInstance instance) {
        StringBuilder sb = new StringBuilder();
        sb.append("AEM ").append(instance).append(": ");
        if (status.authenticated()) {
            sb.append("logged in as ").append(status.user).append(" (").append(StatusSections.methodLabel(status.method))
                    .append(", since ").append(Text.formatTime(status.since)).append(")");
        } else {
            sb.append("not logged in");
            if (status.loginWindowOpen) {
                sb.append("; a login window is open - waiting for the user to sign in");
            }
        }
        if (status.lastError != null) {
            sb.append("\nLast problem: ").append(status.lastError);
        }
        return sb.toString();
    }
}
