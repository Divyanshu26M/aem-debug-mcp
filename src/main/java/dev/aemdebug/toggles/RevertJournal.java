package dev.aemdebug.toggles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.aemdebug.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent record of debug toggles applied to AEM so they can be reverted after debug_cleanup, idle timeout,
 * shutdown or a crash (next start). Contains only debug-related OSGi properties, never credentials.
 */
public final class RevertJournal {

    private static final Logger LOG = LoggerFactory.getLogger(RevertJournal.class);

    /** One applied toggle. */
    public static final class Entry {
        public String id;
        public String toggle;
        public String instance;
        public String origin;
        /** PID that was modified, or the PID of the factory configuration that was created. */
        public String pid;
        public String factoryPid;
        /** True when the configuration did not exist before (revert = delete). */
        public boolean created;
        /** Properties to restore (explicitly set values before the change) when created is false. */
        public Map<String, Object> previous = new LinkedHashMap<>();
        /** Keys changed by the toggle. */
        public List<String> changedKeys = new ArrayList<>();
        public Map<String, Object> applied = new LinkedHashMap<>();
        public long appliedAt;
        public boolean reverted;
        public String revertError;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("toggle", toggle);
            map.put("instance", instance);
            map.put("origin", origin);
            map.put("pid", pid);
            map.put("factoryPid", factoryPid);
            map.put("created", created);
            map.put("previous", previous);
            map.put("changedKeys", changedKeys);
            map.put("applied", applied);
            map.put("appliedAt", appliedAt);
            map.put("reverted", reverted);
            map.put("revertError", revertError);
            return map;
        }

        static Entry fromMap(Map<String, Object> map) {
            Entry entry = new Entry();
            entry.id = Json.asString(map.get("id"));
            entry.toggle = Json.asString(map.get("toggle"));
            entry.instance = Json.asString(map.get("instance"));
            entry.origin = Json.asString(map.get("origin"));
            entry.pid = Json.asString(map.get("pid"));
            entry.factoryPid = Json.asString(map.get("factoryPid"));
            entry.created = Json.asBoolean(map.get("created"), false);
            entry.previous = new LinkedHashMap<>(Json.asMap(map.get("previous")));
            for (Object key : Json.asList(map.get("changedKeys"))) {
                entry.changedKeys.add(String.valueOf(key));
            }
            entry.applied = new LinkedHashMap<>(Json.asMap(map.get("applied")));
            entry.appliedAt = Json.asLong(map.get("appliedAt"), 0);
            entry.reverted = Json.asBoolean(map.get("reverted"), false);
            entry.revertError = Json.asString(map.get("revertError"));
            return entry;
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public RevertJournal(Path file) {
        this.file = file;
        load();
    }

    public synchronized List<Entry> active() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.reverted) {
                result.add(entry);
            }
        }
        return result;
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    public synchronized void add(Entry entry) {
        entries.add(entry);
        save();
    }

    public synchronized void update() {
        entries.removeIf(e -> e.reverted && System.currentTimeMillis() - e.appliedAt > 7L * 24 * 3600 * 1000);
        save();
    }

    public synchronized String nextId() {
        int max = 0;
        for (Entry entry : entries) {
            if (entry.id != null && entry.id.startsWith("t")) {
                try {
                    max = Math.max(max, Integer.parseInt(entry.id.substring(1)));
                } catch (NumberFormatException e) {
                    // ignore foreign ids
                }
            }
        }
        return "t" + (max + 1);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Object item : Json.parseArray(text)) {
                entries.add(Entry.fromMap(Json.asMap(item)));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot read toggle journal {}: {}", file, e.toString());
        }
    }

    private void save() {
        List<Object> list = new ArrayList<>();
        for (Entry entry : entries) {
            list.add(entry.toMap());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, Json.pretty(list).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Cannot write toggle journal {}: {}", file, e.toString());
        }
    }
}
