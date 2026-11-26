package ps.reso.instaeclipse.library.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reports catalog and content-store consistency and rechecks stored hashes. */
public final class LibraryIntegrity {
    private static final long LOCK_TIMEOUT_MS = 60_000L;

    private LibraryIntegrity() { }

    // ---------------------------------------------------------------- report

    public static final class Report {
        /** Files in the content store with no catalog record. */
        public final List<String> filesWithoutRecord = new ArrayList<>();
        /** Files kept in quarantine by maintenance (never deleted automatically). */
        public final List<String> quarantined = new ArrayList<>();
        /** Item uuids whose file is missing. */
        public final List<String> recordsWithoutFile = new ArrayList<>();
        /** Item uuids whose file is damaged (size, or SHA-256 when deep). */
        public final List<String> damaged = new ArrayList<>();
        /** Blob records no item uses. */
        public final List<String> unusedRecords = new ArrayList<>();
        public final List<AmbiguousGroup> ambiguous = new ArrayList<>();
        public int unknownOrigin;
        public int unlinked;
        public int stagingFiles;
        public int items;
        public int blobs;

        public boolean clean() {
            return filesWithoutRecord.isEmpty() && recordsWithoutFile.isEmpty() && damaged.isEmpty()
                    && unusedRecords.isEmpty() && ambiguous.isEmpty() && stagingFiles == 0;
        }
    }

    public static Report inspect(MediaLibrary library, boolean deep) throws IOException {
        Report report = new Report();
        Sql sql = library.sql();
        BlobStore blobs = library.blobs();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            Map<String, Long> sizes = new HashMap<>();
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes FROM blobs")) {
                sizes.put(Sql.Rows.getString(row, "sha256"), Sql.Rows.getLong(row, "size_bytes"));
            }
            report.blobs = sizes.size();
            Set<String> referenced = new LinkedHashSet<>();
            for (boolean trashed : new boolean[] { false, true }) {
                for (LibraryItem item : library.listAll(trashed)) {
                    report.items++;
                    referenced.add(item.sha256);
                    File file = blobs.path(item.sha256);
                    if (!file.isFile()) report.recordsWithoutFile.add(item.uuid);
                    else if (file.length() != item.sizeBytes
                            || (deep && !blobs.verify(item.sha256, item.sizeBytes))) report.damaged.add(item.uuid);
                    if ("unknown".equals(item.origin)) report.unknownOrigin++;
                    if (item.messageKey == null) report.unlinked++;
                }
            }
            for (String sha : sizes.keySet()) if (!referenced.contains(sha)) report.unusedRecords.add(sha);
            for (String sha : blobs.listBlobs()) if (!sizes.containsKey(sha)) report.filesWithoutRecord.add(sha);
            report.quarantined.addAll(blobs.listQuarantine());
            File[] staging = new File(blobs.root(), "staging").listFiles();
            report.stagingFiles = staging == null ? 0 : staging.length;
            report.ambiguous.addAll(findAmbiguities(library));
        }
        return report;
    }

    // ---------------------------------------------------------------- ambiguity

    public static final class AmbiguousGroup {
        public final long accountId;
        public final String messageKey;
        public final boolean fromMe;
        public final List<LibraryItem> items;

        AmbiguousGroup(long accountId, String messageKey, boolean fromMe, List<LibraryItem> items) {
            this.accountId = accountId;
            this.messageKey = messageKey;
            this.fromMe = fromMe;
            this.items = items;
        }

        /** Every item holds the same bytes: resolving loses nothing. */
        public boolean identicalContent() {
            for (LibraryItem item : items) if (!item.sha256.equals(items.get(0).sha256)) return false;
            return true;
        }
    }

    /** Enumerates visible copies with conflicting bytes for the same message. */
    public static List<AmbiguousGroup> findAmbiguities(MediaLibrary library) {
        Map<List<Object>, List<LibraryItem>> groups = new LinkedHashMap<>();
        for (LibraryItem item : library.listAll(false)) {
            if (item.messageKey == null) continue;
            List<Object> key = java.util.Arrays.asList(item.accountId, item.threadKey, item.messageKey, item.fromMe);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        List<AmbiguousGroup> result = new ArrayList<>();
        for (List<LibraryItem> items : groups.values()) {
            LibraryItem first = items.get(0);
            if (items.size() > 1) result.add(new AmbiguousGroup(first.accountId, first.messageKey, first.fromMe, items));
        }
        return result;
    }

    // ---------------------------------------------------------------- periodic SHA-256

    public static final class VerifyReport {
        public int checked;
        public long bytes;
        public int ok;
        public int damaged;
        public int missing;
        public int recovered;
    }

    /**
     * Re-hashes stored files, least recently verified first, until {@code maxBytes} were read
     * (at least one file). Updates each record's state and verification time.
     */
    public static VerifyReport verifyBatch(MediaLibrary library, long maxBytes) throws IOException {
        VerifyReport report = new VerifyReport();
        Sql sql = library.sql();
        BlobStore blobs = library.blobs();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, state FROM blobs "
                    + "ORDER BY verified_at ASC, sha256 ASC")) {
                if (report.checked > 0 && report.bytes >= maxBytes) break;
                String sha = Sql.Rows.getString(row, "sha256");
                long size = Sql.Rows.getLong(row, "size_bytes");
                String before = Sql.Rows.getString(row, "state");
                String state;
                if (!blobs.path(sha).isFile()) {
                    state = "missing";
                    report.missing++;
                } else if (blobs.verify(sha, size)) {
                    state = "ok";
                    report.ok++;
                    report.bytes += size;
                } else {
                    state = "corrupt";
                    report.damaged++;
                    report.bytes += size;
                }
                report.checked++;
                if (!"ok".equals(before) && "ok".equals(state)) report.recovered++;
                sql.update("UPDATE blobs SET state = ?, verified_at = ? WHERE sha256 = ?", state, library.now(), sha);
                if (!state.equals(before)) library.bumpGeneration();
            }
        }
        return report;
    }

    // ---------------------------------------------------------------- canonical dump (backup tests)

    /** Order-independent text dump of the catalog's meaningful content (no row ids or counters). */
    public static String canonicalDump(MediaLibrary library) {
        Sql sql = library.sql();
        StringBuilder out = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : sql.query("SELECT a.package_name, a.account_key, i.uuid, i.thread_key, "
                + "i.message_key, i.from_me, i.media_kind, i.mime_type, i.sha256, i.message_timestamp, "
                + "i.imported_at, i.origin, i.caption, i.trashed_at  "
                + "FROM items i JOIN accounts a ON a.id = i.account_id")) {
            lines.add("item " + new LinkedHashMap<>(row));
        }
        for (Map<String, Object> row : sql.query("SELECT sha256, size_bytes, mime_type FROM blobs")) {
            lines.add("blob " + new LinkedHashMap<>(row));
        }
        lines.add("quota " + library.quotaBytes());
        java.util.Collections.sort(lines);
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }
}
