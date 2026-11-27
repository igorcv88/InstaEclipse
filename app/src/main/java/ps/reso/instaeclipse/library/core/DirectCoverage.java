package ps.reso.instaeclipse.library.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-thread record of what the Instagram client showed and what became of each item.
 *
 * <p>Instagram Direct has no complete local history: pages arrive from the server as the user
 * scrolls. Each page the client deserializes is a discovery event. A discovered item keeps its
 * own state ({@link State}) and is identified only by (account, thread, message id, direction),
 * never by page position or timestamp. Timestamps order the thread's coverage edges; they never
 * identify an item.</p>
 *
 * <p>Invariants (each one has a test):</p>
 * <ul>
 *   <li>Seeing a page again, in any order or direction, never changes a settled state.</li>
 *   <li>A message missing from a page, the session or the UI never changes a row here or in the
 *       catalog. Absence is not deletion. Only the user's own trash removes copies.</li>
 *   <li>A transient failure never counts as processed.</li>
 *   <li>A thread is never reported complete unless the client reached the thread's start and no
 *       item of it is pending or failed.</li>
 * </ul>
 */
public final class DirectCoverage {
    public static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_TIMEOUT_MS = 30_000L;

    public enum State {
        /** Discovered; no bytes were offered yet. */
        PENDING,
        IMPORTED,
        ALREADY_PRESENT,
        /** The client holds no bytes for it now. Settled for reporting; retried if bytes appear. */
        UNAVAILABLE,
        /** Bytes were refused (incomplete, unsupported, too large). Final. */
        REFUSED,
        /** More than one copy claims this identity. Final until the user resolves it. */
        AMBIGUOUS,
        /** Transient failure; retried, never counted as processed. */
        FAILED;

        String column() { return name().toLowerCase(java.util.Locale.ROOT); }

        static State of(String column) { return valueOf(column.toUpperCase(java.util.Locale.ROOT)); }

        boolean isFinal() { return this == IMPORTED || this == ALREADY_PRESENT || this == REFUSED || this == AMBIGUOUS; }
    }

    /** One message item with media, as read from the client's own model. */
    public static final class Discovered {
        public final String messageKey;
        public final boolean fromMe;
        public final String mediaKind;
        public final boolean viewOnce;
        public final long timestamp;

        public Discovered(String messageKey, boolean fromMe, String mediaKind, boolean viewOnce, long timestamp) {
            if (!ImportRequest.validKey(messageKey)) throw new IllegalArgumentException("Invalid message key");
            if (!MediaSniffer.KIND_IMAGE.equals(mediaKind) && !MediaSniffer.KIND_VIDEO.equals(mediaKind)
                    && !"unknown".equals(mediaKind)) throw new IllegalArgumentException("Invalid media kind");
            this.messageKey = messageKey;
            this.fromMe = fromMe;
            this.mediaKind = mediaKind;
            this.viewOnce = viewOnce;
            this.timestamp = Math.max(0, timestamp);
        }
    }

    /** What the client said about the page edges. {@code reachedStart} only when it said there is nothing older. */
    public static final class Page {
        public final List<Discovered> items;
        public final boolean reachedStart;

        public Page(List<Discovered> items, boolean reachedStart) {
            this.items = items;
            this.reachedStart = reachedStart;
        }
    }

    public static final class ThreadReport {
        public final String threadKey;
        public int discovered, pending, imported, alreadyPresent, unavailable, refused, ambiguous, failed, viewOnce;
        /**
         * Current availability of the copies behind IMPORTED and ALREADY_PRESENT. The state records
         * what happened; these say what is on disk now. A copy damaged or lost outside the trash is
         * queued for repair from the same message; one the user trashed is their decision.
         */
        public int keptAvailable, keptDamaged, keptTrashed;
        public boolean reachedStart;
        public long oldestTimestamp, newestTimestamp;
        public int pages;

        ThreadReport(String threadKey) { this.threadKey = threadKey; }

        /** Items whose outcome is known now (bytes kept, refused, ambiguous, or none reachable). */
        public int processed() { return imported + alreadyPresent + unavailable + refused + ambiguous; }

        /** Complete only when the client reached the thread's start and nothing waits for a retry. */
        public boolean complete() {
            return reachedStart && pending == 0 && failed == 0 && unavailable == 0 && keptDamaged == 0;
        }
    }

