package dev.aemdebug.config;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration from environment variables (set in mcp.json). All settings are optional.
 *
 * <pre>
 * AEM_AUTHOR_URL                 default http://localhost:4502
 * AEM_PUBLISH_URL                default http://localhost:4503
 * AEM_JDWP_HOST / AEM_JDWP_PORT  default 127.0.0.1 / 8767
 * AEM_PROJECT_DIR                a local AEM project folder (more via project_index, see ProjectIndexService)
 * AEM_LOG_DIR                    crx-quickstart/logs (else detected from sling.home)
 * AEM_USER / AEM_PASSWORD        non-interactive login fallback (never logged or returned)
 * AEM_DEBUG_BROWSER_CHANNEL      msedge | chrome | chromium (default: auto)
 * AEM_DEBUG_ARTIFACTS_DIR        default PLUGIN_DATA or %TEMP%/aem-debug-mcp
 * AEM_DEBUG_PROJECT_PACKAGES     comma separated Java packages (else from the project index)
 * AEM_DEBUG_IDLE_DETACH_MINUTES  idle time before JDWP detach and toggle revert (default 15)
 * AEM_DEBUG_LOGIN_WAIT_SECONDS   how long a tool waits for the interactive login (default 60)
 * </pre>
 */
public final class Config {

    public static final String DEFAULT_AUTHOR_URL = "http://localhost:4502";
    public static final String DEFAULT_PUBLISH_URL = "http://localhost:4503";
    public static final String DEFAULT_JDWP_HOST = "127.0.0.1";
    public static final int DEFAULT_JDWP_PORT = 8767;

    private final URI authorUrl;
    private final URI publishUrl;
    private final String jdwpHost;
    private final int jdwpPort;
    private final Path projectDir;
    private final Path logDir;
    private final String user;
    private final String password;
    private final String browserChannel;
    private final Path artifactsDir;
    private final List<String> projectPackages;
    private final int idleDetachMinutes;
    private final int loginWaitSeconds;
    private final List<String> problems;

    private Config(Builder b) {
        this.authorUrl = b.authorUrl;
        this.publishUrl = b.publishUrl;
        this.jdwpHost = b.jdwpHost;
        this.jdwpPort = b.jdwpPort;
        this.projectDir = b.projectDir;
        this.logDir = b.logDir;
        this.user = b.user;
        this.password = b.password;
        this.browserChannel = b.browserChannel;
        this.artifactsDir = b.artifactsDir;
        this.projectPackages = Collections.unmodifiableList(new ArrayList<>(b.projectPackages));
        this.idleDetachMinutes = b.idleDetachMinutes;
        this.loginWaitSeconds = b.loginWaitSeconds;
        this.problems = Collections.unmodifiableList(new ArrayList<>(b.problems));
    }

