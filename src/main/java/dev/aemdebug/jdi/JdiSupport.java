package dev.aemdebug.jdi;

/** Whether this Java runtime provides the JDI API (module jdk.jdi, present in full JDKs). */
public final class JdiSupport {

    private JdiSupport() {
    }

    public static boolean available() {
        try {
            Class.forName("com.sun.jdi.Bootstrap", false, JdiSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static String unavailableMessage() {
        return "This Java runtime has no jdk.jdi module (a JRE or trimmed runtime), so JVM debugging is unavailable. "
                + "Run the MCP server with a full JDK 17+ (java.home=" + System.getProperty("java.home") + ").";
    }
}
