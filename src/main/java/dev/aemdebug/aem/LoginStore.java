package dev.aemdebug.aem;

import dev.aemdebug.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AEM login sessions remembered between chats, so each new chat does not ask for a login again. Holds only the
 * {@code login-token} cookie per instance origin (never passwords) in aem-logins.json, readable only by the
 * current OS user. Entries are dropped on logout, when AEM rejects the token, or after a week.
 */
public final class LoginStore {

    private static final Logger LOG = LoggerFactory.getLogger(LoginStore.class);
    public static final String FILE_NAME = "aem-logins.json";
    static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private final Path file;

    private LoginStore(Path file) {
        this.file = file;
    }

    /** A store in the given folder. */
    public static LoginStore in(Path dir) {
        return new LoginStore(dir.resolve(FILE_NAME));
    }

    /** A store that remembers nothing (AEM_DEBUG_REMEMBER_LOGINS=false, tests). */
    public static LoginStore disabled() {
        return new LoginStore(null);
    }

    public boolean enabled() {
        return file != null;
    }

    public Path file() {
        return file;
    }

    /** The remembered token for an instance origin, or null. */
    public String token(String origin) {
        if (file == null) {
            return null;
        }
        Map<String, Object> entry = Json.asMap(read().get(origin));
        return Json.asString(entry.get("token"));
    }

    public void save(String origin, String user, String token) {
        if (file == null || token == null || token.isEmpty()) {
            return;
        }
        update(logins -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user", user);
            entry.put("token", token);
            entry.put("savedAt", System.currentTimeMillis());
            logins.put(origin, entry);
        });
    }

    public void remove(String origin) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        update(logins -> logins.remove(origin));
    }

    /**
     * Removes the entry only while it still holds {@code token}: a token AEM rejected in one chat must not delete a
     * newer login that another chat saved meanwhile.
     */
    public void removeIfToken(String origin, String token) {
        if (file == null || token == null || !Files.exists(file)) {
            return;
        }
        update(logins -> {
            if (token.equals(Json.asString(Json.asMap(logins.get(origin)).get("token")))) {
                logins.remove(origin);
            }
        });
    }

    private interface Change {
        void apply(Map<String, Object> logins);
    }

    private synchronized void update(Change change) {
        try {
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Map<String, Object> logins = read();
                change.apply(logins);
                write(logins);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot update {}: {}", file, e.toString());
        }
    }

    private Map<String, Object> read() {
        Map<String, Object> logins = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return logins;
        }
        try {
            Map<String, Object> content = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Object> entry : Json.asMap(content.get("logins")).entrySet()) {
                long savedAt = Json.asLong(Json.asMap(entry.getValue()).get("savedAt"), 0);
                if (now - savedAt < MAX_AGE_MS) {
                    logins.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cannot read {}: {}", file, e.toString());
        }
        return logins;
    }

    private void write(Map<String, Object> logins) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("description", "AEM login sessions of aem-debug-mcp (login-token cookies only, no passwords). "
                + "Readable only by this user; aem_auth action=logout removes an entry.");
        content.put("logins", logins);
        Path temp = Files.createTempFile(file.getParent(), "aem-logins", ".tmp");
        try {
            restrictToOwner(temp);
            Files.write(temp, Json.pretty(content).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Makes a file readable and writable only by its owner (POSIX rw------- or a single-entry Windows ACL). */
    static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        UserPrincipal owner = acl.getOwner();
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
    }
}
