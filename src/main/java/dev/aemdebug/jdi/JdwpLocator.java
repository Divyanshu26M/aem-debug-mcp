package dev.aemdebug.jdi;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.config.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Finds the JDWP endpoint of an AEM instance. Precedence: a port given with jvm_connection port= in this chat; then
 * the port read from the JVM that serves the instance's HTTP port (the operating system names the process, the
 * Attach API reads its JDWP agent's address); then the configured AEM_JDWP_PORT / AEM_PUBLISH_JDWP_PORT, used only
 * when detection cannot run. When detection finds the JVM without a JDWP agent, or with another debugger connected,
 * that is final: the configured port belongs to another process or to nothing.
 */
public final class JdwpLocator {

    static final long OWNER_TIMEOUT_MS = 2000;
    static final long ATTACH_TIMEOUT_MS = 2500;
    private static final long DETACH_GRACE_MS = 3000;

    /** Where the port of an endpoint comes from. */
    public enum Source {
        /** This server is attached to it. */
        ATTACHED,
        /** jvm_connection port= in this chat. */
        OVERRIDE,
        /** Read from the JVM that serves the instance. */
        DETECTED,
        /** AEM_JDWP_PORT / AEM_PUBLISH_JDWP_PORT, because detection could not run. */
        CONFIGURED,
        /** Nothing is known. */
        NONE
    }

    /** The operating-system and Attach API lookups; replaced in tests. */
    public interface Probe {
        PortOwners.Result owners(int port);

        JdwpListener.Reading listener(long pid);

        /** Whether a JDWP agent could listen on this port here (for restart hints). */
        default boolean portFree(int port) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /** A short name of the process, e.g. java.exe, or null. */
        default String processName(long pid) {
            try {
                return ProcessHandle.of(pid).flatMap(handle -> handle.info().command())
                        .map(command -> Paths.get(command).getFileName().toString()).orElse(null);
            } catch (InvalidPathException | SecurityException e) {
                return null;
            }
        }
    }

    /** The real lookups, each with a time limit. */
    public static Probe systemProbe() {
        return new Probe() {
            @Override
            public PortOwners.Result owners(int port) {
                return PortOwners.find(port, OWNER_TIMEOUT_MS);
            }

            @Override
            public JdwpListener.Reading listener(long pid) {
                return JdwpListener.read(pid, ATTACH_TIMEOUT_MS);
            }
        };
    }

    /** The JDWP endpoint of one instance and how it was found. */
    public static final class Endpoint {
        private final String instance;
        private final String url;
        private final int httpPort;
        private final Source source;
        private final String host;
        private final int port;
        private final long pid;
        private final String process;
        private final JdwpListener.Info detected;
        private final String note;
        private final int configuredPort;
        private final String variable;
        private final int suggestedPort;
        private final int idleMinutes;

        Endpoint(Draft d, Source source, int port, long pid, String process, JdwpListener.Info detected, String note,
                 int suggestedPort) {
            this.instance = d.instance;
            this.url = d.url;
            this.httpPort = d.httpPort;
            this.source = source;
            this.host = d.host;
            this.port = port;
            this.pid = pid;
            this.process = process;
            this.detected = detected;
            this.note = note;
            this.configuredPort = d.configuredPort;
            this.variable = d.variable;
            this.suggestedPort = suggestedPort;
            this.idleMinutes = d.idleMinutes;
        }

        public Source source() {
            return source;
        }

        public String host() {
            return host;
        }

        /** The JDWP port, or -1 when unknown. */
        public int port() {
            return port;
        }

        /** The process serving the instance, or -1 when it was not identified. */
        public long pid() {
            return pid;
        }

        /** What the JVM's JDWP agent reported, or null when the port was not detected. */
        public JdwpListener.State state() {
            return detected == null ? null : detected.state;
        }

        /** Why the port was not detected, or null. */
        public String note() {
            return note;
        }

        /** "127.0.0.1:8767", or null without a port. */
        public String address() {
            return host == null || port <= 0 ? null : host + ":" + port;
        }

