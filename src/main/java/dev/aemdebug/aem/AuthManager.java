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
 * Authentication against local AEM. Order: existing session (in memory, or remembered from an earlier chat in an
 * owner-only file), AEM_USER/AEM_PASSWORD (programmatic j_security_check), then an interactive visible login window
 * (local admin or SSO) whose {@code login-token} cookie is captured. Passwords are never stored; tokens are never
 * logged or returned. At most one login window is open per instance, however many tools wait for it.
 */
public final class AuthManager {

    private static final Logger LOG = LoggerFactory.getLogger(AuthManager.class);

    public static final String LOGIN_TOKEN = "login-token";
    public static final String LOGIN_PAGE = AemResponse.LOGIN_PAGE;
    private static final String CURRENT_USER = "/libs/granite/security/currentuser.json";
    private static final String SESSION_INFO = "/system/sling/info.sessionInfo.json";

    /** How the current session was obtained. */
    public enum Method {
        CREDENTIALS, INTERACTIVE, EXISTING, REMEMBERED
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
    private final LoginStore store;
    private volatile LoginLauncher launcher;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** Per origin: the last remembered token AEM rejected, so it is not loaded again if it could not be removed. */
    private final Map<String, String> rejectedTokens = new ConcurrentHashMap<>();
    private final java.util.Set<String> unverifiedRemembered = ConcurrentHashMap.newKeySet();
    private final List<java.util.function.Consumer<AemInstance>> loginListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public AuthManager(Config config, AemHttpClient http) {
        this(config, http, LoginStore.disabled());
    }

    public AuthManager(Config config, AemHttpClient http, LoginStore store) {
        this.config = config;
        this.http = http;
        this.cookies = http.cookies();
        this.store = store;
    }

    private Object lock(String origin) {
        return locks.computeIfAbsent(origin, k -> new Object());
    }

    public LoginStore store() {
        return store;
    }

    /**
     * Without a token in memory, loads the login remembered by this or another chat (a running chat also picks up a
     * login made meanwhile in another chat); it is verified on first use.
     */
    private void restoreRemembered(AemInstance instance) {
        String origin = instance.origin();
        if (!store.enabled() || cookies.has(origin, LOGIN_TOKEN)) {
            return;
        }
        String token = store.token(origin);
        if (token == null || token.equals(rejectedTokens.get(origin))) {
            return;
        }
        synchronized (lock(origin)) {
            if (!cookies.has(origin, LOGIN_TOKEN)) {
                // A session whose token is gone (AEM cleared it) is stale: the restored token gets verified first.
                sessions.remove(origin);
                cookies.put(origin, LOGIN_TOKEN, token);
                unverifiedRemembered.add(origin);
            }
        }
    }

    private void remember(AemInstance instance, String user) {
        store.save(instance.origin(), user, cookies.get(instance.origin(), LOGIN_TOKEN));
    }

    /** The login token this server sends to the instance (memory only), or null. */
    String token(AemInstance instance) {
        return cookies.get(instance.origin(), LOGIN_TOKEN);
    }

    /** Called under the origin lock when AEM rejected {@code token}: forgets it, in memory and remembered. */
    private void forgetRejected(String origin, String token) {
        sessions.remove(origin);
        unverifiedRemembered.remove(origin);
        cookies.remove(origin, LOGIN_TOKEN);
        if (token != null) {
            rejectedTokens.put(origin, token);
            store.removeIfToken(origin, token);
        }
    }

    /**
     * AEM asked for a login although {@code token} was sent: it expired or was revoked. Forgets it (in memory and
     * remembered) unless a newer login has replaced it meanwhile. AEM usually clears the cookie in the same response,
     * so the token is passed in rather than read from the cookie jar.
     */
    public void sessionRejected(AemInstance instance, String token) {
        String origin = instance.origin();
        synchronized (lock(origin)) {
            String current = cookies.get(origin, LOGIN_TOKEN);
            if (current != null && !current.equals(token)) {
                return;
            }
            LOG.info("AEM {} rejected the session token; a new login is needed", instance.name());
            forgetRejected(origin, token);
        }
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
        return checkUser(instance).user;
    }

    /** Who AEM says the stored session belongs to. */
    private static final class UserCheck {
        static final UserCheck ANONYMOUS = new UserCheck(null, true);
        static final UserCheck UNKNOWN = new UserCheck(null, false);

        /** The authenticated user, or null. */
        final String user;
        /** AEM answered and treated the request as anonymous: the token is not (or no longer) accepted. */
        final boolean rejected;

        UserCheck(String user, boolean rejected) {
            this.user = user;
            this.rejected = rejected;
        }
    }

