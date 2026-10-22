package dev.aemdebug.jdi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/** Bounded in-memory store of snapshots (oldest are dropped). */
public final class SnapshotStore {

    private final int capacity;
    private final Deque<Snapshot> snapshots = new ArrayDeque<>();
    private int counter;
    private int dropped;

    public SnapshotStore(int capacity) {
        this.capacity = capacity;
    }

    public synchronized Snapshot add(Snapshot snapshot) {
        snapshot.id = "s" + (++counter);
        snapshots.addLast(snapshot);
        while (snapshots.size() > capacity) {
            snapshots.removeFirst();
            dropped++;
        }
        return snapshot;
    }

    public synchronized List<Snapshot> list(Predicate<Snapshot> filter) {
        List<Snapshot> result = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            if (filter == null || filter.test(snapshot)) {
                result.add(snapshot);
            }
        }
        return result;
    }

    public synchronized List<Snapshot> since(long time) {
        return list(s -> s.time >= time);
    }

    public synchronized Snapshot get(String id) {
        for (Snapshot snapshot : snapshots) {
            if (snapshot.id.equals(id)) {
                return snapshot;
            }
        }
        return null;
    }

    public synchronized int size() {
        return snapshots.size();
    }

    public synchronized int dropped() {
        return dropped;
    }

    public synchronized int clear() {
        int count = snapshots.size();
        snapshots.clear();
        return count;
    }
}