        /** Whether attaching can be tried. */
        public boolean attachable() {
            return problem() == null;
        }

        /** Why this instance's JVM cannot be attached now, or null. */
        public String problem() {
            if (host == null) {
                return "JVM debugging is disabled: AEM_JDWP_HOST is not a local host.";
            }
            JdwpListener.State state = state();
            if (state == JdwpListener.State.NO_AGENT) {
                return capitalized(where()) + " was started without JDWP, so exceptions and snapshots cannot be captured "
                        + "there; everything else works. To enable it, restart " + instance + " with "
                        + agentOption(suggestedPort) + " (any free port works; it is detected automatically).";
            }
            if (state == JdwpListener.State.DEBUGGER_ATTACHED) {
                return "Another debugger is connected to " + where() + ": an IntelliJ 'Remote JVM Debug' session, or "
                        + "aem-debug in another chat (that one detaches on debug_cleanup there"
                        + (idleMinutes > 0 ? " or after " + idleMinutes + " minutes without activity" : "")
                        + "). JDWP accepts one debugger at a time: stop the other one, then retry.";
            }
            if (state == JdwpListener.State.NOT_LISTENING) {
                return capitalized(where()) + " has a JDWP agent that does not accept debuggers (" + detected.value
                        + "): most likely an IDE started it in debug mode and is its debugger (server=n), or the agent "
                        + "waits for an exception (onthrow). JDWP allows a single debugger, so aem-debug cannot attach. "
                        + "To debug it here, start " + instance + " with " + agentOption(suggestedPort) + " in place of "
                        + "that option (not in addition: a JVM loads only one JDWP agent).";
            }
            if (state == JdwpListener.State.OTHER_TRANSPORT) {
                return capitalized(where()) + " listens for debuggers on the JDWP transport " + detected.value
                        + "; aem-debug-mcp attaches over dt_socket. Restart " + instance + " with "
                        + agentOption(suggestedPort) + ".";
            }
            if (port <= 0) {
                return "No JDWP port is known for " + instance + " (" + note + "). If " + instance
                        + " runs with JDWP, set " + variable + " or call jvm_connection action=attach instance="
                        + instance + " port=<port>; otherwise restart it with " + agentOption(suggestedPort) + ".";
            }
            return null;
        }

        /** Where the port comes from, e.g. "detected from pid 4152 (java.exe), which serves port 4502". */
        public String origin() {
            switch (source) {
                case ATTACHED:
                    return "attached by this server";
                case OVERRIDE:
                    return "set with jvm_connection port= in this chat";
                case DETECTED: {
                    String text = "detected from pid " + pid + (process == null ? "" : " (" + process + ")")
                            + ", which serves port " + httpPort;
                    if (configuredPort > 0 && port > 0 && configuredPort != port) {
                        text += "; " + variable + "=" + configuredPort + " is not used";
                    }
                    return text;
                }
                case CONFIGURED:
                    return variable + "=" + configuredPort + (note == null ? "" : "; not detected: " + note);
                default:
                    return note == null ? "unknown" : note;
            }
        }

        /** Short form for headings, e.g. "127.0.0.1:8767 (detected)" or "(started without JDWP)". */
        public String label() {
            JdwpListener.State state = state();
            if (state == JdwpListener.State.NO_AGENT) {
                return "(started without JDWP)";
            }
            if (state == JdwpListener.State.DEBUGGER_ATTACHED) {
                return "(another debugger is connected)";
            }
            if (state == JdwpListener.State.NOT_LISTENING) {
                return "(JDWP agent does not accept debuggers)";
            }
            if (state == JdwpListener.State.OTHER_TRANSPORT) {
                return "(JDWP transport " + detected.transport + ")";
            }
            if (address() == null) {
                return "(no JDWP port)";
            }
            switch (source) {
                case ATTACHED:
                    return address() + " (attached)";
                case OVERRIDE:
                    return address() + " (set in this chat)";
                case DETECTED:
                    return address() + " (detected)";
                default:
                    return address() + " (" + variable + ")";
            }
        }

