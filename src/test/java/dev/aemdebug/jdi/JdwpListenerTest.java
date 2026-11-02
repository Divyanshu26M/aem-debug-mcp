package dev.aemdebug.jdi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdwpListenerTest {

    @Test
    void theListenerAddressTellsTheThreeStatesApart() {
        JdwpListener.Info listening = JdwpListener.parse("dt_socket:8767");
        assertThat(listening.state).isEqualTo(JdwpListener.State.LISTENING);
        assertThat(listening.port).isEqualTo(8767);
        assertThat(listening.transport).isEqualTo("dt_socket");

        JdwpListener.Info busy = JdwpListener.parse("");
        assertThat(busy.state).isEqualTo(JdwpListener.State.DEBUGGER_ATTACHED);
        assertThat(busy.port).isEqualTo(-1);

        JdwpListener.Info none = JdwpListener.parse(null);
        assertThat(none.state).isEqualTo(JdwpListener.State.NO_AGENT);
        assertThat(none.value).isNull();
    }

    @Test
    void otherTransportsAndUnusualAddresses() {
        assertThat(JdwpListener.parse("dt_socket:127.0.0.1:8770").port).isEqualTo(8770);
        assertThat(JdwpListener.parse(" dt_socket:8771 ").port).isEqualTo(8771);
        JdwpListener.Info shmem = JdwpListener.parse("dt_shmem:javadebug");
        assertThat(shmem.state).isEqualTo(JdwpListener.State.OTHER_TRANSPORT);
        assertThat(shmem.transport).isEqualTo("dt_shmem");
        assertThat(JdwpListener.parse("dt_socket:abc").state).isEqualTo(JdwpListener.State.OTHER_TRANSPORT);
    }

    @Test
    void anAgentThatDoesNotListenIsToldApartFromNoAgent() {
        String ideLaunch = "-Xmx2g -agentlib:jdwp=transport=dt_socket,address=127.0.0.1:51234,suspend=y,server=n "
                + "-Dsome.password=secret-value -Dfile.encoding=UTF-8";
        JdwpListener.Info info = JdwpListener.parse(null, ideLaunch);
        assertThat(info.state).isEqualTo(JdwpListener.State.NOT_LISTENING);
        assertThat(info.value).as("only the JDWP option is kept")
                .isEqualTo("-agentlib:jdwp=transport=dt_socket,address=127.0.0.1:51234,suspend=y,server=n");
        assertThat(JdwpListener.parse(null, "-Xrunjdwp:transport=dt_socket,server=n,address=5005").state)
                .isEqualTo(JdwpListener.State.NOT_LISTENING);
        assertThat(JdwpListener.parse(null, "-agentpath:/opt/jdk/lib/libjdwp.so=transport=dt_socket").state)
                .isEqualTo(JdwpListener.State.NOT_LISTENING);
        assertThat(JdwpListener.parse(null, "-Xmx2g -Dsome.password=secret-value").state)
                .isEqualTo(JdwpListener.State.NO_AGENT);
        assertThat(JdwpListener.parse(null, "-agentlib:jdwpx=foo").state).isEqualTo(JdwpListener.State.NO_AGENT);
        assertThat(JdwpListener.parse(null, null).state).isEqualTo(JdwpListener.State.NO_AGENT);
        assertThat(JdwpListener.parse("dt_socket:8767", ideLaunch).state).isEqualTo(JdwpListener.State.LISTENING);
    }

    @Test
    void jvmsThatDoNotHandleSigquitAreNeverAttached() {
        assertThat(JdwpListener.caughtSignals("Name:\tjava\nSigBlk:\t0000000000000000\nSigCgt:\t0000000181005ccf\n"))
                .isEqualTo(0x181005ccfL);
        assertThat(JdwpListener.catchesSigquit(0x181005ccfL)).isTrue();
        assertThat(JdwpListener.catchesSigquit(0x181005cc3L)).isFalse();
        assertThat(JdwpListener.caughtSignals("Name:\tjava\n")).isNull();
        assertThat(JdwpListener.reducesSignals("-Xrs")).isTrue();
        assertThat(JdwpListener.reducesSignals("-XX:+ReduceSignalUsage")).isTrue();
        assertThat(JdwpListener.reducesSignals("-Xmx2g")).isFalse();
    }

    @Test
    void thisProcessIsNeverAttached() {
        JdwpListener.Reading reading = JdwpListener.read(ProcessHandle.current().pid(), 1000);
        assertThat(reading.info).isNull();
        assertThat(reading.permanent).isTrue();
    }
}
