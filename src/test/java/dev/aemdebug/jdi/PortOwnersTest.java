package dev.aemdebug.jdi;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class PortOwnersTest {

    private static final String NETSTAT_ENGLISH = String.join("\r\n",
            "",
            "Active Connections",
            "",
            "  Proto  Local Address          Foreign Address        State           PID",
            "  TCP    0.0.0.0:4502           0.0.0.0:0              LISTENING       4152",
            "  TCP    0.0.0.0:45020          0.0.0.0:0              LISTENING       999",
            "  TCP    127.0.0.1:8767         0.0.0.0:0              LISTENING       4152",
            "  TCP    127.0.0.1:4502         127.0.0.1:55310        ESTABLISHED     4152",
            "  TCP    127.0.0.1:55310        127.0.0.1:4502         ESTABLISHED     22140",
            "  TCP    127.0.0.1:61000        127.0.0.1:4502         TIME_WAIT       0",
            "  TCP    [::]:4502              [::]:0                 LISTENING       4152",
            "  TCP    [::1]:4502             [::1]:55304            FIN_WAIT_2      4152",
            "  TCP    [::1]:55304            [::1]:4502             CLOSE_WAIT      22140",
            "  UDP    0.0.0.0:4502           *:*                                    777");

    /** State words are translated on non-English Windows; listening rows are found by the foreign address. */
    private static final String NETSTAT_GERMAN = String.join("\r\n",
            "",
            "Aktive Verbindungen",
            "",
            "  Proto  Lokale Adresse         Remoteadresse          Status           PID",
            "  TCP    0.0.0.0:4503           0.0.0.0:0              ABH\u00d6REN          34080",
            "  TCP    127.0.0.1:4503         127.0.0.1:61001        HERGESTELLT      34080",
            "  TCP    [::]:4503              [::]:0                 ABH\u00d6REN          34080",
            "  TCP    [fe80::1%12]:4503      [::]:0                 ABH\u00d6REN          34080");

    private static final String NETSTAT_SPANISH_IPV6_ONLY = String.join("\n",
            "  Proto  Direcci\u00f3n local          Direcci\u00f3n remota        Estado           PID",
            "  TCP    [::1]:4503             [::]:0                 ESCUCHANDO       5120",
            "  TCP    [::1]:4503             [::1]:50000            ESTABLECIDA      5120");

    private static final String PROC_NET_TCP = String.join("\n",
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 0100007F:223F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41234 1 "
                    + "0000000000000000 100 0 0 10 0",
            "   1: 00000000:1196 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41235 1 "
                    + "0000000000000000 100 0 0 10 0",
            "   2: 0100007F:1196 0100007F:D7B2 01 00000000:00000000 00:00000000 00000000  1000        0 41299 1 "
                    + "0000000000000000 20 4 30 10 -1",
            "   3: 0100007F:AFC8 0100007F:1196 06 00000000:00000000 03:00001623 00000000     0        0 0 3 "
                    + "0000000000000000");

    private static final String PROC_NET_TCP6 = String.join("\n",
            "  sl  local_address                         remote_address                        st tx_queue rx_queue "
                    + "tr tm->when retrnsmt   uid  timeout inode",
            "   0: 00000000000000000000000000000000:1196 00000000000000000000000000000000:0000 0A "
                    + "00000000:00000000 00:00000000 00000000  1000        0 51234 1 0000000000000000 100 0 0 10 0");

    @Test
    void netstatListeningRowsAreFoundByTheirForeignAddressInAnyLanguage() {
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH, 4502)).containsExactly(4152L);
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH, 8767)).containsExactly(4152L);
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH, 45020)).containsExactly(999L);
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH, 55310)).isEmpty();
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH, 1234)).isEmpty();
        assertThat(PortOwners.parseNetstat(NETSTAT_GERMAN, 4503)).containsExactly(34080L);
        assertThat(PortOwners.parseNetstat(NETSTAT_SPANISH_IPV6_ONLY, 4503)).containsExactly(5120L);
        assertThat(PortOwners.parseNetstat(NETSTAT_ENGLISH + "\r\n" + NETSTAT_SPANISH_IPV6_ONLY.replace("4503", "4502"),
                4502)).containsExactly(4152L, 5120L);
    }

    @Test
    void lsofPidsAndProcNetTcpInodes() {
        assertThat(PortOwners.parseLsof("4152\n34080\n")).containsExactly(4152L, 34080L);
        assertThat(PortOwners.parseLsof("")).isEmpty();
        assertThat(PortOwners.parseLsof("lsof: WARNING: can't stat() fuse file system\n4152")).containsExactly(4152L);

        assertThat(PortOwners.parseProcNetTcp(PROC_NET_TCP, 4502)).containsExactly("41235");
        assertThat(PortOwners.parseProcNetTcp(PROC_NET_TCP, 8767)).containsExactly("41234");
        assertThat(PortOwners.parseProcNetTcp(PROC_NET_TCP, 45000)).isEmpty();
        assertThat(PortOwners.parseProcNetTcp(PROC_NET_TCP6, 4502)).containsExactly("51234");
    }

    @Test
    void portsOfAddresses() {
        assertThat(PortOwners.portOf("0.0.0.0:4502")).isEqualTo(4502);
        assertThat(PortOwners.portOf("[::]:4502")).isEqualTo(4502);
        assertThat(PortOwners.portOf("[fe80::1%12]:4502")).isEqualTo(4502);
        assertThat(PortOwners.portOf("127.0.0.1:8767")).isEqualTo(8767);
        assertThat(PortOwners.portOf("localhost")).isEqualTo(-1);
        assertThat(PortOwners.portOf("host:abc")).isEqualTo(-1);
    }

    @Test
    void findsThisProcessListeningOnALocalPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            PortOwners.Result result = PortOwners.find(socket.getLocalPort(), 10_000);
            assertThat(result.error).as("lookup with %s", result.method).isNull();
            assertThat(result.pids).contains(ProcessHandle.current().pid());
        }
    }
}
