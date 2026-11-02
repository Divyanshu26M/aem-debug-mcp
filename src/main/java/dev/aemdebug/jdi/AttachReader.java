package dev.aemdebug.jdi;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.util.Properties;

/**
 * The only class that touches the Attach API (module jdk.attach); loaded only after {@link JdwpListener} checked that
 * the module is present.
 */
final class AttachReader {

    /** The process is not a Java VM that this user can attach to; this does not change while it runs. */
    static final class NotAttachableException extends Exception {
        NotAttachableException(String message) {
            super(message);
        }
    }

    private AttachReader() {
    }

    /**
     * The values of {@link JdwpListener#PROPERTY} (null when not set) and of the JVM's arguments (sun.jvm.args) in the
     * JVM with this pid. Only JVMs listed by the Attach API for this user are attached, so no other process ever
     * receives an attach request.
     */
    static String[] agentProperties(long pid) throws NotAttachableException, IOException {
        String id = Long.toString(pid);
        VirtualMachineDescriptor descriptor = null;
        for (VirtualMachineDescriptor candidate : VirtualMachine.list()) {
            if (id.equals(candidate.id())) {
                descriptor = candidate;
                break;
            }
        }
        if (descriptor == null) {
            throw new NotAttachableException("not a Java VM that this user can inspect");
        }
        VirtualMachine machine;
        try {
            machine = VirtualMachine.attach(descriptor);
        } catch (AttachNotSupportedException e) {
            throw new NotAttachableException("its JVM does not accept the attach mechanism (" + e.getMessage() + ")");
        }
        try {
            Properties properties = machine.getAgentProperties();
            return new String[] {properties.getProperty(JdwpListener.PROPERTY), properties.getProperty("sun.jvm.args")};
        } finally {
            try {
                machine.detach();
            } catch (IOException e) {
                // already gone
            }
        }
    }
}
