package dev.aemdebug.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.aem.AemInstance;
import dev.aemdebug.aem.FelixConsole;
import dev.aemdebug.aem.SlingSettings;
import dev.aemdebug.config.Config;
import dev.aemdebug.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locates crx-quickstart/logs/error.log: AEM_LOG_DIR (author) or sling.home from the Sling Settings printer.
 */
public final class LogLocator {

    private static final Logger LOG = LoggerFactory.getLogger(LogLocator.class);

    /** Result of locating the log directory. */
    public static final class Location {
        public final Path errorLog;
        public final String slingHome;
        public final String note;

        Location(Path errorLog, String slingHome, String note) {
            this.errorLog = errorLog;
            this.slingHome = slingHome;
            this.note = note;
        }

        public boolean readable() {
            return errorLog != null && Files.isReadable(errorLog);
        }
    }

    private final Config config;
    private final FelixConsole felix;
    private final Map<String, Location> cache = new ConcurrentHashMap<>();

    public LogLocator(Config config, FelixConsole felix) {
        this.config = config;
        this.felix = felix;
    }

    public Location locate(ToolContext context, AemInstance instance) {
        Location cached = cache.get(instance.origin());
        if (cached != null && cached.readable()) {
            return cached;
        }
        Location location = detect(context, instance);
        if (location.readable()) {
            cache.put(instance.origin(), location);
        }
        return location;
    }

    private Location detect(ToolContext context, AemInstance instance) {
        if (instance.isAuthor() && config.logDir() != null) {
            Path log = config.logDir().resolve("error.log");
            return new Location(log, null, Files.isReadable(log) ? "from AEM_LOG_DIR"
                    : "AEM_LOG_DIR is set but " + log + " is not readable");
        }
        String slingHome = null;
        try {
            SlingSettings settings = felix.slingSettings(context, instance);
            slingHome = settings.slingHome;
        } catch (RuntimeException e) {
            LOG.debug("Sling settings not available: {}", e.getMessage());
            return new Location(null, null, "sling.home unknown (" + e.getMessage() + "); set AEM_LOG_DIR");
        }
        if (slingHome == null || slingHome.isBlank()) {
            return new Location(null, null, "sling.home not reported; set AEM_LOG_DIR");
        }
        Path home;
        try {
            home = Paths.get(slingHome);
        } catch (RuntimeException e) {
            return new Location(null, slingHome, "sling.home is not a valid path; set AEM_LOG_DIR");
        }
        if (!home.isAbsolute()) {
            return new Location(null, slingHome, "sling.home is relative; set AEM_LOG_DIR to crx-quickstart/logs");
        }
        Path log = home.resolve("logs").resolve("error.log");
        if (Files.isReadable(log)) {
            return new Location(log, slingHome, "from sling.home");
        }
        return new Location(log, slingHome, log + " is not readable from this machine; set AEM_LOG_DIR");
    }
}