    private UserCheck checkUser(AemInstance instance) {
        RequestOptions options = RequestOptions.defaults().withoutRedirects()
                .withTimeout(java.time.Duration.ofSeconds(10));
        try {
            AemResponse response = http.get(instance, CURRENT_USER, options);
            if (response.ok() && response.contentType().contains("json")) {
                String user = Json.asString(response.jsonObject().get("authorizableId"));
                return isAnonymous(user) ? UserCheck.ANONYMOUS : new UserCheck(user, false);
            }
            AemResponse info = http.get(instance, SESSION_INFO, options);
            if (info.ok() && info.contentType().contains("json")) {
                String user = Json.asString(info.jsonObject().get("userID"));
                return isAnonymous(user) ? UserCheck.ANONYMOUS : new UserCheck(user, false);
            }
            if (response.isLoginRequired() || response.status() == 403) {
                return UserCheck.ANONYMOUS;
            }
        } catch (IllegalArgumentException e) {
            LOG.debug("Unexpected current user response: {}", e.getMessage());
        }
        return UserCheck.UNKNOWN;
    }

    private static boolean isAnonymous(String user) {
        return user == null || user.isBlank() || "anonymous".equalsIgnoreCase(user);
    }

    public Status status(AemInstance instance, boolean verify) {
        String origin = instance.origin();
        restoreRemembered(instance);
        Session session = sessions.get(origin);
        boolean rememberedVerified = false;
        if (verify && cookies.has(origin, LOGIN_TOKEN)) {
            String checkedToken = cookies.get(origin, LOGIN_TOKEN);
            UserCheck check = checkUser(instance);
            String user = check.user;
            synchronized (lock(origin)) {
                String current = cookies.get(origin, LOGIN_TOKEN);
                if (current != null && !current.equals(checkedToken)) {
                    // A login completed while the old token was checked: the answer is about a token gone already.
                    session = sessions.get(origin);
                } else if (current == null || check.rejected) {
                    // Rejected, or AEM cleared the cookie (Set-Cookie with Max-Age=0) while answering.
                    forgetRejected(origin, checkedToken);
                    session = null;
                } else if (user == null) {
                    // AEM did not answer clearly (starting up, 5xx): keep the token, report no verified session.
                    session = sessions.get(origin);
                } else if (session == null || !user.equals(session.user)) {
                    rememberedVerified = unverifiedRemembered.remove(origin);
                    Method method = rememberedVerified ? Method.REMEMBERED
                            : session == null ? Method.EXISTING : session.method;
                    session = new Session(user, method, System.currentTimeMillis());
                    sessions.put(origin, session);
                }
            }
        }
        if (rememberedVerified) {
            LOG.info("Using the remembered login for {} as {}", instance.name(), session.user);
            notifyLogin(instance);
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
        restoreRemembered(instance);
        consumeCompletedLogin(instance);
        if (!forceInteractive && cookies.has(origin, LOGIN_TOKEN)) {
            Session existing = sessions.get(origin);
            if (existing != null) {
                return status(instance, false);
            }
            Status verified = status(instance, true);
            if (verified.authenticated()) {
                return verified;
            }
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
                    + "then retry this tool."
                    + (store.enabled() ? " The login is remembered (owner-only file) for later chats."
                    : " The session lives in memory only."));
        }
        throw new AuthPendingException("Login to AEM " + instance.name() + " did not complete" + reason
                + ". Run aem_auth with action=login to open the login window again, or configure AEM_USER/AEM_PASSWORD"
                + " in the MCP server environment.");
    }

    /** Starts the interactive login window, or returns the one already open for this instance, without waiting. */
    public PendingLogin startInteractiveLogin(AemInstance instance) {
        String origin = instance.origin();
        PendingLogin login;
        synchronized (lock(origin)) {
            PendingLogin existing = pending.get(origin);
            if (existing != null && existing.isOpen()) {
                return existing;
            }
            login = new PendingLogin();
            pending.put(origin, login);
        }
        LoginLauncher current = launcher;
        if (current == null) {
            login.fail("no browser available for interactive login");
            return login;
        }
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
        String user;
        synchronized (lock(origin)) {
            PendingLogin login = pending.get(origin);
            if (login == null || !login.isCompleted()) {
                return hasSession(instance);
            }
            String token = login.takeToken();
            if (token != null) {
                cookies.put(origin, LOGIN_TOKEN, token);
                unverifiedRemembered.remove(origin);
            }
            pending.remove(origin);
            user = currentUser(instance);
            if (user == null) {
                cookies.remove(origin, LOGIN_TOKEN);
                lastErrors.put(origin, "login token captured but AEM did not accept it");
                return false;
            }
            sessions.put(origin, new Session(user, Method.INTERACTIVE, System.currentTimeMillis()));
            lastErrors.remove(origin);
            remember(instance, user);
        }
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
        unverifiedRemembered.remove(origin);
        remember(instance, user);
        LOG.info("Logged in to {} as {} using AEM_USER", instance.name(), user);
        notifyLogin(instance);
        return true;
    }

    /** Forgets the session, in memory and remembered on disk (does not touch AEM). */
    public void logout(AemInstance instance) {
        String origin = instance.origin();
        sessions.remove(origin);
        cookies.clear(origin);
        unverifiedRemembered.remove(origin);
        store.remove(origin);
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
