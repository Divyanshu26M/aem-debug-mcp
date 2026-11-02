package dev.aemdebug.jdi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the JDWP agent of a running JVM listens, read through the Attach API. The agent publishes its address as the
 * agent property {@code sun.jdwp.listenerAddress} ("dt_socket:8767"), sets it to "" while a debugger is connected
 * (it stops listening then) and sets it again when the debugger detaches. An agent that does not listen
 * ({@code server=n}: an IDE launched the JVM in debug mode and is its debugger; or onthrow/launch) never sets it, so
 * the JVM's arguments tell that case apart from a JVM without a JDWP agent.
 */
public final class JdwpListener {

    public static final String PROPERTY = "sun.jdwp.listenerAddress";

    /** A JDWP agent option among the JVM arguments. Only this option is kept: the other arguments may hold secrets. */
    private static final Pattern JDWP_OPTION = Pattern.compile(
            "(?<!\\S)(-agentlib:jdwp(?:=\\S*)?|-Xrunjdwp(?::\\S*)?|-agentpath:\\S*jdwp\\S*)(?!\\S)");

    /** What the property says about the JDWP agent. */
    public enum State {
        /** Listening on {@link Info#port}: a debugger can attach. */
        LISTENING,
        /** A debugger is connected; the agent does not listen until it detaches. */
        DEBUGGER_ATTACHED,
        /** A JDWP agent that does not accept debuggers (server=n, onthrow or launch); {@link Info#value} = option. */
        NOT_LISTENING,
        /** The JVM has no JDWP agent (not started with -agentlib:jdwp=...). */
        NO_AGENT,
        /** Listening on a transport other than dt_socket (e.g. dt_shmem). */
        OTHER_TRANSPORT
    }

    /** The interpreted property. */
    public static final class Info {
        public final State state;
        /** The TCP port while {@link State#LISTENING}, else -1. */
        public final int port;
        /** Transport name, e.g. dt_socket, or null. */
        public final String transport;
        /** The raw property value (an address, never a secret), or null when absent. */
        public final String value;

        Info(State state, int port, String transport, String value) {
            this.state = state;
            this.port = port;
            this.transport = transport;
            this.value = value;
        }
    }

    /** The result of reading the property of one process. */
    public static final class Reading {
        /** The interpreted property, or null when it could not be read. */
        public final Info info;
        /** Why it could not be read, or null. */
        public final String problem;
        /** The problem does not go away while this process runs (not a JVM, attach disabled, ...). */
        public final boolean permanent;

        private Reading(Info info, String problem, boolean permanent) {
            this.info = info;
            this.problem = problem;
            this.permanent = permanent;
        }

        public static Reading of(Info info) {
            return new Reading(info, null, false);
        }

        public static Reading failed(String problem, boolean permanent) {
            return new Reading(null, problem, permanent);
        }
    }

    private JdwpListener() {
    }

    /** Interprets a {@code sun.jdwp.listenerAddress} value: null, "", "dt_socket:8767" or "dt_shmem:name". */
    public static Info parse(String value) {
        return parse(value, null);
    }

    /**
     * Interprets the listener address together with the JVM's arguments: without an address, a JDWP option among the
     * arguments means an agent that does not listen, none means no agent.
     */
    public static Info parse(String value, String jvmArguments) {
        if (value == null) {
            String option = jdwpOption(jvmArguments);
            return option == null ? new Info(State.NO_AGENT, -1, null, null)
                    : new Info(State.NOT_LISTENING, -1, null, option);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return new Info(State.DEBUGGER_ATTACHED, -1, null, "");
        }
        int colon = trimmed.indexOf(':');
        String transport = colon > 0 ? trimmed.substring(0, colon) : trimmed;
        if (!"dt_socket".equals(transport)) {
            return new Info(State.OTHER_TRANSPORT, -1, transport, trimmed);
        }
        int port = PortOwners.portOf(trimmed);
        if (port <= 0 || port > 65535) {
            return new Info(State.OTHER_TRANSPORT, -1, transport, trimmed);
        }
        return new Info(State.LISTENING, port, transport, trimmed);
    }

