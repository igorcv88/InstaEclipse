package ps.reso.instaeclipse.library.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Transactional catalog migrations. Future catalog versions are refused. */
public final class LibrarySchema {
    public static final int CURRENT_VERSION = 2;

    static final String[] TABLES = { "accounts", "blobs", "items", "meta", "direct_items", "direct_threads" };

    /** One forward migration step from {@code version - 1} to {@code version}. */
    public interface Step {
        int version();

        void apply(Sql sql);
    }

    private final List<Step> steps;
    private final int targetVersion;

    public LibrarySchema() {
        this(defaultSteps(), CURRENT_VERSION);
    }

    /** Package-visible for tests that simulate future versions and failing steps. */
    LibrarySchema(List<Step> steps, int targetVersion) {
        List<Step> sorted = new ArrayList<>(steps);
        sorted.sort((a, b) -> Integer.compare(a.version(), b.version()));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).version() != i + 1) throw new IllegalArgumentException("Migration steps must be contiguous");
        }
        if (targetVersion > sorted.size()) throw new IllegalArgumentException("No step reaches the target version");
        this.steps = Collections.unmodifiableList(sorted);
        this.targetVersion = targetVersion;
    }

    public static List<Step> defaultSteps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public void apply(Sql sql) {
                createVersion1(sql);
            }
        });
        steps.add(new Step() {
            @Override
            public int version() {
                return 2;
            }

            @Override
            public void apply(Sql sql) {
                createVersion2(sql);
            }
        });
        return steps;
    }

    /** Brings the catalog to the target version, or throws without changing it. */
    public void migrate(Sql sql) throws LibraryException {
        int current = sql.userVersion();
        if (current > targetVersion) {
            throw new LibraryException(LibraryException.Code.SCHEMA_TOO_NEW,
                    "The media library was created by a newer version of the module");
        }
        for (int version = current + 1; version <= targetVersion; version++) {
            Step step = steps.get(version - 1);
            try {
                sql.inTransaction(() -> {
                    requireIntegrity(sql);
                    long[] before = counts(sql);
                    step.apply(sql);
                    long[] after = counts(sql);
                    for (int i = 0; i < before.length; i++) {
                        if (after[i] < before[i]) {
                            throw new IllegalStateException("Migration would drop rows from " + TABLES[i]);
                        }
                    }
                    requireIntegrity(sql);
                    sql.setUserVersion(step.version());
                    return null;
                });
            } catch (RuntimeException failure) {
                throw new LibraryException(LibraryException.Code.IO,
                        "Media library migration to version " + version + " failed; nothing was changed", failure);
            }
        }
    }

    private static void requireIntegrity(Sql sql) {
        List<Map<String, Object>> rows = sql.query("PRAGMA integrity_check");
        if (rows.isEmpty()) throw new IllegalStateException("Integrity check returned nothing");
        Object value = rows.get(0).values().iterator().next();
        if (!"ok".equalsIgnoreCase(String.valueOf(value))) {
            throw new IllegalStateException("Media library database failed its integrity check");
        }
    }

    private static long[] counts(Sql sql) {
        long[] result = new long[TABLES.length];
        for (int i = 0; i < TABLES.length; i++) {
            if (sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", TABLES[i]).isEmpty()) continue;
            result[i] = Sql.Rows.getLong(sql.query("SELECT COUNT(*) AS n FROM " + TABLES[i]).get(0), "n");
        }
        return result;
    }

    static void createVersion1(Sql sql) {
        sql.exec("CREATE TABLE IF NOT EXISTS accounts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "package_name TEXT NOT NULL, "
                + "account_key TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "UNIQUE(package_name, account_key))");
        sql.exec("CREATE TABLE IF NOT EXISTS blobs ("
                + "sha256 TEXT PRIMARY KEY NOT NULL CHECK(length(sha256) = 64), "
                + "size_bytes INTEGER NOT NULL CHECK(size_bytes > 0), "
                + "mime_type TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "verified_at INTEGER NOT NULL, "
                + "state TEXT NOT NULL DEFAULT 'ok' CHECK(state IN ('ok','missing','corrupt')))");
        sql.exec("CREATE TABLE IF NOT EXISTS items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "uuid TEXT NOT NULL UNIQUE, "
                + "account_id INTEGER NOT NULL REFERENCES accounts(id), "
                + "thread_key TEXT NOT NULL, "
                + "message_key TEXT, "
                + "from_me INTEGER NOT NULL, "
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('image','video')), "
                + "mime_type TEXT NOT NULL, "
                + "sha256 TEXT NOT NULL REFERENCES blobs(sha256), "
                + "message_timestamp INTEGER NOT NULL DEFAULT 0, "
                + "imported_at INTEGER NOT NULL, "
                + "origin TEXT NOT NULL, "
                + "caption TEXT, "
                + "trashed_at INTEGER NOT NULL DEFAULT 0)");
        sql.exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_items_message ON items"
                + "(account_id, thread_key, message_key, from_me, sha256) WHERE message_key IS NOT NULL");
        sql.exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_items_unlinked ON items"
                + "(account_id, thread_key, sha256) WHERE message_key IS NULL");
        sql.exec("CREATE INDEX IF NOT EXISTS idx_items_chat ON items(account_id, thread_key, message_timestamp)");
        sql.exec("CREATE INDEX IF NOT EXISTS idx_items_sha ON items(sha256)");
        sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
    }

    /**
     * Version 2: Direct coverage. {@code direct_items} records every message with media the
     * client showed, with its own settled state; {@code direct_threads} records how far the
     * pages reached. Neither table is ever pruned because a message is absent from a page.
     */
    static void createVersion2(Sql sql) {
        sql.exec("ALTER TABLE items ADD COLUMN view_once INTEGER NOT NULL DEFAULT 0");
        sql.exec("CREATE TABLE IF NOT EXISTS direct_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "account_id INTEGER NOT NULL REFERENCES accounts(id), "
                + "thread_key TEXT NOT NULL, "
                + "message_key TEXT NOT NULL, "
                + "from_me INTEGER NOT NULL, "
                + "media_kind TEXT NOT NULL CHECK(media_kind IN ('image','video','unknown')), "
                + "view_once INTEGER NOT NULL, "
                + "message_timestamp INTEGER NOT NULL DEFAULT 0, "
                + "discovered_at INTEGER NOT NULL, "
                + "last_seen_at INTEGER NOT NULL, "
                + "state TEXT NOT NULL CHECK(state IN ('pending','imported','already_present','unavailable',"
                + "'refused','ambiguous','failed')), "
                + "attempts INTEGER NOT NULL DEFAULT 0, "
                + "last_error TEXT, "
                + "item_uuid TEXT, "
                + "settled_at INTEGER NOT NULL DEFAULT 0, "
                + "source_url TEXT, "
                + "source_seen_at INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(account_id, thread_key, message_key, from_me))");
        sql.exec("CREATE INDEX IF NOT EXISTS idx_direct_items_state ON direct_items(account_id, state)");
        sql.exec("CREATE TABLE IF NOT EXISTS direct_threads ("
                + "account_id INTEGER NOT NULL REFERENCES accounts(id), "
                + "thread_key TEXT NOT NULL, "
                + "newest_key TEXT, "
                + "newest_ts INTEGER NOT NULL DEFAULT 0, "
                + "oldest_key TEXT, "
                + "oldest_ts INTEGER NOT NULL DEFAULT 0, "
                + "reached_start INTEGER NOT NULL DEFAULT 0, "
                + "pages INTEGER NOT NULL DEFAULT 0, "
                + "updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(account_id, thread_key))");
    }
}
