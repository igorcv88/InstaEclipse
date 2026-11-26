package ps.reso.instaeclipse.library.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The persistent private media library: catalog + content-addressed blobs.
 *
 * <p>Consistency model. Bytes are always made durable before the catalog row that refers to
 * them (stage → sync → atomic rename → transaction). The only states an interruption can leave
 * are therefore (a) a staging file, (b) a blob without a row, or (c) a row whose blob was later
 * lost or damaged outside the module. {@link #reconcile} removes (a) and (b) and marks (c) as
 * {@code missing}/{@code corrupt} without deleting the row, so the user still sees the item and
 * a re-import or backup restore repairs it in place.</p>
 *
 * <p>Every mutation holds {@link LibraryLock}; reads use SQLite's own isolation.</p>
 */
public final class MediaLibrary {
    public static final long GIB = 1024L * 1024L * 1024L;
    /** Default quota: large enough for a real library, bounded by the device reserve anyway. */
    public static final long DEFAULT_QUOTA_BYTES = 16L * GIB;
    public static final long MIN_QUOTA_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_QUOTA_BYTES = 1024L * GIB;
    /** Per-item limit for user-supplied media. */
    public static final long MAX_ITEM_BYTES = 4L * GIB;
    /** How long a trashed item stays recoverable before {@link #purgeExpiredTrash} may erase it. */
    public static final long TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private static final long LOCK_TIMEOUT_MS = 30_000L;
    private static final String META_QUOTA = "quota_bytes";
    private static final String META_GENERATION = "generation";

    private static final String ITEM_COLUMNS = "i.id, i.uuid, i.account_id, a.package_name, a.account_key, i.thread_key, "
            + "i.message_key, i.from_me, i.media_kind, i.mime_type, i.sha256, "
            + "b.size_bytes, i.message_timestamp, i.imported_at, i.origin, i.caption, i.trashed_at, b.state";
    private static final String ITEM_FROM = " FROM items i JOIN accounts a ON a.id = i.account_id "
            + "JOIN blobs b ON b.sha256 = i.sha256 ";

    private final Sql sql;
    private final BlobStore blobs;
    private final LibraryLock lock;
    private final LongSupplier clock;
    private volatile DecodeProbe decodeProbe;

    /**
     * Device-side decode check (images: sampled decode; videos: duration and a video track).
     * Returns {@code null} when the file decodes, otherwise a reason. Optional: the structural
     * {@link MediaValidator} always runs.
     */
    public interface DecodeProbe {
        String check(File file, String mimeType);
    }

    public void setDecodeProbe(DecodeProbe probe) {
        this.decodeProbe = probe;
    }

    /** Structural validation plus the optional decode probe; throws INCOMPLETE_MEDIA. */
    void requireComplete(File file, String mime) throws IOException {
        String invalid = MediaValidator.validate(file, mime);
        DecodeProbe probe = decodeProbe;
        if (invalid == null && probe != null) {
            try {
                invalid = probe.check(file, mime);
            } catch (RuntimeException failure) {
                invalid = "decode_failed";
            }
        }
        if (invalid != null) {
            throw new LibraryException(LibraryException.Code.INCOMPLETE_MEDIA,
                    "The media is incomplete or cannot be decoded (" + invalid + ")");
        }
    }

    public MediaLibrary(Sql sql, BlobStore blobs, LibraryLock lock, LongSupplier clock) throws LibraryException {
        this.sql = sql;
        this.blobs = blobs;
        this.lock = lock;
        this.clock = clock;
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            new LibrarySchema().migrate(sql);
        } catch (LibraryException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new LibraryException(LibraryException.Code.IO, "Could not lock the catalog", failure);
        }
    }

    public BlobStore blobs() {
        return blobs;
    }

    LibraryLock lock() {
        return lock;
    }

    Sql sql() {
        return sql;
    }

    // ---------------------------------------------------------------- import

    public enum ImportStatus {
        /** New item with new bytes. */
        CREATED,
        /** New item whose bytes were already stored for another message (no extra space). */
        DEDUPLICATED,
        /** The same identity and content are already in the catalog. */
        ALREADY_PRESENT,
        /** The item existed but its bytes were missing or corrupt; they were restored. */
        REPAIRED
    }

    public static final class ImportResult {
        public final ImportStatus status;
        public final LibraryItem item;

        ImportResult(ImportStatus status, LibraryItem item) {
            this.status = status;
            this.item = item;
        }
    }

    @FunctionalInterface
    public interface StreamSource {
        InputStream open() throws IOException;
    }

    /** Only called from the explicit import flow. Hash the bytes before deciding idempotency. */
    public ImportResult importMedia(ImportRequest request, StreamSource source) throws IOException {
        request.validate();
        BlobStore.Staged staged = null;
        try {
            try (InputStream input = source.open()) {
                staged = blobs.stage(input, MAX_ITEM_BYTES);
            }
            String kind = MediaSniffer.kindOf(staged.sniffedMime);
            if (kind == null) {
                throw new LibraryException(LibraryException.Code.UNSUPPORTED_MEDIA, "Only photos and videos can be kept");
            }
            requireComplete(staged.file, staged.sniffedMime);
            try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
                long accountId = ensureAccount(request.packageName, request.accountKey);
                LibraryItem existing = findCopy(accountId, request.threadKey, request.messageKey,
                        request.fromMe, staged.sha256);
                if (existing != null && existing.isAvailable()
                        && blobs.verify(existing.sha256, existing.sizeBytes)) {
                    return new ImportResult(ImportStatus.ALREADY_PRESENT, existing);
                }
                return storeStaged(accountId, request, request.origin, staged, existing);
            }
        } finally {
            if (staged != null) staged.discard();
        }
    }

    private ImportResult storeStaged(long accountId, ImportRequest request, String origin, BlobStore.Staged staged,
                                     LibraryItem existing) throws IOException {
        String mime = staged.sniffedMime;
        String kind = MediaSniffer.kindOf(mime);
        // Quota is evaluated after deduplication: identical, verified bytes cost nothing.
        boolean alreadyStored = blobs.verify(staged.sha256, staged.size);
        long accounted = Sql.Rows.getLong(sql.query("SELECT COALESCE(SUM(size_bytes),0) AS n FROM blobs "
                + "WHERE sha256=? AND state!='missing'", staged.sha256).get(0), "n");
        if (!alreadyStored && usedBytes() - accounted + staged.size > quotaBytes()) {
            throw new LibraryException(LibraryException.Code.QUOTA_EXCEEDED, "The media library is full");
        }
        BlobStore.CommitOutcome outcome = blobs.commit(staged);
        long now = clock.getAsLong();
        String uuid;
        ImportStatus status;
        try {
            uuid = sql.inTransaction(() -> {
                upsertBlob(staged.sha256, staged.size, mime, now);
                bumpGeneration();
                if (existing != null) {
                    sql.update("UPDATE items SET sha256 = ?, mime_type = ?, media_kind = ? WHERE id = ?",
                            staged.sha256, mime, kind, existing.id);
                    return existing.uuid;
                }
                String id = UUID.randomUUID().toString();
                sql.insert("INSERT INTO items (uuid, account_id, thread_key, message_key, from_me, "
                                + "media_kind, mime_type, sha256, message_timestamp, imported_at, origin, caption) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, accountId, request.threadKey, request.messageKey, request.fromMe ? 1 : 0,
                        kind, mime, staged.sha256, request.messageTimestamp, now, origin, request.caption);
                return id;
            });
        } catch (RuntimeException failure) {
            // Bytes without a row are invisible; drop them unless another item owns them.
            if (outcome == BlobStore.CommitOutcome.CREATED && !isBlobReferenced(staged.sha256)) {
                deleteBlobQuietly(staged.sha256);
            }
            throw new LibraryException(LibraryException.Code.IO, "Could not record the media item", failure);
        }
        if (existing != null) {
            status = ImportStatus.REPAIRED;
            if (!existing.sha256.equals(staged.sha256)) collectBlob(existing.sha256);
        } else {
            status = outcome == BlobStore.CommitOutcome.REUSED_VERIFIED ? ImportStatus.DEDUPLICATED : ImportStatus.CREATED;
        }
        return new ImportResult(status, get(uuid));
    }

    // ---------------------------------------------------------------- queries

    public LibraryItem get(String uuid) {
        if (uuid == null) return null;
        List<Map<String, Object>> rows = sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE i.uuid = ?", uuid);
        return rows.isEmpty() ? null : new LibraryItem(rows.get(0));
    }

    /** Returns a single candidate only when the full identity is unambiguous. */
    public LibraryItem lookup(String packageName, String accountKey, String threadKey,
                              String messageKey, boolean fromMe) {
        Long account = findAccount(packageName, accountKey);
        if (account == null || messageKey == null || threadKey == null || threadKey.isEmpty()) return null;
        List<Map<String, Object>> rows = sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM
                        + "WHERE i.account_id=? AND i.thread_key=? AND i.message_key=? AND i.from_me=? "
                        + "AND i.trashed_at=0 LIMIT 2", account, threadKey, messageKey, fromMe ? 1 : 0);
        return rows.size() == 1 ? new LibraryItem(rows.get(0)) : null;
    }

    public List<LibraryItem> listChat(String packageName, String accountKey, String threadKey) {
        List<LibraryItem> result = new ArrayList<>();
        if (threadKey == null || threadKey.isEmpty()) return result;
        for (Map<String, Object> row : sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM
                        + "WHERE a.package_name=? AND a.account_key=? AND i.thread_key=? AND i.trashed_at=0 "
                        + "ORDER BY i.message_timestamp DESC, i.id DESC", packageName, accountKey, threadKey)) {
            result.add(new LibraryItem(row));
        }
        return result;
    }

    /** User-selected association to a conversation. The message identity remains unknown. */
    public boolean associate(String uuid, String packageName, String accountKey, String threadKey) throws IOException {
        ImportRequest.forConversation(packageName, accountKey, threadKey).validate();
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            LibraryItem item = get(uuid);
            if (item == null || item.isTrashed() || !item.threadKey.isEmpty()) return false;
            return sql.inTransaction(() -> {
                long account = ensureAccount(packageName, accountKey);
                if (findCopy(account, threadKey, null, false, item.sha256) != null) return false;
                sql.update("UPDATE items SET account_id=?, thread_key=? WHERE id=?", account, threadKey, item.id);
                bumpGeneration();
                return true;
            });
        }
    }

    /** Every item (all accounts), for the module's own library screen. */
    public List<LibraryItem> listAll(boolean trashed) {
        List<LibraryItem> result = new ArrayList<>();
        for (Map<String, Object> row : sql.query("SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE "
                + (trashed ? "i.trashed_at > 0" : "i.trashed_at = 0")
                + " ORDER BY i.message_timestamp DESC, i.id DESC")) {
            result.add(new LibraryItem(row));
        }
        return result;
    }

    public File fileOf(LibraryItem item) throws IOException {
        return blobs.path(item.sha256);
    }

    /** Bytes used by distinct stored blobs (deduplicated). */
    public long usedBytes() {
        return Sql.Rows.getLong(sql.query("SELECT COALESCE(SUM(size_bytes), 0) AS n FROM blobs WHERE state != 'missing'")
                .get(0), "n");
    }

    public long quotaBytes() {
        List<Map<String, Object>> rows = sql.query("SELECT value FROM meta WHERE key = ?", META_QUOTA);
        if (rows.isEmpty()) return DEFAULT_QUOTA_BYTES;
        try {
            return clampQuota(Long.parseLong(Sql.Rows.getString(rows.get(0), "value")));
        } catch (NumberFormatException invalid) {
            return DEFAULT_QUOTA_BYTES;
        }
    }

    public void setQuotaBytes(long bytes) throws IOException {
        long value = clampQuota(bytes);
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            // Plain statements rather than UPSERT: Android 9 ships SQLite 3.22, which predates it.
            sql.inTransaction(() -> {
                if (sql.update("UPDATE meta SET value = ? WHERE key = ?", String.valueOf(value), META_QUOTA) == 0) {
                    sql.insert("INSERT INTO meta (key, value) VALUES (?, ?)", META_QUOTA, String.valueOf(value));
                }
                return null;
            });
        }
    }

    public boolean hasExplicitQuota() {
        return !sql.query("SELECT 1 FROM meta WHERE key = ?", META_QUOTA).isEmpty();
    }

    public static long clampQuota(long bytes) {
        return Math.max(MIN_QUOTA_BYTES, Math.min(MAX_QUOTA_BYTES, bytes));
    }

    // ---------------------------------------------------------------- change tracking

    /**
     * A counter that changes whenever anything a viewer could have cached changes: an item
     * added, repaired, trashed, restored or purged, or a file found missing/damaged/recovered.
     * Persisted, so it keeps increasing across process restarts. Clients cache lookups and
     * thumbnails per generation and drop them as soon as it differs.
     */
    public long generation() {
        List<Map<String, Object>> rows = sql.query("SELECT value FROM meta WHERE key = ?", META_GENERATION);
        if (rows.isEmpty()) return 0L;
        try {
            return Long.parseLong(Sql.Rows.getString(rows.get(0), "value"));
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }

    void bumpGeneration() {
        long next = generation() + 1;
        if (sql.update("UPDATE meta SET value = ? WHERE key = ?", String.valueOf(next), META_GENERATION) == 0) {
            sql.insert("INSERT INTO meta (key, value) VALUES (?, ?)", META_GENERATION, String.valueOf(next));
        }
    }

    // ---------------------------------------------------------------- controlled deletion

    /** Moves an item to the trash; its bytes stay until it is purged. */
    public boolean trash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            boolean changed = sql.update("UPDATE items SET trashed_at = ? WHERE uuid = ? AND trashed_at = 0",
                    Math.max(1, clock.getAsLong()), uuid) > 0;
            if (changed) bumpGeneration();
            return changed;
        }
    }

    public boolean restoreFromTrash(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            boolean changed = sql.update("UPDATE items SET trashed_at = 0 WHERE uuid = ? AND trashed_at > 0", uuid) > 0;
            if (changed) bumpGeneration();
            return changed;
        }
    }

    /**
     * Permanently erases a trashed item. Refuses items that are not in the trash, so a single
     * accidental call can never erase visible media. Shared bytes survive while another item
     * still references them.
     */
    public boolean purge(String uuid) throws IOException {
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            LibraryItem item = get(uuid);
            if (item == null || !item.isTrashed()) return false;
            sql.update("DELETE FROM items WHERE id = ?", item.id);
            collectBlob(item.sha256);
            bumpGeneration();
            return true;
        }
    }

    /** Purges items trashed before {@code now - TRASH_RETENTION_MS}. */
    public int purgeExpiredTrash() throws IOException {
        long cutoff = clock.getAsLong() - TRASH_RETENTION_MS;
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            int purged = 0;
            for (Map<String, Object> row : sql.query("SELECT uuid FROM items WHERE trashed_at > 0 AND trashed_at <= ?", cutoff)) {
                if (purge(Sql.Rows.getString(row, "uuid"))) purged++;
            }
            return purged;
        }
    }

    // ---------------------------------------------------------------- reconciliation

    public static final class ReconcileReport {
        public int stagingRemoved;
        /** Files without a catalog record, moved to quarantine (never deleted by maintenance). */
        public int orphanBlobsQuarantined;
        /** Catalog blob records no item uses; the record is dropped and the file quarantined. */
        public int unreferencedBlobsQuarantined;
        public int markedMissing;
        public int markedCorrupt;
        public int recovered;
        public int verified;
    }

    /**
     * Repairs what an interruption or external damage can leave behind.
     *
     * @param deep re-hash every blob (slow); otherwise only presence and size are checked
     */
    public ReconcileReport reconcile(boolean deep) throws IOException {
        ReconcileReport report = new ReconcileReport();
        try (LibraryLock.Held ignored = lock.acquire(LOCK_TIMEOUT_MS)) {
            report.stagingRemoved = blobs.cleanStaging();
            LinkedHashSet<String> known = new LinkedHashSet<>();
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, state FROM blobs")) {
                String sha = Sql.Rows.getString(row, "sha256");
                long size = Sql.Rows.getLong(row, "size_bytes");
                String state = Sql.Rows.getString(row, "state");
                known.add(sha);
                if (!isBlobReferenced(sha)) {
                    sql.update("DELETE FROM blobs WHERE sha256 = ?", sha);
                    blobs.quarantine(sha);
                    report.unreferencedBlobsQuarantined++;
                    continue;
                }
                String actual;
                if (!blobs.path(sha).isFile()) actual = "missing";
                else if (!blobs.existsWithSize(sha, size)) actual = "corrupt";
                else if (deep) actual = blobs.verify(sha, size) ? "ok" : "corrupt";
                else actual = "ok".equals(state) ? "ok" : (blobs.verify(sha, size) ? "ok" : "corrupt");
                if ("ok".equals(actual)) report.verified++;
                if (!actual.equals(state)) {
                    sql.update("UPDATE blobs SET state = ?, verified_at = ? WHERE sha256 = ?", actual, clock.getAsLong(), sha);
                    bumpGeneration();
                    if ("missing".equals(actual)) report.markedMissing++;
                    else if ("corrupt".equals(actual)) report.markedCorrupt++;
                    else report.recovered++;
                }
            }
            for (String sha : blobs.listBlobs()) {
                if (!known.contains(sha)) {
                    blobs.quarantine(sha);
                    report.orphanBlobsQuarantined++;
                }
            }
        }
        return report;
    }

    // ---------------------------------------------------------------- internals (also used by backup)

    long ensureAccount(String packageName, String accountKey) {
        Long existing = findAccount(packageName, accountKey);
        if (existing != null) return existing;
        sql.insert("INSERT OR IGNORE INTO accounts (package_name, account_key, created_at) VALUES (?,?,?)",
                packageName, accountKey, clock.getAsLong());
        Long created = findAccount(packageName, accountKey);
        if (created == null) throw new IllegalStateException("Could not create library account");
        return created;
    }

    Long findAccount(String packageName, String accountKey) {
        if (packageName == null || accountKey == null) return null;
        List<Map<String, Object>> rows = sql.query("SELECT id FROM accounts WHERE package_name = ? AND account_key = ?",
                packageName, accountKey);
        return rows.isEmpty() ? null : Sql.Rows.getLong(rows.get(0), "id");
    }

    LibraryItem findCopy(long account, String thread, String message, boolean fromMe, String sha) {
        String base = "SELECT " + ITEM_COLUMNS + ITEM_FROM + "WHERE i.account_id=? AND i.thread_key=? AND i.sha256=?";
        List<Map<String, Object>> rows = message == null
                ? sql.query(base + " AND i.message_key IS NULL", account, thread, sha)
                : sql.query(base + " AND i.message_key=? AND i.from_me=?", account, thread, sha, message, fromMe ? 1 : 0);
        return rows.isEmpty() ? null : new LibraryItem(rows.get(0));
    }

    void upsertBlob(String sha256, long size, String mime, long now) {
        if (sql.update("UPDATE blobs SET size_bytes = ?, verified_at = ?, state = 'ok' WHERE sha256 = ?",
                size, now, sha256) == 0) {
            sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at, state) "
                    + "VALUES (?,?,?,?,?,'ok')", sha256, size, mime, now, now);
        }
    }

    boolean isBlobReferenced(String sha256) {
        return !sql.query("SELECT 1 FROM items WHERE sha256 = ? LIMIT 1", sha256).isEmpty();
    }

    /** Deletes a blob row and file once no item references it. */
    void collectBlob(String sha256) {
        if (isBlobReferenced(sha256)) return;
        sql.update("DELETE FROM blobs WHERE sha256 = ?", sha256);
        deleteBlobQuietly(sha256);
    }

    private void deleteBlobQuietly(String sha256) {
        try {
            blobs.delete(sha256);
        } catch (IOException ignored) {
            // A later reconcile removes the orphan.
        }
    }

    long now() {
        return clock.getAsLong();
    }
}
