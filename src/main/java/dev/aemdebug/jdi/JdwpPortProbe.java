package dev.aemdebug.jdi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Checks whether a JDWP agent is accepting debugger connections. Performs a proper JDWP handshake and closes the
 * socket right away, which the agent treats like a debugger attaching and detaching.
 */
public final class JdwpPortProbe {

    /** Port state as seen from this machine. */
    public enum State {
        LISTENING, NOT_ACCEPTING, NOT_JDWP, TIMEOUT, ERROR
    }

    public static final class Result {
        public final State state;
        public final String detail;

        Result(State state, String detail) {
            this.state = state;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return state + (detail == null ? "" : " (" + detail + ")");
        }
    }

    private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);

    private JdwpPortProbe() {
    }

    public static Result probe(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(HANDSHAKE);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] reply = new byte[HANDSHAKE.length];
            int read = 0;
            while (read < reply.length) {
                int n = in.read(reply, read, reply.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read == reply.length && Arrays.equals(reply, HANDSHAKE)) {
                return new Result(State.LISTENING, "JDWP handshake OK");
            }
            return new Result(State.NOT_JDWP, "port is open but did not answer the JDWP handshake");
        } catch (ConnectException e) {
            return new Result(State.NOT_ACCEPTING, "connection refused");
        } catch (SocketTimeoutException e) {
            return new Result(State.TIMEOUT, "no answer within " + timeoutMs + " ms");
        } catch (IOException e) {
            return new Result(State.ERROR, e.getMessage());
        }
    }
}
