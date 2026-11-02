package dev.aemdebug.jdi;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.AemInstances;
import dev.aemdebug.config.Config;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Precedence, fallbacks and caching of the JDWP port lookup, with the OS and Attach API lookups faked. */
class JdwpLocatorTest {

    /** Stands in for netstat/lsof and the Attach API. */
    static final class FakeProbe implements JdwpLocator.Probe {
        PortOwners.Result owners = PortOwners.Result.found(Set.of(), "netstat");
        final Map<Long, JdwpListener.Reading> readings = new HashMap<>();
        final Set<Integer> busyPorts = new HashSet<>();
        int ownerCalls;
        int listenerCalls;
        int lastPort;

        @Override
        public PortOwners.Result owners(int port) {
            ownerCalls++;
            lastPort = port;
            return owners;
        }

        @Override
        public JdwpListener.Reading listener(long pid) {
            listenerCalls++;
            return readings.getOrDefault(pid,
                    JdwpListener.Reading.failed("not a Java VM that this user can inspect", true));
        }

        @Override
        public boolean portFree(int port) {
            return !busyPorts.contains(port);
        }

        @Override
        public String processName(long pid) {
            return "java.exe";
        }

        FakeProbe servedBy(long... pids) {
            Set<Long> set = new java.util.LinkedHashSet<>();
            for (long pid : pids) {
                set.add(pid);
            }
            owners = PortOwners.Result.found(set, "netstat");
            return this;
        }

        FakeProbe reads(long pid, String listenerAddress) {
            readings.put(pid, JdwpListener.Reading.of(JdwpListener.parse(listenerAddress)));
            return this;
        }
    }

    private final FakeProbe probe = new FakeProbe();

    private static Config.Builder config() {
        return Config.builder().authorUrl("http://localhost:4502").publishUrl("http://localhost:4503")
                .jdwp("127.0.0.1", 8767);
    }

    private JdwpLocator locator(Config config) {
        return new JdwpLocator(config, name -> null, probe);
    }

    private static AemInstance author(Config config) {
        return new AemInstances(config).author();
    }

    private static AemInstance publish(Config config) {
        return new AemInstances(config).publish();
    }

