package dev.aemdebug.jdi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Finds the processes that listen on a local TCP port with the operating system's own means: {@code netstat -ano}
 * on Windows, {@code /proc/net/tcp} (then {@code lsof}) on Linux and {@code lsof} on macOS. On Linux and macOS only
 * processes of the current user are found, which are also the only ones the Attach API can read. Every lookup is
 * bounded by a time limit.
 */
public final class PortOwners {

    /** The processes listening on a port, or why they could not be looked up. */
    public static final class Result {
        public final Set<Long> pids;
        /** What was used, e.g. "netstat", "/proc/net/tcp" or "lsof". */
        public final String method;
        /** Why the lookup could not run, or null. */
        public final String error;

        Result(Set<Long> pids, String method, String error) {
            this.pids = Collections.unmodifiableSet(new LinkedHashSet<>(pids));
            this.method = method;
            this.error = error;
        }

        static Result found(Set<Long> pids, String method) {
            return new Result(pids, method, null);
        }

        static Result failed(String error) {
            return new Result(Collections.emptySet(), null, error);
        }
    }

    private PortOwners() {
    }

    /** The PIDs listening on {@code port} on this machine (any local address), within {@code timeoutMs}. */
    public static Result find(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                return windows(port, deadline);
            }
            if (os.contains("linux")) {
                return linux(port, deadline);
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return Result.found(lsof(port, deadline), "lsof");
            }
            return Result.failed("not supported on " + System.getProperty("os.name"));
        } catch (IOException e) {
            return Result.failed(e.getMessage());
        }
    }

    private static Result windows(int port, long deadline) throws IOException {
        String netstat = netstatCommand();
        Set<Long> pids = parseNetstat(run(List.of(netstat, "-ano", "-p", "TCP"), deadline), port);
        if (pids.isEmpty()) {
            pids = parseNetstat(run(List.of(netstat, "-ano", "-p", "TCPv6"), deadline), port);
        }
        return Result.found(pids, "netstat");
    }

    private static String netstatCommand() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path path = Paths.get(root, "System32", "netstat.exe");
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
        }
        return "netstat";
    }

    private static Result linux(int port, long deadline) throws IOException {
        IOException procProblem = null;
        try {
            Set<Long> pids = procOwners(port, deadline);
            if (!pids.isEmpty()) {
                return Result.found(pids, "/proc/net/tcp");
            }
        } catch (IOException e) {
            procProblem = e;
        }
        try {
            return Result.found(lsof(port, deadline), "/proc/net/tcp and lsof");
        } catch (IOException e) {
            if (procProblem != null) {
                throw procProblem;
            }
            // lsof is not installed: /proc found nobody of this user listening.
            return Result.found(Collections.emptySet(), "/proc/net/tcp");
        }
    }

    private static Set<Long> lsof(int port, long deadline) throws IOException {
        String command = Files.isExecutable(Paths.get("/usr/sbin/lsof")) ? "/usr/sbin/lsof" : "lsof";
        return parseLsof(run(List.of(command, "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"), deadline));
    }

    /**
     * PIDs from {@code netstat -ano} that listen on {@code port}. Listening rows are recognized by their unspecified
     * foreign address (0.0.0.0:0, [::]:0) because the state column is translated on non-English Windows.
     */
    static Set<Long> parseNetstat(String output, int port) {
        Set<Long> pids = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 4 || !tokens[0].equalsIgnoreCase("TCP")) {
                continue;
            }
            String foreign = tokens[2];
            if (!foreign.equals("0.0.0.0:0") && !foreign.equals("[::]:0") && !foreign.equals("*:*")) {
                continue;
            }
            if (portOf(tokens[1]) != port) {
                continue;
            }
            long pid = parsePid(tokens[tokens.length - 1]);
            if (pid > 0) {
                pids.add(pid);
            }
        }
        return pids;
    }

    /** PIDs printed by {@code lsof -t}, one per line. */
    static Set<Long> parseLsof(String output) {
        Set<Long> pids = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            long pid = parsePid(line.trim());
            if (pid > 0) {
                pids.add(pid);
            }
        }
        return pids;
    }

    /** Socket inodes in LISTEN state (0A) on {@code port} from the content of /proc/net/tcp or /proc/net/tcp6. */
    static Set<String> parseProcNetTcp(String content, int port) {
        Set<String> inodes = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 10 || !tokens[0].endsWith(":")) {
                continue;
            }
            String local = tokens[1];
            int colon = local.lastIndexOf(':');
            if (colon < 0 || !"0A".equalsIgnoreCase(tokens[3])) {
                continue;
            }
            int localPort;
            try {
                localPort = Integer.parseInt(local.substring(colon + 1), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            if (localPort == port && !"0".equals(tokens[9])) {
                inodes.add(tokens[9]);
            }
        }
        return inodes;
    }

    private static Set<Long> procOwners(int port, long deadline) throws IOException {
        Set<String> inodes = new HashSet<>();
        for (String name : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            Path path = Paths.get(name);
            if (Files.isReadable(path)) {
                inodes.addAll(parseProcNetTcp(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1),
                        port));
            }
        }
        Set<Long> pids = new LinkedHashSet<>();
        if (inodes.isEmpty()) {
            return pids;
        }
        Set<String> wanted = new HashSet<>();
        for (String inode : inodes) {
            wanted.add("socket:[" + inode + "]");
        }
        try (DirectoryStream<Path> processes = Files.newDirectoryStream(Paths.get("/proc"),
                path -> path.getFileName().toString().chars().allMatch(Character::isDigit))) {
            for (Path process : processes) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("looking through /proc took too long");
                }
                try (DirectoryStream<Path> fds = Files.newDirectoryStream(process.resolve("fd"))) {
                    for (Path fd : fds) {
                        try {
                            if (wanted.contains(Files.readSymbolicLink(fd).toString())) {
                                pids.add(Long.parseLong(process.getFileName().toString()));
                                break;
                            }
                        } catch (IOException | UnsupportedOperationException e) {
                            // the descriptor closed meanwhile
                        }
                    }
                } catch (IOException | SecurityException e) {
                    // a process of another user, or gone
                }
            }
        }
        return pids;
    }

    /** The port of "127.0.0.1:4502", "[::]:4502" or "[fe80::1%12]:4502", or -1. */
    public static int portOf(String address) {
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parsePid(String value) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit) || value.length() > 18) {
            return -1;
        }
        return Long.parseLong(value);
    }

    /** Runs a command and returns its standard output; fails when it does not finish before the deadline. */
    static String run(List<String> command, long deadline) throws IOException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new IOException("no time left to run " + command.get(0));
        }
        Process process;
        try {
            process = new ProcessBuilder(new ArrayList<>(command))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new IOException(command.get(0) + " is not available (" + e.getMessage() + ")");
        }
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(out);
            } catch (IOException e) {
                // process ended
            }
        }, "aemdbg-port-owners");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.get(0) + " did not answer within " + remaining + " ms");
            }
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while running " + command.get(0));
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