    /** The JDWP agent option among space-separated JVM arguments (sun.jvm.args), or null. */
    static String jdwpOption(String jvmArguments) {
        if (jvmArguments == null) {
            return null;
        }
        Matcher matcher = JDWP_OPTION.matcher(jvmArguments);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Whether this runtime has the Attach API (module jdk.attach, present in full JDKs). Checked here, without loading
     * {@link AttachReader}: linking that class needs the module.
     */
    public static boolean attachAvailable() {
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine", false, JdwpListener.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Reads the JDWP listener address of the JVM with this pid, giving up after {@code timeoutMs}. */
    public static Reading read(long pid, long timeoutMs) {
        if (!attachAvailable()) {
            return Reading.failed("this Java runtime has no jdk.attach module (a JRE or trimmed runtime); run the "
                    + "MCP server with a full JDK", true);
        }
        if (pid == ProcessHandle.current().pid()) {
            return Reading.failed("that is this server's own process", true);
        }
        String unsafe = signalProblem(pid);
        if (unsafe != null) {
            return Reading.failed(unsafe, true);
        }
        if (System.getProperty("sun.tools.attach.attachTimeout") == null) {
            System.setProperty("sun.tools.attach.attachTimeout", String.valueOf(Math.max(1000, timeoutMs)));
        }
        CompletableFuture<String[]> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(AttachReader.agentProperties(pid));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, "aemdbg-jdwp-detect-" + pid);
        thread.setDaemon(true);
        thread.start();
        try {
            String[] properties = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Reading.of(parse(properties[0], properties[1]));
        } catch (TimeoutException e) {
            return Reading.failed("its JVM did not answer the attach request within " + timeoutMs + " ms", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AttachReader.NotAttachableException) {
                return Reading.failed(cause.getMessage(), true);
            }
            return Reading.failed("reading its JDWP address failed (" + cause + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reading.failed("interrupted", false);
        }
    }

    /**
     * On Linux and macOS the Attach API wakes the target with SIGQUIT. A JVM started with -Xrs does not handle
     * SIGQUIT, and older JDKs would then terminate it, so such JVMs are never attached. Null when attaching is safe.
     */
    static String signalProblem(long pid) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return null;
        }
        if (os.contains("linux")) {
            Path status = Paths.get("/proc", Long.toString(pid), "status");
            try {
                Long caught = caughtSignals(new String(Files.readAllBytes(status), StandardCharsets.ISO_8859_1));
                if (caught != null && !catchesSigquit(caught)) {
                    return "its JVM does not handle SIGQUIT (started with -Xrs?), and attaching would send it one";
                }
                return null;
            } catch (IOException e) {
                return null;
            }
        }
        if (os.contains("mac") || os.contains("darwin")) {
            Optional<String[]> arguments = ProcessHandle.of(pid).flatMap(handle -> handle.info().arguments());
            if (arguments.isEmpty()) {
                return "its command line cannot be read, so it is unclear whether it handles SIGQUIT (the attach "
                        + "request is a SIGQUIT)";
            }
            for (String argument : arguments.get()) {
                if (reducesSignals(argument)) {
                    return "its JVM runs with " + argument + " and would not handle the attach request's SIGQUIT";
                }
            }
            return null;
        }
        return "the JDWP port cannot be read from the JVM on " + System.getProperty("os.name");
    }

    /** The SigCgt mask of a /proc/PID/status text, or null. */
    static Long caughtSignals(String status) {
        for (String line : status.split("\\R")) {
            if (line.startsWith("SigCgt:")) {
                try {
                    return Long.parseUnsignedLong(line.substring("SigCgt:".length()).trim(), 16);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** SIGQUIT is signal 3: bit 2 of the mask. */
    static boolean catchesSigquit(long mask) {
        return (mask & (1L << 2)) != 0;
    }

    static boolean reducesSignals(String argument) {
        return "-Xrs".equals(argument) || "-XX:+ReduceSignalUsage".equals(argument);
    }
}