    @Test
    void aPortDetectedFromTheJvmBeatsTheConfiguredOne() {
        Config config = config().build();
        probe.servedBy(4152).reads(4152, "dt_socket:8770");
        JdwpLocator.Endpoint endpoint = locator(config).endpoint(author(config), false);
        assertThat(probe.lastPort).isEqualTo(4502);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.DETECTED);
        assertThat(endpoint.port()).isEqualTo(8770);
        assertThat(endpoint.pid()).isEqualTo(4152);
        assertThat(endpoint.attachable()).isTrue();
        assertThat(endpoint.address()).isEqualTo("127.0.0.1:8770");
        assertThat(endpoint.label()).isEqualTo("127.0.0.1:8770 (detected)");
        assertThat(endpoint.origin()).isEqualTo("detected from pid 4152 (java.exe), which serves port 4502; "
                + "AEM_JDWP_PORT=8767 is not used");
    }

    @Test
    void aPortSetInThisChatWinsWithoutAnyDetection() {
        Config config = config().build();
        JdwpLocator locator = locator(config);
        probe.servedBy(4152).reads(4152, "dt_socket:8770");
        locator.override("author", 9000);
        JdwpLocator.Endpoint endpoint = locator.endpoint(author(config), true);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.OVERRIDE);
        assertThat(endpoint.port()).isEqualTo(9000);
        assertThat(probe.ownerCalls).isZero();
        assertThat(endpoint.refused()).contains("jvm_connection port=0 forgets this port");

        locator.override("author", 0);
        assertThat(locator.override("author")).isNull();
        assertThat(locator.endpoint(author(config), false).port()).isEqualTo(8770);
    }

    @Test
    void aJvmStartedWithoutJdwpIsFinalAndTheConfiguredPortIsNotUsed() {
        Config config = config().publishJdwpPort(8768).build();
        probe.servedBy(34080).reads(34080, null);
        probe.busyPorts.add(8768);
        probe.busyPorts.add(8769);
        JdwpLocator.Endpoint endpoint = locator(config).endpoint(publish(config), true);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.DETECTED);
        assertThat(endpoint.state()).isEqualTo(JdwpListener.State.NO_AGENT);
        assertThat(endpoint.port()).isEqualTo(-1);
        assertThat(endpoint.attachable()).isFalse();
        assertThat(endpoint.label()).isEqualTo("(started without JDWP)");
        assertThat(endpoint.problem())
                .startsWith("The publish JVM (pid 34080, serving http://localhost:4503) was started without JDWP")
                .contains("restart publish with -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8770")
                .contains("detected automatically");
    }

    @Test
    void anotherDebuggerIsReportedAndCheckedAgainOnEveryLookup() {
        Config config = config().build();
        JdwpLocator locator = locator(config);
        probe.servedBy(4152).reads(4152, "");
        JdwpLocator.Endpoint endpoint = locator.endpoint(author(config), false);
        assertThat(endpoint.state()).isEqualTo(JdwpListener.State.DEBUGGER_ATTACHED);
        assertThat(endpoint.problem())
                .startsWith("Another debugger is connected to the author JVM (pid 4152, serving http://localhost:4502)")
                .contains("IntelliJ").contains("aem-debug in another chat").contains("after 15 minutes");
        locator.endpoint(author(config), false);
        assertThat(probe.listenerCalls).isEqualTo(2);

        probe.reads(4152, "dt_socket:8767");
        assertThat(locator.endpoint(author(config), false).attachable()).isTrue();
    }

    @Test
    void whenDetectionCannotRunTheConfiguredPortIsUsedAndTheReasonGiven() {
        Config config = config().build();
        probe.owners = PortOwners.Result.failed("netstat is not available");
        JdwpLocator.Endpoint endpoint = locator(config).endpoint(author(config), true);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.CONFIGURED);
        assertThat(endpoint.port()).isEqualTo(8767);
        assertThat(endpoint.attachable()).isTrue();
        assertThat(endpoint.label()).isEqualTo("127.0.0.1:8767 (AEM_JDWP_PORT)");
        assertThat(endpoint.origin()).isEqualTo("AEM_JDWP_PORT=8767; not detected: the process serving port 4502 "
                + "could not be looked up: netstat is not available");
        assertThat(endpoint.refused()).contains("refused the connection. Either another debugger is attached")
                .contains("not detected: the process serving port 4502");

        probe.servedBy();
        assertThat(locator(config).endpoint(author(config), true).origin())
                .contains("no process of this user listens on port 4502");

        probe.servedBy(1234);
        assertThat(locator(config).endpoint(author(config), true).origin())
                .contains("pid 1234 (java.exe) serves port 4502, but not a Java VM that this user can inspect");
    }

    @Test
    void withoutAConfiguredPortNothingIsKnownAndTheProblemSaysWhatToDo() {
        Config config = config().build();
        probe.owners = PortOwners.Result.failed("lsof is not available");
        JdwpLocator.Endpoint endpoint = locator(config).endpoint(publish(config), false);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.NONE);
        assertThat(endpoint.label()).isEqualTo("(no JDWP port)");
        assertThat(endpoint.problem()).startsWith("No JDWP port is known for publish (the process serving port 4503 "
                + "could not be looked up: lsof is not available)")
                .contains("set AEM_PUBLISH_JDWP_PORT").contains("jvm_connection action=attach instance=publish port=")
                .contains("address=8768");
    }

    @Test
    void detectionCanBeSwitchedOffAndNeedsALocalJdwpHost() {
        Config off = config().detectJdwp(false).build();
        JdwpLocator.Endpoint endpoint = locator(off).endpoint(author(off), true);
        assertThat(endpoint.source()).isEqualTo(JdwpLocator.Source.CONFIGURED);
        assertThat(endpoint.origin()).contains("detection is off (AEM_DEBUG_DETECT_JDWP=false)");
        assertThat(probe.ownerCalls).isZero();

        Config noHost = config().jdwp(null, 8767).build();
        assertThat(locator(noHost).endpoint(author(noHost), true).problem())
                .isEqualTo("JVM debugging is disabled: AEM_JDWP_HOST is not a local host.");
    }

    @Test
    void aJvmLaunchedByAnIdeInDebugModeIsNotCalledStartedWithoutJdwp() {
        Config config = config().build();
        JdwpLocator locator = locator(config);
        probe.servedBy(4152).readings.put(4152L, JdwpListener.Reading.of(JdwpListener.parse(null,
                "-Xmx2g -agentlib:jdwp=transport=dt_socket,server=n,address=127.0.0.1:51234")));
        JdwpLocator.Endpoint endpoint = locator.endpoint(author(config), false);
        assertThat(endpoint.state()).isEqualTo(JdwpListener.State.NOT_LISTENING);
        assertThat(endpoint.attachable()).isFalse();
        assertThat(endpoint.label()).isEqualTo("(JDWP agent does not accept debuggers)");
        assertThat(endpoint.problem()).startsWith("The author JVM (pid 4152, serving http://localhost:4502) has a JDWP "
                + "agent that does not accept debuggers (-agentlib:jdwp=transport=dt_socket,server=n,"
                + "address=127.0.0.1:51234)")
                .contains("in place of that option (not in addition").doesNotContain("started without JDWP");
        locator.endpoint(author(config), false);
        assertThat(probe.listenerCalls).isEqualTo(1);
        locator.endpoint(author(config), true);
        assertThat(probe.listenerCalls).as("an agent waiting for an exception can start listening").isEqualTo(2);
    }

    @Test
    void readingsAreCachedPerProcessUntilTheProcessChangesOrAnAttachFails() {
        Config config = config().build();
        JdwpLocator locator = locator(config);
        probe.servedBy(4152).reads(4152, "dt_socket:8767");
        locator.endpoint(author(config), false);
        locator.endpoint(author(config), false);
        assertThat(probe.listenerCalls).as("cached").isEqualTo(1);
        assertThat(probe.ownerCalls).as("the process is looked up every time").isEqualTo(2);
        locator.endpoint(author(config), true);
        assertThat(probe.listenerCalls).as("status calls read again").isEqualTo(2);

        probe.servedBy(5000).reads(5000, "dt_socket:8771");
        assertThat(locator.endpoint(author(config), false).port()).as("AEM restarted").isEqualTo(8771);
        assertThat(probe.listenerCalls).isEqualTo(3);

        locator.invalidate("author");
        locator.endpoint(author(config), false);
        assertThat(probe.listenerCalls).as("after a failed attach").isEqualTo(4);
    }

    @Test
    void fixedStatesAreNotReadAgainForTheSameProcess() {
        Config config = config().build();
        JdwpLocator locator = locator(config);
        probe.servedBy(34080).reads(34080, null);
        locator.endpoint(author(config), true);
        locator.endpoint(author(config), true);
        assertThat(probe.listenerCalls).isEqualTo(1);

        probe.servedBy(1234);
        locator.endpoint(author(config), true);
        locator.endpoint(author(config), true);
        assertThat(probe.listenerCalls).as("not a JVM: permanent for that process").isEqualTo(2);
    }
}
