package dev.aemdebug.jdi;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.ListeningConnector;
import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.config.Config;
import dev.aemdebug.fixture.FixtureApp;
import dev.aemdebug.server.Services;
import dev.aemdebug.tools.ToolContext;
import dev.aemdebug.tools.ToolRegistry;
import dev.aemdebug.tools.impl.ToolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Detects the JDWP port of a real JVM (the fixture app) from its HTTP port, the way it is done for AEM, and follows the
 * agent through attach and detach. The fixture listens on an ephemeral JDWP port, so it gets a new port after every
 * detach.
 */
class JdwpDetectionFixtureTest {

    private static final Pattern JDWP_PORT =
            Pattern.compile("Listening for transport dt_socket at address: (?:\\S*:)?(\\d+)");
    private static final Pattern HTTP_PORT = Pattern.compile("FIXTURE_PORT=(\\d+)");
    private static final String LISTENING_AGENT =
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0";

    @TempDir
    Path temp;

    /** A fixture JVM and the lines it prints. */
    private static final class Fixture implements AutoCloseable {
        final Process process;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final StringBuffer output = new StringBuffer();
        final List<Integer> jdwpPorts = new ArrayList<>();
        int httpPort;

        /** Starts the fixture with a JDWP agent option (null = none); waits for its listener when it is a server. */
        Fixture(String agentOption) throws Exception {
            Path classes = Paths.get(FixtureApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            if (agentOption != null) {
                command.add(agentOption);
            }
            command.add("-Xmx64m");
            command.add("-cp");
            command.add(classes.toString());
            command.add(FixtureApp.class.getName());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        output.append(line).append('\n');
                        lines.add(line);
                    }
                } catch (IOException e) {
                    output.append("(output closed)\n");
                }
            }, "jdwp-detection-fixture");
            reader.setDaemon(true);
            reader.start();
            if (agentOption != null && agentOption.contains("server=y")) {
                nextJdwpPort();
            }
            while (httpPort == 0) {
                read();
            }
        }

        /** Waits for the agent's next "Listening for transport dt_socket at address: N". */
        int nextJdwpPort() throws InterruptedException {
            int known = jdwpPorts.size();
            while (jdwpPorts.size() == known) {
                read();
            }
            return jdwpPorts.get(jdwpPorts.size() - 1);
        }

        private void read() throws InterruptedException {
            String line = lines.poll(30, TimeUnit.SECONDS);
            assertThat(line).as("fixture output so far:%n%s", output).isNotNull();
            Matcher jdwp = JDWP_PORT.matcher(line);
            if (jdwp.find()) {
                jdwpPorts.add(Integer.parseInt(jdwp.group(1)));
            }
            Matcher http = HTTP_PORT.matcher(line);
            if (http.find()) {
                httpPort = Integer.parseInt(http.group(1));
            }
        }

        long pid() {
            return process.pid();
        }

        @Override
        public void close() throws Exception {
            try {
                process.getOutputStream().close();
            } catch (IOException e) {
                // already gone
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static Config config(int httpPort) {
        return Config.builder().authorUrl("http://127.0.0.1:" + httpPort).jdwp("127.0.0.1", 1).build();
    }

    @Test
    void theJdwpPortIsDetectedFromTheHttpPortAndFollowedThroughAttachAndDetach() throws Exception {
        assumeTrue(JdiSupport.available() && JdwpListener.attachAvailable(), "jdk.jdi / jdk.attach not available");
        try (Fixture fixture = new Fixture(LISTENING_AGENT)) {
            int jdwpPort = fixture.jdwpPorts.get(0);

            PortOwners.Result owners = PortOwners.find(fixture.httpPort, 10_000);
            assertThat(owners.error).as("lookup with %s", owners.method).isNull();
            assertThat(owners.pids).contains(fixture.pid());

            JdwpListener.Reading free = JdwpListener.read(fixture.pid(), 10_000);
            assertThat(free.problem).isNull();
            assertThat(free.info.state).isEqualTo(JdwpListener.State.LISTENING);
            assertThat(free.info.value).isEqualTo("dt_socket:" + jdwpPort);

            Config config = config(fixture.httpPort);
            AemInstance author = new AemInstances(config).author();
            JdiEngine engine = new JdiEngine(30, "author");
            JdwpLocator locator = new JdwpLocator(config, name -> engine, JdwpLocator.systemProbe());
            JdwpLocator.Endpoint endpoint = locator.endpoint(author, true);
            assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.DETECTED);
            assertThat(endpoint.port()).isEqualTo(jdwpPort);
            assertThat(endpoint.pid()).isEqualTo(fixture.pid());
            assertThat(endpoint.origin()).contains("which serves port " + fixture.httpPort)
                    .contains("AEM_JDWP_PORT=1 is not used");

            engine.attach(endpoint.host(), endpoint.port(), 10_000);
            try {
                JdwpListener.Reading busy = JdwpListener.read(fixture.pid(), 10_000);
                assertThat(busy.info.state).as("while a debugger is connected").isEqualTo(
                        JdwpListener.State.DEBUGGER_ATTACHED);
                assertThat(busy.info.value).isEmpty();
                assertThat(locator.endpoint(author, true).source()).isEqualTo(JdwpLocator.Source.ATTACHED);
                JdwpLocator otherServer = new JdwpLocator(config, name -> null, JdwpLocator.systemProbe());
                assertThat(otherServer.endpoint(author, true).problem())
                        .startsWith("Another debugger is connected to the author JVM (pid " + fixture.pid());
            } finally {
                engine.detach("test");
            }

            int newPort = fixture.nextJdwpPort();
            JdwpLocator.Endpoint afterDetach = locator.endpoint(author, false);
            assertThat(afterDetach.source()).isEqualTo(JdwpLocator.Source.DETECTED);
            assertThat(afterDetach.port()).as("read again after the detach").isEqualTo(newPort);
            assertThat(JdwpListener.read(fixture.pid(), 10_000).info.value).isEqualTo("dt_socket:" + newPort);
        }
    }

    @Test
    void jvmConnectionAttachesToTheDetectedPortAndFollowsItAfterADetach() throws Exception {
        assumeTrue(JdiSupport.available() && JdwpListener.attachAvailable(), "jdk.jdi / jdk.attach not available");
        try (Fixture fixture = new Fixture(LISTENING_AGENT)) {
            Config config = Config.builder().authorUrl("http://127.0.0.1:" + fixture.httpPort).jdwp("127.0.0.1", 1)
                    .artifactsDir(temp).build();
            Services services = new Services(config);
            try {
                ToolRegistry registry = ToolFactory.createRegistry(services);
                ToolContext context = ToolContext.detached();
                String attach = registry.invoke("jvm_connection", context, Map.of("action", "attach")).allText();
                assertThat(attach).contains("at 127.0.0.1:" + fixture.jdwpPorts.get(0))
                        .contains("The port was detected from pid " + fixture.pid());
                registry.invoke("jvm_connection", context, Map.of("action", "detach"));

                int newPort = fixture.nextJdwpPort();
                String again = registry.invoke("jvm_connection", context, Map.of("action", "attach")).allText();
                assertThat(again).as("the new ephemeral port after the detach").contains("at 127.0.0.1:" + newPort);
            } finally {
                services.cleanup.runAll(ToolContext.detached(), true);
            }
        }
    }

    @Test
    void aJvmWhoseAgentDoesNotListenIsNotReportedAsStartedWithoutJdwp() throws Exception {
        assumeTrue(JdiSupport.available() && JdwpListener.attachAvailable(), "jdk.jdi / jdk.attach not available");
        ListeningConnector connector = null;
        for (ListeningConnector candidate : Bootstrap.virtualMachineManager().listeningConnectors()) {
            if ("com.sun.jdi.SocketListen".equals(candidate.name())) {
                connector = candidate;
            }
        }
        assumeTrue(connector != null, "no SocketListen connector");
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("port").setValue("0");
        if (arguments.containsKey("localAddress")) {
            arguments.get("localAddress").setValue("127.0.0.1");
        }
        int debuggerPort = PortOwners.portOf(connector.startListening(arguments));
        ListeningConnector listening = connector;
        // Like an IDE that launches the JVM in debug mode: the agent connects out (server=n) and never listens.
        CompletableFuture<VirtualMachine> debuggee = CompletableFuture.supplyAsync(() -> {
            try {
                return listening.accept(arguments);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try (Fixture fixture = new Fixture(
                "-agentlib:jdwp=transport=dt_socket,server=n,suspend=n,address=127.0.0.1:" + debuggerPort)) {
            VirtualMachine machine = debuggee.get(30, TimeUnit.SECONDS);
            try {
                JdwpListener.Reading reading = JdwpListener.read(fixture.pid(), 10_000);
                assertThat(reading.problem).isNull();
                assertThat(reading.info.state).isEqualTo(JdwpListener.State.NOT_LISTENING);
                assertThat(reading.info.value).contains("server=n");

                Config config = config(fixture.httpPort);
                JdwpLocator locator = new JdwpLocator(config, name -> null, JdwpLocator.systemProbe());
                assertThat(locator.endpoint(new AemInstances(config).author(), true).problem())
                        .contains("has a JDWP agent that does not accept debuggers")
                        .doesNotContain("started without JDWP");
            } finally {
                machine.dispose();
            }
        } finally {
            connector.stopListening(arguments);
        }
    }

    @Test
    void aJvmWithoutJdwpIsReportedWithoutFallingBackToTheConfiguredPort() throws Exception {
        assumeTrue(JdwpListener.attachAvailable(), "jdk.attach not available");
        try (Fixture fixture = new Fixture(null)) {
            JdwpListener.Reading reading = JdwpListener.read(fixture.pid(), 10_000);
            assertThat(reading.problem).isNull();
            assertThat(reading.info.state).isEqualTo(JdwpListener.State.NO_AGENT);

            Config config = config(fixture.httpPort);
            JdwpLocator locator = new JdwpLocator(config, name -> null, JdwpLocator.systemProbe());
            JdwpLocator.Endpoint endpoint = locator.endpoint(new AemInstances(config).author(), true);
            assertThat(endpoint.port()).isEqualTo(-1);
            assertThat(endpoint.problem()).startsWith("The author JVM (pid " + fixture.pid() + ", serving http://127.0.0.1:"
                    + fixture.httpPort + ") was started without JDWP");
        }
    }
}
