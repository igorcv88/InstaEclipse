package ps.reso.instaeclipse.library.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Streaming, password-encrypted export and restore of the media library.
 *
 * <p>Plaintext layout inside {@link StreamingAead}: a sequence of records
 * {@code type(1) ‖ jsonLength(4) ‖ json ‖ [raw bytes]}: {@code H} header, {@code C} catalog
 * (accounts and items), one {@code B} per distinct blob followed by exactly its bytes, and
 * {@code E} end. Media bytes are copied through a fixed buffer in both directions; memory use
 * does not depend on library size.</p>
 *
 * <p>Restore is two-phase. Phase 1 authenticates and parses the entire stream, staging every blob
 * on disk and checking its SHA-256, size and detected type against the catalog. Nothing in the
 * catalog or blob store changes during phase 1, so a wrong password, a modified or truncated
 * file, or a full device leaves the library exactly as it was. Phase 2 runs under the library
 * lock: quota is evaluated after deduplication, blobs are moved into place, and all rows are
 * inserted in one transaction. Existing items with the same identity are kept as they are
 * (merge, never overwrite).</p>
 */
public final class LibraryBackup {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_ITEMS = 500_000;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long LOCK_TIMEOUT_MS = 60_000L;

    private final MediaLibrary library;

    public LibraryBackup(MediaLibrary library) {
        this.library = library;
    }

    public static final class ExportReport {
        public int items;
        public int blobs;
        public long bytes;
        /** Items left out because their bytes are missing or corrupt. */
        public int skippedUnavailable;
    }

    public static final class RestoreReport {
        public int itemsRestored;
        public int itemsAlreadyPresent;
        public int blobsCreated;
        public int blobsReused;
    }

    // ---------------------------------------------------------------- export

    public ExportReport export(OutputStream sink, char[] password) throws IOException {
        return export(sink, password, StreamingAead.DEFAULT_ITERATIONS, StreamingAead.DEFAULT_CHUNK);
    }