        /** Explains a refused connection with what is known about this endpoint. */
        public String refused() {
            switch (source) {
                case DETECTED:
                    return "JDWP port " + address() + " (" + origin() + ") refused the connection although the JVM "
                            + "reported it as free: a debugger probably attached meanwhile. Check with aem_status and "
                            + "retry.";
                case OVERRIDE:
                    return "JDWP port " + address() + " (" + origin() + ") refused the connection: another debugger is "
                            + "attached, or " + instance + " does not listen for debuggers there. jvm_connection "
                            + "port=0 forgets this port so it is detected from the JVM again.";
                default:
                    return "JDWP port " + address() + " (" + origin() + ") refused the connection. Either another "
                            + "debugger is attached (e.g. IntelliJ's 'Remote JVM Debug' - stop it and retry), or "
                            + instance + " does not listen for debuggers on this port (started without JDWP, or with "
                            + "another address).";
            }
        }

        private String where() {
            return pid > 0 ? "the " + instance + " JVM (pid " + pid + ", serving " + url + ")"
                    : instance + " (" + url + ")";
        }

        private static String capitalized(String text) {
            return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
    }

    /** The parts of an endpoint that do not depend on detection. */
    static final class Draft {
        final String instance;
        final String url;
        final int httpPort;
        final String host;
        final int configuredPort;
        final String variable;
        final int idleMinutes;

        Draft(AemInstance instance, Config config) {
            this.instance = instance.name();
            this.url = instance.baseUrl().toString();
            this.httpPort = httpPort(instance.baseUrl());
            this.host = config.jdwpHost();
            this.configuredPort = config.jdwpPort(instance.name());
            this.variable = instance.isAuthor() ? "AEM_JDWP_PORT" : "AEM_PUBLISH_JDWP_PORT";
            this.idleMinutes = config.idleDetachMinutes();
        }
    }

    private static final class Cached {
        final long pid;
        final JdwpListener.Reading reading;
        final long at;

        Cached(long pid, JdwpListener.Reading reading, long at) {
            this.pid = pid;
            this.reading = reading;
            this.at = at;
        }
    }

    private final Config config;
    private final Function<String, JdiEngine> engines;
    private final Probe probe;
    private final Map<String, Integer> overrides = new ConcurrentHashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public JdwpLocator(Config config, Function<String, JdiEngine> engines, Probe probe) {
        this.config = config;
        this.engines = engines;
        this.probe = probe;
    }

    /** Sets the port for this chat (jvm_connection port=); 0 or less forgets it. */
    public void override(String instance, int port) {
        if (port > 0) {
            overrides.put(instance, port);
        } else {
            overrides.remove(instance);
        }
        cache.remove(instance);
    }

    /** The port set for this chat, or null. */
    public Integer override(String instance) {
        return overrides.get(instance);
    }

    /** Forgets what was read from the JVM (after a failed attach); the next lookup reads it again. */
    public void invalidate(String instance) {
        cache.remove(instance);
    }

