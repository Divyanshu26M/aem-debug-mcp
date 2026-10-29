package dev.aemdebug.tools.impl;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AuthManager;
import dev.aemdebug.aem.AuthPendingException;
import dev.aemdebug.aem.PageTargets;
import dev.aemdebug.aem.PageUrls;
import dev.aemdebug.browser.SiteSession;
import dev.aemdebug.config.HostPolicy;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.AbstractTool;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolException;
import dev.aemdebug.tools.ToolResult;
import dev.aemdebug.util.Args;
import dev.aemdebug.util.Schema;
import dev.aemdebug.util.Text;

import java.net.URI;
import java.util.Locale;

/** aem_auth: AEM logins (author, publish) and the site's own visitor session. */
public final class AemAuthTool extends AbstractTool {

    private final Services s;

    public AemAuthTool(Services services) {
        super("aem_auth", "AEM authentication",
                "Manages AEM logins and the site visitor session. action=login opens a visible browser window on the "
                        + "AEM login page of author or publish (local admin or SSO) and captures the session cookie; "
                        + "it is remembered for later chats in an owner-only file unless AEM_DEBUG_REMEMBER_LOGINS="
                        + "false; action=status shows who is logged in; action=logout forgets that login (memory and "
                        + "file). action=site-login opens a visible window on a local site page (url, e.g. a dev-server "
                        + "or publish page) for the site's own visitor/customer login - external identity providers "
                        + "work in that window; cookies of local hosts and storage of local origins are captured in "
                        + "memory only (never on disk) and used by page_render/debug_page for publish and dev-server "
                        + "pages; action=site-logout forgets them. Passwords are never accepted as arguments: the user "
                        + "signs in in the browser window (or AEM_USER/AEM_PASSWORD are set in the server environment). "
                        + "Other tools prompt for the AEM login automatically when AEM requires it.",
                Schema.object()
                        .requiredEnum("action", "login, status, logout, site-login or site-logout",
                                "login", "status", "logout", "site-login", "site-logout")
                        .enumStr("instance", "author (default) or publish", "author", "publish")
                        .str("url", "action=site-login: local page where the user starts signing in, e.g. "
                                + "http://localhost:3000/ or a publish page path (default: the publish home)")
                        .integer("waitSeconds", "How long to wait for the user: login - until signed in (default 60); "
                                + "site-login - until the user closes the window (default 0 = return at once)", 0, 300)
                        .bool("interactive", "Use the browser login window even when AEM_USER/AEM_PASSWORD are set"));
        this.s = services;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ToolResult call(ToolContext context, Args args) {
        String action = args.choice("action", null, "login", "status", "logout", "site-login", "site-logout");
        if (action == null) {
            throw new IllegalArgumentException("action is required (login, status, logout, site-login or site-logout)");
        }
        switch (action) {
            case "site-login":
                return siteLogin(context, args);
            case "site-logout":
                return ToolResult.of(s.browser.forgetSiteSession()
                        ? "Closed the site login window (if open) and forgot the site session. It was kept in memory "
                        + "only; nothing changed on the site."
                        : "There was no site session.");
            default:
                break;
        }
        AemInstance instance = s.instances.resolve(args.str("instance"));
        switch (action) {
            case "status": {
                StringBuilder sb = new StringBuilder(describe(s.auth.status(instance, true), instance));
                sb.append('\n').append(rememberedNote());
                sb.append('\n').append(siteSessionLine(s.browser.siteSession()));
                return ToolResult.of(sb.toString());
            }
            case "logout":
                s.auth.logout(instance);
                return ToolResult.of("Forgot the AEM login for " + instance
                        + (s.auth.store().enabled() ? " in memory and its remembered copy on disk." : " (memory only).")
                        + " Nothing changed in AEM.");
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

    private ToolResult siteLogin(ToolContext context, Args args) {
        String url = siteUrl(args);
        long since = System.currentTimeMillis();
        boolean opened = s.browser.openSiteLogin(url);
        int wait = args.integer("waitSeconds", 0, 0, 300);
        SiteSession session = wait > 0 ? waitForClose(context, wait, since) : null;
        StringBuilder sb = new StringBuilder();
        if (closedSince(session, since)) {
            sb.append("The user closed the site login window. ").append(siteSessionLine(session)).append('\n');
            sb.append(session.isEmpty() ? "Nothing was captured: ask the user to sign in again (aem_auth "
                    + "action=site-login) and to close the window only when the site shows them signed in.\n"
                    : "Renders of publish and dev-server pages now use it (session=auto); session=none renders "
                    + "anonymously.\n");
            return ToolResult.of(sb.toString());
        }
        sb.append(opened ? "Opened a site login window at " + url + "."
                : "A site login window is already open; it was brought to the front (open windows are never "
                + "replaced).").append('\n');
        sb.append("Ask the user to sign in there (any login flow, including an external identity provider) and to "
                + "close the window when the site shows them signed in. While the window is open, renders already use "
                + "its current state. The session is kept in memory only and never written to disk.\n");
        sb.append(siteSessionLine(session != null ? session : s.browser.siteSessionSnapshot())).append('\n');
        return ToolResult.of(sb.toString());
    }

    /** The url argument as an absolute local URL; plain paths are resolved against the instance (default publish). */
    private String siteUrl(Args args) {
        String value = JvmTools.blankToNull(args.str("url"));
        String instanceArg = args.str("instance");
        AemInstance instance = s.instances.resolve(instanceArg == null || instanceArg.isBlank() ? "publish" : instanceArg);
        if (value == null) {
            return instance.url("/");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException e) {
                throw new ToolException("Not a valid URL: " + value);
            }
            PageTargets.rejectCredentials(uri);
            HostPolicy.requireLocal(uri);
            return uri.toString();
        }
        return instance.url(PageUrls.toRequestPath(value));
    }

    /** Waits until the user closes the window opened (or brought to the front) at {@code since}. */
    private SiteSession waitForClose(ToolContext context, int waitSeconds, long since) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long nextProgress = 0;
        SiteSession session = s.browser.siteSessionSnapshot();
        while (System.currentTimeMillis() < deadline) {
            session = s.browser.siteSessionSnapshot();
            if (closedSince(session, since)) {
                return session;
            }
            long now = System.currentTimeMillis();
            if (now >= nextProgress) {
                context.progress("Waiting for the user to sign in to the site and close the window ("
                        + Math.max(0, (deadline - now) / 1000) + " s left)");
                nextProgress = now + 5000;
            }
            JvmTools.pause(500);
        }
        return s.browser.siteSession();
    }

    /** A capture of a window that was closed after {@code since} (not an older session). */
    private static boolean closedSince(SiteSession session, long since) {
        return session != null && !session.windowOpen() && session.capturedAt() >= since;
    }

    private String rememberedNote() {
        return s.auth.store().enabled()
                ? "AEM logins are remembered for later chats in " + s.auth.store().file()
                + " (owner-only; aem_auth action=logout removes one)."
                : "AEM logins are kept in memory only (AEM_DEBUG_REMEMBER_LOGINS=false).";
    }

    static String siteSessionLine(SiteSession session) {
        if (session == null) {
            return "Site session: none (aem_auth action=site-login url=<page> lets the user sign in to the site).";
        }
        return "Site session (memory only): " + session.summary() + ".";
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
