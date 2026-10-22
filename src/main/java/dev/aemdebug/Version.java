package dev.aemdebug;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Server version from the filtered properties file. */
public final class Version {

    private static final String VERSION = load();

    private Version() {
    }

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/aem-debug-mcp-version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("version", "dev");
            return value.contains("${") ? "dev" : value;
        } catch (IOException e) {
            return "dev";
        }
    }
}
