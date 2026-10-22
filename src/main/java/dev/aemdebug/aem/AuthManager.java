package dev.aemdebug.aem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.util.Json;
import dev.aemdebug.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication against local AEM. Order: existing in-memory session, AEM_USER/AEM_PASSWORD (programmatic
 * j_security_check), then an interactive visible login window (local admin or SSO) whose {@code login-token}
 * cookie is captured in memory only. Secrets are never logged, persisted or returned.
 */
public final class AuthManager {

    private static final Logger LOG = LoggerFactory.getLogger(AuthManager.class);

    public static final String LOGIN_TOKEN = "login-token";
    public static final String LOGIN_PAGE = AemResponse.LOGIN_PAGE;
    private static final String CURRENT_USER = "/libs/granite/security/currentuser.json";
    private static final String SESSION_INFO = "/system/sling/info.sessionInfo.json";

    /** How the current session was obtained. */
    public enum Method {
        CREDENTIALS, INTERACTIVE, EXISTING
    }

    /** Opens the visible login window; implemented by the browser engine. */
    public interface LoginLauncher {
        void openLoginWindow(AemInstance instance, String loginUrl, PendingLogin pending);

        void closeLoginWindow(AemInstance instance);
    }

    /** State of an interactive login started by this server. */
    public static final class PendingLogin {
        private final long startedAt = System.currentTimeMillis();
        private volatile String state = "OPEN";
        private volatile String token;
        private volatile String error;

        public void complete(String loginToken) {
            this.token = loginToken;
            this.state = "COMPLETED";
        }

        public void fail(String message) {
            this.error = message;
            this.state = "FAILED";
        }

        public boolean isOpen() {
            return "OPEN".equals(state);
        }

        public boolean isCompleted() {
            return "COMPLETED".equals(state);
        }

        public String state() {
            return state;
        }

        public String error() {
            return error;
        }

        String takeToken() {
            String value = token;
            token = null;
            return value;
        }

        public long startedAt() {
            return startedAt;
        }
    }

    /** Snapshot of the authentication state of one instance. */
    public static final class Status {
        public final String instance;
        public final String user;
        public final Method method;
        public final long since;
        public final boolean loginWindowOpen;
        public final String lastError;

        Status(String instance, String user, Method method, long since, boolean loginWindowOpen, String lastError) {
            this.instance = instance;
            this.user = user;
            this.method = method;
            this.since = since;
            this.loginWindowOpen = loginWindowOpen;
            this.lastError = lastError;
        }

        public boolean authenticated() {
            return user != null;
        }
    }

    private static final class Session {
        final String user;
        final Method method;
        final long since;

        Session(String user, Method method, long since) {
            this.user = user;
            this.method = method;
            this.since = since;
        }
    }

    private final Config config;
    private final AemHttpClient http;
    private final CookieJar cookies;
    private volatile LoginLauncher launcher;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<AemInstance>> loginListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public AuthManager(Config config, AemHttpClient http) {
        this.config = config;
        this.http = http;
        this.cookies = http.cookies();
    }

    /** Called (on the calling thread) whenever a new session has been established for an instance. */
    public void addLoginListener(java.util.function.Consumer<AemInstance> listener) {
        loginListeners.add(listener);
    }

    private void notifyLogin(AemInstance instance) {
        for (java.util.function.Consumer<AemInstance> listener : loginListeners) {
            try {
                listener.accept(instance);
            } catch (RuntimeException e) {
                LOG.warn("login listener failed: {}", e.toString());
            }
        }
    }

    public void setLoginLauncher(LoginLauncher launcher) {
        this.launcher = launcher;
    }

    public boolean hasSession(AemInstance instance) {
        return sessions.containsKey(instance.origin()) && cookies.has(instance.origin(), LOGIN_TOKEN);
    }

    /** Cookies to hand to the rendering browser for this instance (in memory only). */
    public Map<String, String> sessionCookies(AemInstance instance) {
        return cookies.snapshot(instance.origin());
    }

