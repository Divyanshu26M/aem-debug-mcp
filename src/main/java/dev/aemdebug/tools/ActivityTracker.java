package dev.aemdebug.tools;

import java.util.concurrent.atomic.AtomicLong;

/** Tracks the last tool activity; used by idle timers (JDWP auto-detach, toggle auto-revert). */
public final class ActivityTracker {

    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

    public void touch() {
        lastActivity.set(System.currentTimeMillis());
    }

    public long lastActivityMillis() {
        return lastActivity.get();
    }

    public long idleMillis() {
        return System.currentTimeMillis() - lastActivity.get();
    }
}