    /**
     * The endpoint of an instance. {@code refresh} reads the JVM's JDWP state again (status calls); otherwise a
     * reading of the same process is reused until an attach fails or this server detaches.
     */
    public Endpoint endpoint(AemInstance instance, boolean refresh) {
        Draft d = new Draft(instance, config);
        if (d.host == null) {
            return new Endpoint(d, Source.NONE, -1, -1, null, null, "AEM_JDWP_HOST is not a local host", -1);
        }
        JdiEngine engine = engines.apply(d.instance);
        if (engine != null && engine.isAttached()) {
            int attachedPort = PortOwners.portOf(String.valueOf(engine.endpoint()));
            if (attachedPort > 0) {
                return new Endpoint(d, Source.ATTACHED, attachedPort, -1, null, null, null, -1);
            }
        }
        Integer override = overrides.get(d.instance);
        if (override != null) {
            return new Endpoint(d, Source.OVERRIDE, override, -1, null, null, null, -1);
        }
        if (!config.detectJdwp()) {
            return fallback(d, "detection is off (AEM_DEBUG_DETECT_JDWP=false)");
        }
        PortOwners.Result owners = probe.owners(d.httpPort);
        if (owners.error != null) {
            return fallback(d, "the process serving port " + d.httpPort + " could not be looked up: " + owners.error);
        }
        long self = ProcessHandle.current().pid();
        List<String> problems = new ArrayList<>();
        for (long pid : owners.pids) {
            if (pid == self) {
                continue;
            }
            JdwpListener.Reading reading = reading(d.instance, pid, refresh, engine);
            String process = probe.processName(pid);
            if (reading.info != null) {
                JdwpListener.Info info = reading.info;
                boolean listening = info.state == JdwpListener.State.LISTENING;
                return new Endpoint(d, Source.DETECTED, listening ? info.port : -1, pid, process, info, null,
                        listening ? -1 : suggest(d));
            }
            problems.add("pid " + pid + (process == null ? "" : " (" + process + ")") + " serves port " + d.httpPort
                    + ", but " + reading.problem);
        }
        if (problems.isEmpty()) {
            return fallback(d, "no process of this user listens on port " + d.httpPort + ": AEM is not running, or runs "
                    + "as another user, in a container or in WSL");
        }
        return fallback(d, String.join("; ", problems));
    }

    private Endpoint fallback(Draft d, String note) {
        if (d.configuredPort > 0) {
            return new Endpoint(d, Source.CONFIGURED, d.configuredPort, -1, null, null, note, -1);
        }
        return new Endpoint(d, Source.NONE, -1, -1, null, null, note, suggest(d));
    }

    private JdwpListener.Reading reading(String instance, long pid, boolean refresh, JdiEngine engine) {
        long detachedAt = engine == null ? 0 : engine.lastDetachedAt();
        Cached cached = cache.get(instance);
        if (cached != null && cached.pid == pid && cached.at > detachedAt && reusable(cached.reading, refresh)) {
            return cached.reading;
        }
        JdwpListener.Reading reading = probe.listener(pid);
        // Right after this server detached, the agent reports "" until it listens again (a few milliseconds).
        for (int retry = 0; retry < 3 && isBusy(reading)
                && System.currentTimeMillis() - detachedAt < DETACH_GRACE_MS; retry++) {
            pause(300);
            reading = probe.listener(pid);
        }
        cache.put(instance, new Cached(pid, reading, System.currentTimeMillis()));
        return reading;
    }

    /**
     * Whether a cached reading still holds. Failures and states that cannot change are kept for the life of the
     * process; a listening agent can get a debugger, and an agent waiting for an exception can start listening.
     */
    static boolean reusable(JdwpListener.Reading reading, boolean refresh) {
        if (reading.info == null) {
            return reading.permanent;
        }
        switch (reading.info.state) {
            case NO_AGENT:
            case OTHER_TRANSPORT:
                return true;
            case LISTENING:
            case NOT_LISTENING:
                return !refresh;
            default:
                return false;
        }
    }

    private static boolean isBusy(JdwpListener.Reading reading) {
        return reading.info != null && reading.info.state == JdwpListener.State.DEBUGGER_ATTACHED;
    }

    /** A free port for a restart hint: the configured one, else 8767 (author) / 8768 (publish), else the next free. */
    private int suggest(Draft d) {
        int preferred = d.configuredPort > 0 ? d.configuredPort : "publish".equals(d.instance) ? 8768 : 8767;
        for (int candidate = preferred; candidate < preferred + 30 && candidate <= 65535; candidate++) {
            if (probe.portFree(candidate)) {
                return candidate;
            }
        }
        return preferred;
    }

    static int httpPort(URI baseUrl) {
        if (baseUrl.getPort() > 0) {
            return baseUrl.getPort();
        }
        return "https".equalsIgnoreCase(baseUrl.getScheme()) ? 443 : 80;
    }

    static String agentOption(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
