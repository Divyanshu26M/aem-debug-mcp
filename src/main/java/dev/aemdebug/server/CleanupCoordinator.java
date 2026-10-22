package dev.aemdebug.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.tools.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central place for releasing debug resources: breakpoints and watches, debug toggles (auto-revert), the JDWP
 * connection and browsers. Used by debug_cleanup, the idle timer, the shutdown hook and crash recovery.
 */
public final class CleanupCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupCoordinator.class);

    /** A service that holds debug resources. */
    public interface Participant {
        String name();

        /** Releases everything; returns a one-line summary for the user. */
        String cleanup(ToolContext context, boolean shutdown);

        /** Releases exclusive resources after inactivity; returns a summary or null when nothing was done. */
        default String onIdle() {
            return null;
        }

        /** Repairs state left behind by a previous crashed run. */
        default void recover() {
        }
    }

    private final List<Participant> participants = new CopyOnWriteArrayList<>();

    public void register(Participant participant) {
        participants.add(participant);
    }

    /** Full cleanup in registration order; never throws. */
    public List<String> runAll(ToolContext context, boolean shutdown) {
        List<String> lines = new ArrayList<>();
        for (Participant participant : participants) {
            try {
                String line = participant.cleanup(context, shutdown);
                if (line != null && !line.isBlank()) {
                    lines.add(participant.name() + ": " + line);
                }
            } catch (RuntimeException e) {
                LOG.warn("cleanup of {} failed", participant.name(), e);
                lines.add(participant.name() + ": cleanup failed - " + e.getMessage());
            }
        }
        return lines;
    }

    public void onIdle() {
        for (Participant participant : participants) {
            try {
                String line = participant.onIdle();
                if (line != null) {
                    LOG.info("idle cleanup {}: {}", participant.name(), line);
                }
            } catch (RuntimeException e) {
                LOG.warn("idle cleanup of {} failed", participant.name(), e);
            }
        }
    }

    public void onShutdown() {
        for (String line : runAll(ToolContext.detached(), true)) {
            LOG.info("shutdown cleanup - {}", line);
        }
    }

    public void recoverFromPreviousRun() {
        for (Participant participant : participants) {
            try {
                participant.recover();
            } catch (RuntimeException e) {
                LOG.warn("recovery of {} failed", participant.name(), e);
            }
        }
    }
}