    public static final class Report {
        public final List<ThreadReport> threads = new ArrayList<>();
        public int discovered, processed, pending, failed, unavailable, threadsComplete;

        /** Never true while any thread's start was not reached: available history is unknown there. */
        public boolean complete() { return !threads.isEmpty() && threadsComplete == threads.size(); }
    }

    private final MediaLibrary library;

    public DirectCoverage(MediaLibrary library) {
        this.library = library;
    }

    /** Records a page. Returns how many items were new to the record. Idempotent. */
    public int discover(String packageName, String accountKey, String threadKey, Page page) throws IOException {
        ImportRequest.forConversation(packageName, accountKey, threadKey).validate();
        Sql sql = library.sql();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            return sql.inTransaction(() -> {
                long account = library.ensureAccount(packageName, accountKey);
                long now = library.now();
                int added = 0;
                Discovered newest = null, oldest = null;
                for (Discovered item : page.items) {
                    if (newest == null || item.timestamp > newest.timestamp) newest = item;
                    if (oldest == null || item.timestamp < oldest.timestamp) oldest = item;
                    int touched = sql.update("UPDATE direct_items SET last_seen_at=?, "
                                    + "media_kind=CASE WHEN media_kind='unknown' THEN ? ELSE media_kind END, "
                                    + "view_once=MAX(view_once, ?), "
                                    + "message_timestamp=CASE WHEN message_timestamp=0 THEN ? ELSE message_timestamp END "
                                    + "WHERE account_id=? AND thread_key=? AND message_key=? AND from_me=?",
                            now, item.mediaKind, item.viewOnce ? 1 : 0, item.timestamp,
                            account, threadKey, item.messageKey, item.fromMe ? 1 : 0);
                    if (touched == 0) {
                        sql.insert("INSERT INTO direct_items (account_id, thread_key, message_key, from_me, "
                                        + "media_kind, view_once, message_timestamp, discovered_at, last_seen_at, state) "
                                        + "VALUES (?,?,?,?,?,?,?,?,?,'pending')",
                                account, threadKey, item.messageKey, item.fromMe ? 1 : 0, item.mediaKind,
                                item.viewOnce ? 1 : 0, item.timestamp, now, now);
                        added++;
                    }
                }
                recordEdges(sql, account, threadKey, newest, oldest, page.reachedStart, now);
                return added;
            });
        }
    }

    private static void recordEdges(Sql sql, long account, String thread, Discovered newest, Discovered oldest,
                                    boolean reachedStart, long now) {
        List<Map<String, Object>> rows = sql.query("SELECT newest_ts, oldest_ts, oldest_key FROM direct_threads "
                + "WHERE account_id=? AND thread_key=?", account, thread);
        if (rows.isEmpty()) {
            sql.insert("INSERT INTO direct_threads (account_id, thread_key, newest_key, newest_ts, oldest_key, "
                            + "oldest_ts, reached_start, pages, updated_at) VALUES (?,?,?,?,?,?,?,1,?)",
                    account, thread, newest == null ? null : newest.messageKey, newest == null ? 0 : newest.timestamp,
                    oldest == null ? null : oldest.messageKey, oldest == null ? 0 : oldest.timestamp,
                    reachedStart ? 1 : 0, now);
            return;
        }
        Map<String, Object> row = rows.get(0);
        long newestTs = Sql.Rows.getLong(row, "newest_ts");
        long oldestTs = Sql.Rows.getLong(row, "oldest_ts");
        boolean noOldest = Sql.Rows.getString(row, "oldest_key") == null;
        if (newest != null && newest.timestamp > newestTs) {
            sql.update("UPDATE direct_threads SET newest_key=?, newest_ts=? WHERE account_id=? AND thread_key=?",
                    newest.messageKey, newest.timestamp, account, thread);
        }
        if (oldest != null && (noOldest || oldest.timestamp < oldestTs)) {
            sql.update("UPDATE direct_threads SET oldest_key=?, oldest_ts=? WHERE account_id=? AND thread_key=?",
                    oldest.messageKey, oldest.timestamp, account, thread);
        }
        // reached_start only ever becomes true: a later page that does not reach it says nothing.
        sql.update("UPDATE direct_threads SET reached_start=MAX(reached_start, ?), pages=pages+1, updated_at=? "
                + "WHERE account_id=? AND thread_key=?", reachedStart ? 1 : 0, now, account, thread);
    }

    /** The state recorded for one identity, or {@code null} when it was never discovered. */
    public State stateOf(String packageName, String accountKey, String threadKey, String messageKey, boolean fromMe) {
        Long account = library.findAccount(packageName, accountKey);
        if (account == null) return null;
        List<Map<String, Object>> rows = library.sql().query("SELECT state FROM direct_items WHERE account_id=? "
                + "AND thread_key=? AND message_key=? AND from_me=?", account, threadKey, messageKey, fromMe ? 1 : 0);
        return rows.isEmpty() ? null : State.of(Sql.Rows.getString(rows.get(0), "state"));
    }

    /**
     * Records an outcome. A final state is never overwritten; FAILED and UNAVAILABLE never replace
     * a better one. An undiscovered identity is recorded first, so a capture without a page event
     * still counts.
     */
    public State settle(String packageName, String accountKey, String threadKey, Discovered item, State outcome,
                        String itemUuid, String error) throws IOException {
        if (outcome == State.PENDING) throw new IllegalArgumentException("PENDING is not an outcome");
        ImportRequest.forConversation(packageName, accountKey, threadKey).validate();
        Sql sql = library.sql();
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            return sql.inTransaction(() -> {
                long account = library.ensureAccount(packageName, accountKey);
                long now = library.now();
                List<Map<String, Object>> rows = sql.query("SELECT state FROM direct_items "
                                + "WHERE account_id=? AND thread_key=? AND message_key=? AND from_me=?",
                        account, threadKey, item.messageKey, item.fromMe ? 1 : 0);
                if (rows.isEmpty()) {
                    sql.insert("INSERT INTO direct_items (account_id, thread_key, message_key, from_me, media_kind, "
                                    + "view_once, message_timestamp, discovered_at, last_seen_at, state) "
                                    + "VALUES (?,?,?,?,?,?,?,?,?,'pending')",
                            account, threadKey, item.messageKey, item.fromMe ? 1 : 0, item.mediaKind,
                            item.viewOnce ? 1 : 0, item.timestamp, now, now);
                    rows = sql.query("SELECT state FROM direct_items WHERE account_id=? AND thread_key=? "
                            + "AND message_key=? AND from_me=?", account, threadKey, item.messageKey, item.fromMe ? 1 : 0);
                }
                State current = State.of(Sql.Rows.getString(rows.get(0), "state"));
                State next = transition(current, outcome);
                if (next == current && current.isFinal()) return current;
                sql.update("UPDATE direct_items SET state=?, attempts=attempts+?, last_error=?, "
                                + "item_uuid=COALESCE(?, item_uuid), settled_at=? "
                                + "WHERE account_id=? AND thread_key=? AND message_key=? AND from_me=?",
                        next.column(), outcome == State.FAILED ? 1 : 0,
                        error, itemUuid,
                        now, account, threadKey, item.messageKey, item.fromMe ? 1 : 0);
                return next;
            });
        }
    }

    static State transition(State current, State outcome) {
        if (current.isFinal()) {
            // A copy that already exists stays; AMBIGUOUS may still be reported over a single copy
            // when a second, different copy for the same identity arrives.
            if (outcome == State.AMBIGUOUS && (current == State.IMPORTED || current == State.ALREADY_PRESENT)) {
                return State.AMBIGUOUS;
            }
            return current;
        }
        return outcome;
    }

    /** Identities still worth offering bytes for: pending, unavailable, or failed under the retry limit. */
    public List<String[]> retryable(String packageName, String accountKey, int limit) {
        List<String[]> result = new ArrayList<>();
        Long account = library.findAccount(packageName, accountKey);
        if (account == null) return result;
        for (Map<String, Object> row : library.sql().query("SELECT thread_key, message_key, from_me FROM direct_items "
                        + "WHERE account_id=? AND (state IN ('pending','unavailable') OR (state='failed' AND attempts<?)) "
                        + "ORDER BY message_timestamp ASC, id ASC LIMIT ?", account, MAX_ATTEMPTS, Math.max(1, limit))) {
            result.add(new String[] { Sql.Rows.getString(row, "thread_key"), Sql.Rows.getString(row, "message_key"),
                    Sql.Rows.getLong(row, "from_me") != 0 ? "1" : "0" });
        }
        return result;
    }

    /** Coverage summed over every account of a package (the module's own library screen). */
    public Report reportAll(String packageName) {
        Report total = new Report();
        for (Map<String, Object> row : library.sql().query("SELECT account_key FROM accounts WHERE package_name=? "
                + "ORDER BY account_key", packageName)) {
            Report one = report(packageName, Sql.Rows.getString(row, "account_key"), null);
            total.threads.addAll(one.threads);
            total.discovered += one.discovered;
            total.processed += one.processed;
            total.pending += one.pending;
            total.failed += one.failed;
            total.unavailable += one.unavailable;
            total.threadsComplete += one.threadsComplete;
        }
        return total;
    }

    /** Coverage for one account; pass a thread to restrict it. Never infers anything from absence. */
    public Report report(String packageName, String accountKey, String onlyThread) {
        Report report = new Report();
        Long account = library.findAccount(packageName, accountKey);
        if (account == null) return report;
        Sql sql = library.sql();
        String threadFilter = onlyThread == null ? "" : " AND thread_key=?";
        Object[] args = onlyThread == null ? new Object[] { account } : new Object[] { account, onlyThread };
        Map<String, ThreadReport> byThread = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : sql.query("SELECT thread_key, newest_ts, oldest_ts, reached_start, pages "
                + "FROM direct_threads WHERE account_id=?" + threadFilter + " ORDER BY thread_key", args)) {
            ThreadReport thread = new ThreadReport(Sql.Rows.getString(row, "thread_key"));
            thread.reachedStart = Sql.Rows.getLong(row, "reached_start") != 0;
            thread.newestTimestamp = Sql.Rows.getLong(row, "newest_ts");
            thread.oldestTimestamp = Sql.Rows.getLong(row, "oldest_ts");
            thread.pages = (int) Sql.Rows.getLong(row, "pages");
            byThread.put(thread.threadKey, thread);
        }
        for (Map<String, Object> row : sql.query("SELECT thread_key, state, view_once, COUNT(*) AS n FROM direct_items "
                + "WHERE account_id=?" + threadFilter + " GROUP BY thread_key, state, view_once", args)) {
            String key = Sql.Rows.getString(row, "thread_key");
            ThreadReport thread = byThread.computeIfAbsent(key, ThreadReport::new);
            int n = (int) Sql.Rows.getLong(row, "n");
            thread.discovered += n;
            if (Sql.Rows.getLong(row, "view_once") != 0) thread.viewOnce += n;
            switch (State.of(Sql.Rows.getString(row, "state"))) {
                case PENDING -> thread.pending += n;
                case IMPORTED -> thread.imported += n;
                case ALREADY_PRESENT -> thread.alreadyPresent += n;
                case UNAVAILABLE -> thread.unavailable += n;
                case REFUSED -> thread.refused += n;
                case AMBIGUOUS -> thread.ambiguous += n;
                case FAILED -> thread.failed += n;
            }
        }
        for (Map<String, Object> row : sql.query("SELECT d.thread_key, "
                + "SUM(CASE WHEN i.id IS NOT NULL AND i.trashed_at=0 AND b.state='ok' THEN 1 ELSE 0 END) AS ok, "
                + "SUM(CASE WHEN i.id IS NOT NULL AND i.trashed_at=0 AND (b.state IS NULL OR b.state!='ok') "
                + "THEN 1 ELSE 0 END) AS damaged, "
                + "SUM(CASE WHEN i.id IS NULL OR i.trashed_at>0 THEN 1 ELSE 0 END) AS trashed "
                + "FROM direct_items d LEFT JOIN items i ON i.uuid=d.item_uuid LEFT JOIN blobs b ON b.sha256=i.sha256 "
                + "WHERE d.account_id=? AND d.state IN ('imported','already_present')"
                + threadFilter.replace("thread_key", "d.thread_key") + " GROUP BY d.thread_key", args)) {
            ThreadReport thread = byThread.computeIfAbsent(Sql.Rows.getString(row, "thread_key"), ThreadReport::new);
            thread.keptAvailable = (int) Sql.Rows.getLong(row, "ok");
            thread.keptDamaged = (int) Sql.Rows.getLong(row, "damaged");
            thread.keptTrashed = (int) Sql.Rows.getLong(row, "trashed");
        }
        for (ThreadReport thread : byThread.values()) {
            report.threads.add(thread);
            report.discovered += thread.discovered;
            report.processed += thread.processed();
            report.pending += thread.pending;
            report.failed += thread.failed;
            report.unavailable += thread.unavailable;
            if (thread.complete()) report.threadsComplete++;
        }
        return report;
    }
}