    public static Config fromEnvironment(Map<String, String> env) {
        Builder b = new Builder();
        b.authorUrl = parseUrl(env, "AEM_AUTHOR_URL", DEFAULT_AUTHOR_URL, b.problems);
        b.publishUrl = parseUrl(env, "AEM_PUBLISH_URL", DEFAULT_PUBLISH_URL, b.problems);

        String jdwpHost = value(env, "AEM_JDWP_HOST", DEFAULT_JDWP_HOST);
        if (HostPolicy.isLocalHost(jdwpHost)) {
            b.jdwpHost = jdwpHost;
        } else {
            b.problems.add("AEM_JDWP_HOST '" + jdwpHost + "' is not local; only localhost/127.0.0.1/::1 are allowed."
                    + " JVM debugging is disabled.");
            b.jdwpHost = null;
        }
        b.jdwpPort = intValue(env, "AEM_JDWP_PORT", DEFAULT_JDWP_PORT, 1, 65535, b.problems);

        b.projectDir = pathValue(env, "AEM_PROJECT_DIR");
        b.logDir = pathValue(env, "AEM_LOG_DIR");
        b.user = value(env, "AEM_USER", null);
        b.password = env.get("AEM_PASSWORD");
        if (b.password != null && b.password.isEmpty()) {
            b.password = null;
        }

        String channel = value(env, "AEM_DEBUG_BROWSER_CHANNEL", null);
        if (channel != null) {
            String normalized = channel.toLowerCase(Locale.ROOT);
            if (normalized.equals("msedge") || normalized.equals("chrome") || normalized.equals("chromium")) {
                b.browserChannel = normalized;
            } else {
                b.problems.add("AEM_DEBUG_BROWSER_CHANNEL must be msedge, chrome or chromium (was '" + channel
                        + "'); using auto detection.");
            }
        }

        Path artifacts = pathValue(env, "AEM_DEBUG_ARTIFACTS_DIR");
        if (artifacts == null) {
            Path pluginData = pathValue(env, "PLUGIN_DATA");
            artifacts = pluginData != null
                    ? pluginData
                    : Paths.get(System.getProperty("java.io.tmpdir"), "aem-debug-mcp");
        }
        b.artifactsDir = artifacts.toAbsolutePath().normalize();

        String packages = value(env, "AEM_DEBUG_PROJECT_PACKAGES", null);
        if (packages != null) {
            for (String part : packages.split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    b.projectPackages.add(part.trim());
                }
            }
        }
        b.idleDetachMinutes = intValue(env, "AEM_DEBUG_IDLE_DETACH_MINUTES", 15, 0, 24 * 60, b.problems);
        b.loginWaitSeconds = intValue(env, "AEM_DEBUG_LOGIN_WAIT_SECONDS", 60, 0, 600, b.problems);
        return new Config(b);
    }

    private static URI parseUrl(Map<String, String> env, String name, String defaultValue, List<String> problems) {
        String raw = value(env, name, defaultValue);
        try {
            return HostPolicy.requireLocalBaseUrl(raw, name);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    private static String value(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank() || value.contains("${")) {
            return defaultValue;
        }
        return value.trim();
    }

    private static Path pathValue(Map<String, String> env, String name) {
        String value = value(env, name, null);
        if (value == null) {
            return null;
        }
        try {
            return Paths.get(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int intValue(Map<String, String> env, String name, int defaultValue, int min, int max,
                                List<String> problems) {
        String value = value(env, name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                problems.add(name + " must be between " + min + " and " + max + " (was " + value + ")");
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            problems.add(name + " is not a number: " + value);
            return defaultValue;
        }
    }

    public URI authorUrl() {
        return authorUrl;
    }

    public URI publishUrl() {
        return publishUrl;
    }

    public String jdwpHost() {
        return jdwpHost;
    }

    public int jdwpPort() {
        return jdwpPort;
    }

    public Path projectDir() {
        return projectDir;
    }

    public Path logDir() {
        return logDir;
    }

    public String user() {
        return user;
    }

    /** Only for the programmatic login request body. Never log or return. */
    public String password() {
        return password;
    }

    public boolean hasCredentials() {
        return user != null && password != null;
    }

    public String browserChannel() {
        return browserChannel;
    }

    public Path artifactsDir() {
        return artifactsDir;
    }

    public List<String> projectPackages() {
        return projectPackages;
    }

    public int idleDetachMinutes() {
        return idleDetachMinutes;
    }

    public int loginWaitSeconds() {
        return loginWaitSeconds;
    }

    public List<String> problems() {
        return problems;
    }

    @Override
    public String toString() {
        return "Config{author=" + authorUrl + ", publish=" + publishUrl + ", jdwp=" + jdwpHost + ":" + jdwpPort
                + ", projectDir=" + projectDir + ", logDir=" + logDir + ", user=" + (user == null ? "-" : user)
                + ", password=" + (password == null ? "-" : "****") + ", browserChannel="
                + (browserChannel == null ? "auto" : browserChannel) + ", artifactsDir=" + artifactsDir
                + ", projectPackages=" + projectPackages + ", idleDetachMinutes=" + idleDetachMinutes + "}";
    }

    /** Test helper for building configs without environment variables. */
    public static Builder builder() {
        Builder b = new Builder();
        b.authorUrl = URI.create(DEFAULT_AUTHOR_URL);
        b.publishUrl = URI.create(DEFAULT_PUBLISH_URL);
        b.jdwpHost = DEFAULT_JDWP_HOST;
        b.jdwpPort = DEFAULT_JDWP_PORT;
        b.artifactsDir = Paths.get(System.getProperty("java.io.tmpdir"), "aem-debug-mcp-test");
        b.idleDetachMinutes = 15;
        b.loginWaitSeconds = 0;
        return b;
    }

    public static final class Builder {
        private URI authorUrl;
        private URI publishUrl;
        private String jdwpHost;
        private int jdwpPort;
        private Path projectDir;
        private Path logDir;
        private String user;
        private String password;
        private String browserChannel;
        private Path artifactsDir;
        private final List<String> projectPackages = new ArrayList<>();
        private int idleDetachMinutes;
        private int loginWaitSeconds;
        private final List<String> problems = new ArrayList<>();

        public Builder authorUrl(String url) {
            this.authorUrl = HostPolicy.requireLocalBaseUrl(url, "authorUrl");
            return this;
        }

        public Builder publishUrl(String url) {
            this.publishUrl = HostPolicy.requireLocalBaseUrl(url, "publishUrl");
            return this;
        }

        public Builder jdwp(String host, int port) {
            this.jdwpHost = host;
            this.jdwpPort = port;
            return this;
        }

        public Builder projectDir(Path dir) {
            this.projectDir = dir;
            return this;
        }

        public Builder logDir(Path dir) {
            this.logDir = dir;
            return this;
        }

        public Builder credentials(String user, String password) {
            this.user = user;
            this.password = password;
            return this;
        }

        public Builder browserChannel(String channel) {
            this.browserChannel = channel;
            return this;
        }

        public Builder artifactsDir(Path dir) {
            this.artifactsDir = dir;
            return this;
        }

        public Builder projectPackages(List<String> packages) {
            this.projectPackages.clear();
            this.projectPackages.addAll(packages);
            return this;
        }

        public Builder idleDetachMinutes(int minutes) {
            this.idleDetachMinutes = minutes;
            return this;
        }

        public Builder loginWaitSeconds(int seconds) {
            this.loginWaitSeconds = seconds;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }
}