    /** Current AEM user for the stored session, or null when anonymous / not logged in. */
    public String currentUser(AemInstance instance) {
        RequestOptions options = RequestOptions.defaults().withoutRedirects()
                .withTimeout(java.time.Duration.ofSeconds(10));
        try {
            AemResponse response = http.get(instance, CURRENT_USER, options);
            if (response.ok() && response.contentType().contains("json")) {
                String user = Json.asString(response.jsonObject().get("authorizableId"));
                return isAnonymous(user) ? null : user;
            }
            AemResponse info = http.get(instance, SESSION_INFO, options);
            if (info.ok() && info.contentType().contains("json")) {
                String user = Json.asString(info.jsonObject().get("userID"));
                return isAnonymous(user) ? null : user;
            }
        } catch (IllegalArgumentException e) {
            LOG.debug("Unexpected current user response: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isAnonymous(String user) {
        return user == null || user.isBlank() || "anonymous".equalsIgnoreCase(user);
    }

    public Status status(AemInstance instance, boolean verify) {
        String origin = instance.origin();
        Session session = sessions.get(origin);
        if (verify && cookies.has(origin, LOGIN_TOKEN)) {
            String user = currentUser(instance);
            if (user == null) {
                sessions.remove(origin);
                cookies.remove(origin, LOGIN_TOKEN);
                session = null;
            } else if (session == null || !user.equals(session.user)) {
                session = new Session(user, session == null ? Method.EXISTING : session.method,
                        System.currentTimeMillis());
                sessions.put(origin, session);
            }
        }
        PendingLogin login = pending.get(origin);
        return new Status(instance.name(), session == null ? null : session.user,
                session == null ? null : session.method, session == null ? 0 : session.since,
                login != null && login.isOpen(), lastErrors.get(origin));
    }

    /**
     * Makes sure an authenticated session exists, prompting the user with a login window if needed.
     *
     * @throws AuthPendingException when the user has not completed the login within the wait time
     */
    public void ensureSession(ToolContext context, AemInstance instance) {
        ensureSession(context, instance, config.loginWaitSeconds(), false);
    }

    public Status ensureSession(ToolContext context, AemInstance instance, int waitSeconds, boolean forceInteractive) {
        String origin = instance.origin();
        consumeCompletedLogin(instance);
        if (!forceInteractive && cookies.has(origin, LOGIN_TOKEN)) {
            Session existing = sessions.get(origin);
            if (existing != null) {
                return status(instance, false);
            }
            String user = currentUser(instance);
            if (user != null) {
                sessions.put(origin, new Session(user, Method.EXISTING, System.currentTimeMillis()));
                return status(instance, false);
            }
            cookies.remove(origin, LOGIN_TOKEN);
        }
        if (!forceInteractive && config.hasCredentials()) {
            context.progress("Logging in to AEM " + instance.name() + " with AEM_USER");
            if (loginWithCredentials(instance)) {
                return status(instance, false);
            }
        }
        PendingLogin login = startInteractiveLogin(instance);
        waitForLogin(context, instance, login, waitSeconds);
        if (consumeCompletedLogin(instance)) {
            return status(instance, false);
        }
        String reason = login.error() != null ? " (" + login.error() + ")" : "";
        if (login.isOpen()) {
            throw new AuthPendingException("AEM " + instance.name() + " needs a login. A browser window is open at "
                    + instance.url(LOGIN_PAGE) + " - ask the user to sign in there (local admin or SSO), "
                    + "then retry this tool. Nothing is stored on disk; the session lives in memory only.");
        }
        throw new AuthPendingException("Login to AEM " + instance.name() + " did not complete" + reason
                + ". Run aem_auth with action=login to open the login window again, or configure AEM_USER/AEM_PASSWORD"
                + " in the MCP server environment.");
    }

    /** Starts (or reuses) the interactive login window without waiting. */
    public PendingLogin startInteractiveLogin(AemInstance instance) {
        String origin = instance.origin();
        PendingLogin existing = pending.get(origin);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        LoginLauncher current = launcher;
        PendingLogin login = new PendingLogin();
        if (current == null) {
            login.fail("no browser available for interactive login");
            pending.put(origin, login);
            return login;
        }
        pending.put(origin, login);
        try {
            current.openLoginWindow(instance, instance.url(LOGIN_PAGE), login);
        } catch (RuntimeException e) {
            login.fail("could not open the login window: " + Text.oneLine(e.getMessage(), 300));
            lastErrors.put(origin, login.error());
        }
        return login;
    }

    private void waitForLogin(ToolContext context, AemInstance instance, PendingLogin login, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long nextProgress = 0;
        while (login.isOpen() && System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            if (now >= nextProgress) {
                long remaining = Math.max(0, (deadline - now) / 1000);
                context.progress("Waiting for the user to sign in to AEM " + instance.name()
                        + " in the browser window (" + remaining + " s left)");
                nextProgress = now + 5000;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Moves a token captured by the login window into the cookie jar and verifies it. */
    public boolean consumeCompletedLogin(AemInstance instance) {
        String origin = instance.origin();
        PendingLogin login = pending.get(origin);
        if (login == null || !login.isCompleted()) {
            return hasSession(instance);
        }
        String token = login.takeToken();
        if (token != null) {
            cookies.put(origin, LOGIN_TOKEN, token);
        }
        pending.remove(origin);
        String user = currentUser(instance);
        if (user == null) {
            cookies.remove(origin, LOGIN_TOKEN);
            lastErrors.put(origin, "login token captured but AEM did not accept it");
            return false;
        }
        sessions.put(origin, new Session(user, Method.INTERACTIVE, System.currentTimeMillis()));
        lastErrors.remove(origin);
        LOG.info("Interactive login completed for {} as {}", instance.name(), user);
        notifyLogin(instance);
        return true;
    }

    /** Programmatic login with AEM_USER / AEM_PASSWORD. The password is only placed in the request body. */
    public boolean loginWithCredentials(AemInstance instance) {
        if (!config.hasCredentials()) {
            return false;
        }
        String origin = instance.origin();
        List<String[]> form = new ArrayList<>();
        form.add(new String[] {"j_username", config.user()});
        form.add(new String[] {"j_password", config.password()});
        form.add(new String[] {"j_validate", "true"});
        form.add(new String[] {"_charset_", "UTF-8"});
        RequestOptions options = RequestOptions.defaults().anonymous()
                .withHeader("Referer", instance.url(LOGIN_PAGE));
        try {
            AemResponse response = http.postForm(instance, LOGIN_PAGE + "/j_security_check", form,
                    AemHttpClient.WriteReason.LOGIN, options);
            if (!cookies.has(origin, LOGIN_TOKEN)) {
                lastErrors.put(origin, "AEM_USER login failed (HTTP " + response.status() + ")");
                LOG.warn("Credential login to {} failed with HTTP {}", instance.name(), response.status());
                return false;
            }
        } catch (AemUnavailableException e) {
            lastErrors.put(origin, e.getMessage());
            throw e;
        }
        String user = currentUser(instance);
        if (user == null) {
            cookies.remove(origin, LOGIN_TOKEN);
            lastErrors.put(origin, "AEM_USER login returned a token that AEM did not accept");
            return false;
        }
        sessions.put(origin, new Session(user, Method.CREDENTIALS, System.currentTimeMillis()));
        lastErrors.remove(origin);
        LOG.info("Logged in to {} as {} using AEM_USER", instance.name(), user);
        notifyLogin(instance);
        return true;
    }

    /** Forgets the in-memory session (does not touch AEM). */
    public void logout(AemInstance instance) {
        String origin = instance.origin();
        sessions.remove(origin);
        cookies.clear(origin);
        PendingLogin login = pending.remove(origin);
        if (login != null && login.isOpen()) {
            login.fail("cancelled");
            LoginLauncher current = launcher;
            if (current != null) {
                try {
                    current.closeLoginWindow(instance);
                } catch (RuntimeException e) {
                    LOG.debug("Closing login window failed: {}", e.toString());
                }
            }
        }
    }
}