    ExportReport export(OutputStream sink, char[] password, int iterations, int chunkSize) throws IOException {
        // Snapshot the catalog under the lock, then stream outside it so a long export does not
        // block imports. Blobs are immutable content addresses; one purged meanwhile fails the
        // export explicitly instead of producing an incomplete backup.
        ExportReport report = new ExportReport();
        List<LibraryItem> items = new ArrayList<>();
        LinkedHashMap<String, LibraryItem> blobOwners = new LinkedHashMap<>();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            for (boolean trashed : new boolean[] { false, true }) {
                for (LibraryItem item : library.listAll(trashed)) {
                    if (!item.isAvailable()) {
                        report.skippedUnavailable++;
                        continue;
                    }
                    items.add(item);
                    blobOwners.putIfAbsent(item.sha256, item);
                }
            }
        }
        try (DataOutputStream out = new DataOutputStream(StreamingAead.encrypt(sink, password, iterations, chunkSize))) {
            JSONObject header = new JSONObject();
            header.put("format", FORMAT_VERSION);
            header.put("createdAt", library.now());
            header.put("items", items.size());
            header.put("blobs", blobOwners.size());
            writeRecord(out, 'H', header);
            JSONObject catalog = catalog(items);
            catalog.put("extras", catalogExtras());
            writeRecord(out, 'C', catalog);
            byte[] buffer = new byte[64 * 1024];
            for (LibraryItem owner : blobOwners.values()) {
                JSONObject meta = new JSONObject();
                meta.put("sha256", owner.sha256);
                meta.put("size", owner.sizeBytes);
                writeRecord(out, 'B', meta);
                MessageDigest digest = BlobStore.newDigest();
                long copied = 0;
                try (InputStream input = library.blobs().open(owner.sha256)) {
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        if (copied + read > owner.sizeBytes) throw integrity(owner);
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        copied += read;
                    }
                }
                if (copied != owner.sizeBytes || !owner.sha256.equals(BlobStore.hex(digest.digest()))) throw integrity(owner);
                report.bytes += copied;
            }
            JSONObject end = new JSONObject();
            end.put("blobs", blobOwners.size());
            writeRecord(out, 'E', end);
        } catch (JSONException failure) {
            throw new LibraryException(LibraryException.Code.IO, "Could not write backup catalog", failure);
        }
        report.items = items.size();
        report.blobs = blobOwners.size();
        return report;
    }

    private static LibraryException integrity(LibraryItem item) {
        return new LibraryException(LibraryException.Code.INTEGRITY,
                "Stored media changed during export; run Verify and try again");
    }

    private static JSONObject catalog(List<LibraryItem> items) throws JSONException {
        JSONArray array = new JSONArray();
        for (LibraryItem item : items) {
            JSONObject value = new JSONObject();
            value.put("uuid", item.uuid);
            value.put("package", item.packageName);
            value.put("accountKey", item.accountKey);
            value.put("threadKey", item.threadKey);
            if (item.messageKey != null) value.put("messageKey", item.messageKey);
            value.put("fromMe", item.fromMe);
            value.put("kind", item.mediaKind);
            value.put("mime", item.mimeType);
            value.put("sha256", item.sha256);
            value.put("size", item.sizeBytes);
            value.put("messageTimestamp", item.messageTimestamp);
            value.put("importedAt", item.importedAt);
            value.put("origin", item.origin);
            if (item.caption != null) value.put("caption", item.caption);
            value.put("trashedAt", item.trashedAt);
            if (item.viewOnce) value.put("viewOnce", true);
            array.put(value);
        }
        JSONObject catalog = new JSONObject();
        catalog.put("items", array);
        return catalog;
    }

    private JSONObject catalogExtras() throws JSONException {
        JSONObject extras = new JSONObject();
        extras.put("quotaBytes", library.quotaBytes());
        extras.put("schemaVersion", LibrarySchema.CURRENT_VERSION);
        return extras;
    }

    private static void writeRecord(DataOutputStream out, char type, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) throw invalid("Backup catalog exceeds the format limit");
        out.writeByte(type);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    // ---------------------------------------------------------------- restore

    public RestoreReport restore(InputStream source, char[] password) throws IOException {
        return restore(source, password, 2_000_000);
    }

    RestoreReport restore(InputStream source, char[] password, int maxIterations) throws IOException {
        Map<String, BlobStore.Staged> staged = new HashMap<>();
        try (BlobStore.StageSession session = library.blobs().beginStaging()) {
            List<JSONObject> items;
            JSONObject extras = null;
            try (StreamingAead.DecryptingInputStream decrypted =
                         (StreamingAead.DecryptingInputStream) StreamingAead.decrypt(source, password, maxIterations);
                 DataInputStream in = new DataInputStream(decrypted)) {
                JSONObject header = readRecord(in, 'H');
                if (header.optInt("format", -1) != FORMAT_VERSION) throw invalid("Unsupported backup format");
                JSONObject catalogRecord = readRecord(in, 'C');
                items = validateCatalog(catalogRecord);
                extras = catalogRecord.optJSONObject("extras");
                Map<String, Long> expected = new HashMap<>();
                Map<String, String> mimeTypes = new HashMap<>();
                for (JSONObject item : items) {
                    expected.put(item.getString("sha256"), item.getLong("size"));
                    String priorMime = mimeTypes.put(item.getString("sha256"), item.getString("mime"));
                    if (priorMime != null && !priorMime.equals(item.getString("mime"))) {
                        throw invalid("Inconsistent MIME types in backup");
                    }
                }
                if (header.optInt("items", -1) != items.size()) throw invalid("Backup item count does not match its catalog");
                int declaredBlobs = header.optInt("blobs", -1);
                if (declaredBlobs != expected.size()) throw invalid("Backup blob count does not match its catalog");
                for (int index = 0; index < declaredBlobs; index++) {
                    JSONObject meta = readRecord(in, 'B');
                    String sha = meta.getString("sha256");
                    long size = meta.getLong("size");
                    Long want = expected.get(sha);
                    if (want == null || want != size || staged.containsKey(sha)) throw invalid("Unexpected media in backup");
                    library.blobs().requireFreeSpace(size);
                    BlobStore.Staged blob = library.blobs().stage(new Bounded(in, size), MediaLibrary.MAX_ITEM_BYTES, session);
                    staged.put(sha, blob);
                    if (blob.size != size || !blob.sha256.equals(sha)) throw invalid("Media checksum mismatch in backup");
                    if (!mimeTypes.get(sha).equals(blob.sniffedMime)) {
                        throw invalid("Media type in backup does not match its content");
                    }
                    try {
                        library.requireComplete(blob.file, blob.sniffedMime);
                    } catch (LibraryException incomplete) {
                        throw invalid("Backup contains an incomplete media file", incomplete);
                    }
                }
                JSONObject end = readRecord(in, 'E');
                if (end.optInt("blobs", -1) != declaredBlobs) throw invalid("Backup end marker is inconsistent");
                if (in.read() != -1 || !decrypted.isComplete()) throw invalid("Backup did not end where expected");
            } catch (JSONException failure) {
                throw invalid("Backup catalog is malformed", failure);
            }
            return commit(items, extras, staged);
        } finally {
            for (BlobStore.Staged blob : staged.values()) blob.discard();
        }
    }

    private RestoreReport commit(List<JSONObject> items, JSONObject extras, Map<String, BlobStore.Staged> staged)
            throws IOException {
        RestoreReport report = new RestoreReport();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            long newBytes = 0;
            for (BlobStore.Staged blob : staged.values()) {
                if (!library.blobs().verify(blob.sha256, blob.size)) newBytes += blob.size;
            }
            if (library.usedBytes() + newBytes > library.quotaBytes()) {
                throw new LibraryException(LibraryException.Code.QUOTA_EXCEEDED,
                        "Restoring this backup would exceed the library quota");
            }
            List<String> created = new ArrayList<>();
            Sql sql = library.sql();
            try {
                for (BlobStore.Staged blob : staged.values()) {
                    BlobStore.CommitOutcome outcome = library.blobs().commit(blob);
                    if (outcome == BlobStore.CommitOutcome.CREATED) created.add(blob.sha256);
                    if (outcome == BlobStore.CommitOutcome.REUSED_VERIFIED) report.blobsReused++;
                    else report.blobsCreated++;
                }
                sql.inTransaction(() -> {
                    long now = library.now();
                    for (JSONObject item : items) {
                        try {
                            long accountId = library.ensureAccount(item.getString("package"), item.getString("accountKey"));
                            String sha = item.getString("sha256");
                            library.upsertBlob(sha, item.getLong("size"), item.getString("mime"), now);
                            String thread = item.getString("threadKey");
                            String message = item.optString("messageKey", null);
                            boolean fromMe = item.getBoolean("fromMe");
                            if (library.findCopy(accountId, thread, message, fromMe, sha) != null) {
                                report.itemsAlreadyPresent++;
                                continue;
                            }
                            String uuid = item.getString("uuid");
                            if (library.get(uuid) != null) uuid = UUID.randomUUID().toString();
                            sql.insert("INSERT INTO items (uuid, account_id, thread_key, message_key, from_me, "
                                            + "media_kind, mime_type, sha256, message_timestamp, imported_at, origin, "
                                            + "caption, trashed_at, view_once) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                    uuid, accountId, thread, message, fromMe ? 1 : 0,
                                    item.getString("kind"), item.getString("mime"), sha,
                                    item.getLong("messageTimestamp"), item.getLong("importedAt"),
                                    item.getString("origin"), item.optString("caption", null), item.getLong("trashedAt"),
                                    item.optBoolean("viewOnce") ? 1 : 0);
                            report.itemsRestored++;
                        } catch (JSONException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    library.bumpGeneration();
                    return null;
                });
            } catch (IOException | RuntimeException failure) {
                for (String sha : created) library.collectBlob(sha);
                throw new LibraryException(LibraryException.Code.IO, "Could not commit the restored catalog", failure);
            }
            // The backup's storage limit applies only where the user never chose one here.
            if (extras != null && extras.has("quotaBytes") && !library.hasExplicitQuota()) {
                library.setQuotaBytes(extras.optLong("quotaBytes", MediaLibrary.DEFAULT_QUOTA_BYTES));
            }
        }
        return report;
    }

    private static List<JSONObject> validateCatalog(JSONObject catalog) throws JSONException, LibraryException {
        JSONArray array = catalog.getJSONArray("items");
        if (array.length() > MAX_ITEMS) throw invalid("Too many items in backup");
        List<JSONObject> items = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<String, Long> sizes = new HashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String sha = item.optString("sha256");
            String kind = item.optString("kind");
            long size = item.optLong("size", -1);
            if (!SHA256.matcher(sha).matches() || size <= 0 || size > MediaLibrary.MAX_ITEM_BYTES
                    || !(MediaSniffer.KIND_IMAGE.equals(kind) || MediaSniffer.KIND_VIDEO.equals(kind))
                    || !kind.equals(MediaSniffer.kindOf(item.optString("mime")))) {
                throw invalid("Invalid media item in backup");
            }
            Long previous = sizes.put(sha, size);
            if (previous != null && previous != size) throw invalid("Inconsistent media sizes in backup");
            new ImportRequest(item.optString("package"), item.optString("accountKey"),
                    item.optString("threadKey"), item.optString("messageKey", null),
                    item.optBoolean("fromMe"), item.optLong("messageTimestamp"),
                    item.optString("origin"), item.optString("caption", null),
                    item.optBoolean("viewOnce")).validate();
            if (!item.has("fromMe") || !item.has("messageTimestamp") || !item.has("importedAt")
                    || !item.has("trashedAt") || item.optString("uuid").isEmpty()) {
                throw invalid("Incomplete media item in backup");
            }
            String identity = item.getString("package") + '\n' + item.getString("accountKey") + '\n'
                    + item.getString("threadKey") + '\n' + item.optString("messageKey", "") + '\n' + item.getBoolean("fromMe") + '\n' + sha;
            if (!identities.add(identity)) throw invalid("Duplicate media item in backup");
            items.add(item);
        }
        return items;
    }

    private static JSONObject readRecord(DataInputStream in, char expectedType) throws IOException, JSONException {
        int type;
        int length;
        try {
            type = in.readUnsignedByte();
            length = in.readInt();
        } catch (EOFException truncated) {
            throw invalid("Backup is truncated", truncated);
        }
        if (type != expectedType) throw invalid("Backup records are out of order");
        if (length < 2 || length > MAX_JSON_BYTES) throw invalid("Backup record is too large");
        byte[] bytes = new byte[length];
        try {
            in.readFully(bytes);
        } catch (EOFException truncated) {
            throw invalid("Backup is truncated", truncated);
        }
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static LibraryException invalid(String message) {
        return new LibraryException(LibraryException.Code.BACKUP_INVALID, message);
    }

    private static LibraryException invalid(String message, Throwable cause) {
        return new LibraryException(LibraryException.Code.BACKUP_INVALID, message, cause);
    }

    /** Exposes exactly {@code remaining} bytes of the underlying stream and never closes it. */
    private static final class Bounded extends FilterInputStream {
        private long remaining;

        Bounded(InputStream in, long size) {
            super(in);
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value < 0) throw invalid("Backup is truncated");
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) throw invalid("Backup is truncated");
            remaining -= read;
            return read;
        }

        @Override
        public void close() {
            // The record stream continues after this blob.
        }
    }
}
