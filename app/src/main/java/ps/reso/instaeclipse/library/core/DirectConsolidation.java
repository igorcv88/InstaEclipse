package ps.reso.instaeclipse.library.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Consolidation of eligible Direct media into the library: discovery, then capture, each with
 * its own recorded state (see {@link DirectCoverage}).
 *
 * <p>Binding owner decisions (newest first):</p>
 * <ul>
 *   <li>2026-11-27, after the device test: conventional and permanent ("keep in chat") media are
 *       kept automatically when Instagram shows them. View-once and replayable items are kept
 *       only after the user opened them in Instagram (the account is in {@code seen_user_ids}) or
 *       when they are the user's own; otherwise they are recorded and left {@code UNAVAILABLE}
 *       with {@code view_once_not_fetched}. Fetching then consumes nothing.</li>
 *   <li>2026-11-26: never run, automatically, anything that consumes a view-once view or changes a
 *       message's state on the server.</li>
 * </ul>
 *
 * <p>The only network operation is an HTTPS GET of a media CDN URL that the client's own model
 * already holds ({@link DirectStoreRow#isCdnUrl}). It never calls an Instagram API host.</p>
 */
public final class DirectConsolidation {
    public static final String VIEW_ONCE_NOT_FETCHED = "view_once_not_fetched";
    public static final String NO_URL = "no_url";
    private static final long LOCK_TIMEOUT_MS = 30_000L;

    /** Opens a CDN URL. Implementations must refuse anything {@link DirectStoreRow#isCdnUrl} refuses. */
    public interface Fetcher {
        /** @return the body, or {@code null} when the server says the object is gone (403/404/410). */
        InputStream open(String url) throws IOException;
    }

    /** One part offered by the Instagram-side scanner. */
    public static final class Offer {
        public final String accountKey, threadKey;
        public final DirectCoverage.Discovered identity;
        public final String url;
        /** See {@link DirectStoreRow.Part#fetchAllowed}. A view-once item is fetched only when this is set. */
        public final boolean fetchAllowed;

        public Offer(String accountKey, String threadKey, DirectCoverage.Discovered identity, String url) {
            this(accountKey, threadKey, identity, url, !identity.viewOnce);
        }

        public Offer(String accountKey, String threadKey, DirectCoverage.Discovered identity, String url,
                     boolean fetchAllowed) {
            this.fetchAllowed = fetchAllowed;
            this.accountKey = accountKey;
            this.threadKey = threadKey;
            this.identity = identity;
            this.url = url;
        }
    }

    public static final class RunReport {
        public int attempted, transferred, validated, persisted, imported, alreadyPresent, unavailable, refused, ambiguous, failed, superseded;
        public boolean cancelled;
        /** True only when no retryable item is left at the end of the run. */
        public boolean drained;
    }

    private final MediaLibrary library;
    private final DirectCoverage coverage;
    private final DirectCapture capture;
    private final DirectProgress progress;

    public DirectConsolidation(MediaLibrary library) {
        this(library, DirectProgress.NONE);
    }

    public DirectConsolidation(MediaLibrary library, DirectProgress progress) {
        this.progress = progress;
        this.library = library;
        this.coverage = new DirectCoverage(library);
        this.capture = new DirectCapture(library, coverage);
    }

    public DirectCoverage coverage() {
        return coverage;
    }

    /**
     * Records offers from one thread. View-once items and items without a usable URL are settled
     * as UNAVAILABLE at once; nothing is fetched here.
     */
    public int offer(String packageName, String accountKey, String threadKey, List<Offer> offers,
                     boolean reachedStart) throws IOException {
        List<DirectCoverage.Discovered> page = new ArrayList<>();
        for (Offer offer : offers) {
            if (!offer.accountKey.equals(accountKey) || !offer.threadKey.equals(threadKey)) {
                throw new IllegalArgumentException("Offer outside the page scope");
            }
            page.add(offer.identity);
        }
        int added = coverage.discover(packageName, accountKey, threadKey, new DirectCoverage.Page(page, reachedStart));
        for (Offer offer : offers) {
            if (offer.identity.viewOnce && !offer.fetchAllowed) {
                coverage.settle(packageName, accountKey, threadKey, offer.identity,
                        DirectCoverage.State.UNAVAILABLE, null, VIEW_ONCE_NOT_FETCHED);
            } else if (!DirectStoreRow.isCdnUrl(offer.url)) {
                coverage.settleMissingSourceOffer(packageName, accountKey, threadKey, offer.identity, NO_URL);
            } else {
                recordSource(packageName, accountKey, threadKey, offer.identity, offer.url);
            }
        }
        return added;
    }

    public static final class OfferReport {
        public int discovered, queued, alreadyStored, skipped;
    }

    /** Snapshot of just this offer's identities, before starting its worker. */
    public OfferReport offerWithReport(String packageName, String accountKey, String threadKey,
                                       List<Offer> offers) throws IOException {
        OfferReport report = new OfferReport();
        report.discovered = offer(packageName, accountKey, threadKey, offers, false);
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            for (Offer offer : offers) {
                DirectCoverage.Discovered identity = offer.identity;
                LibraryItem kept = library.lookup(packageName, accountKey, threadKey, identity.messageKey,
                        identity.fromMe);
                String disposition;
                if (kept != null && kept.isAvailable() && library.blobs().existsWithSize(kept.sha256, kept.sizeBytes)) {
                    report.alreadyStored++;
                    disposition = "already_persisted";
                } else if (!library.sql().query("SELECT 1 " + WORK
                                + "AND a.account_key=? AND d.thread_key=? AND d.message_key=? AND d.from_me=? LIMIT 1",
                        packageName, DirectCoverage.MAX_ATTEMPTS, accountKey, threadKey, identity.messageKey,
                        identity.fromMe ? 1 : 0).isEmpty()) {
                    report.queued++;
                    disposition = "eligible";
                } else {
                    report.skipped++;
                    disposition = "not_eligible";
                }
                progress.event(accountKey, threadKey, identity.messageKey, "catalog_offer",
                        "disposition=" + disposition + " from_me=" + identity.fromMe);
            }
        }
        return report;
    }

    private void recordSource(String packageName, String accountKey, String threadKey,
                              DirectCoverage.Discovered identity, String url) throws IOException {
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            Long account = library.findAccount(packageName, accountKey);
            if (account == null) return;
            library.sql().update("UPDATE direct_items SET "
                            + "state=CASE WHEN state='unavailable' AND (source_url IS NULL OR source_url<>?) "
                            + "THEN 'pending' ELSE state END, "
                            + "attempts=CASE WHEN state='failed' AND source_url<>? THEN 0 ELSE attempts END, "
                            + "source_seen_at=CASE WHEN source_url=? AND state IN ('pending','failed') "
                            + "THEN source_seen_at ELSE MAX(COALESCE(source_seen_at,0)+1,?) END, "
                            + "source_url=? WHERE account_id=? "
                            + "AND thread_key=? AND message_key=? AND from_me=? AND (view_once=0 OR ?=1 OR from_me=1)",
                    url, url, url, library.now(), url, account, threadKey, identity.messageKey, identity.fromMe ? 1 : 0,
                    identity.viewOnce ? 1 : 0);
        }
    }

    /** Thrown through a transfer when the user paused or the feature was turned off. Never an outcome. */
    public static final class Cancelled extends IOException {
        public Cancelled() { super("Consolidation paused"); }
    }

    private static final String META_PAUSED = "direct_consolidation_paused";

    /**
     * Work that may run: not view-once, a CDN URL recorded, and either not final (pending, failed
     * under the retry limit, unavailable with a newer URL) or final but with its kept copy damaged
     * or missing outside the trash, so a lost copy is repaired from the same message.
     */
    private static final String WORK = "FROM direct_items d JOIN accounts a ON a.id=d.account_id "
            + "LEFT JOIN items i ON i.uuid=d.item_uuid LEFT JOIN blobs b ON b.sha256=i.sha256 "
            + "WHERE a.package_name=? AND d.source_url IS NOT NULL "
            + "AND (d.state='pending' OR (d.state='failed' AND d.attempts<?) "
            + "OR (d.state='unavailable' AND d.source_seen_at>d.settled_at) "
            + "OR (d.state IN ('imported','already_present') AND i.id IS NOT NULL AND i.trashed_at=0 "
            + "AND b.state!='ok' AND d.source_seen_at>d.settled_at)) ";

    /** Persistent: survives the process, so a restart never resumes paused work by itself. */
    public void setPaused(boolean paused) throws IOException {
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            library.sql().inTransaction(() -> {
                library.sql().update("DELETE FROM meta WHERE key=?", META_PAUSED);
                if (paused) library.sql().insert("INSERT INTO meta (key, value) VALUES (?, '1')", META_PAUSED);
                return null;
            });
        }
    }

    public boolean isPaused() {
        return !library.sql().query("SELECT 1 FROM meta WHERE key=?", META_PAUSED).isEmpty();
    }

    /** Explicit "continue": failed items past the retry limit get a fresh set of attempts. */
    public int resetFailures(String packageName) throws IOException {
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            return library.sql().update("UPDATE direct_items SET attempts=0 WHERE state='failed' AND account_id IN "
                    + "(SELECT id FROM accounts WHERE package_name=?)", packageName);
        }
    }

    /**
     * Fetches and imports up to {@code budget} items. New work precedes retries, oldest first
     * within each class. An expired URL is UNAVAILABLE until a fresh one is offered. {@code cancelled} is checked between items and during every
     * read, so a pause stops a large video mid-transfer without recording an outcome.
     */
    public RunReport run(String packageName, Fetcher fetcher, int budget, BooleanSupplier cancelled) throws IOException {
        RunReport report = new RunReport();
        DirectProgress trackImport = (account, thread, part, stage, detail) -> {
            if ("transfer_complete".equals(stage)) report.transferred++;
            if ("validated".equals(stage)) report.validated++;
            if ("persisted".equals(stage) && !detail.startsWith("status=ALREADY_PRESENT ")) report.persisted++;
            progress.event(account, thread, part, stage, detail);
        };
        List<Map<String, Object>> work = library.sql().query("SELECT d.id, d.state, d.view_once, d.thread_key, d.message_key, d.from_me, "
                        + "d.media_kind, d.message_timestamp, d.source_url, d.source_seen_at, a.account_key " + WORK
                        + "ORDER BY CASE WHEN d.state='failed' THEN 1 ELSE 0 END, d.message_timestamp ASC, d.id ASC LIMIT ?",
                packageName, DirectCoverage.MAX_ATTEMPTS, Math.max(1, budget));
        for (Map<String, Object> row : work) {
            if (cancelled.getAsBoolean()) {
                report.cancelled = true;
                break;
            }
            String account = Sql.Rows.getString(row, "account_key");
            String thread = Sql.Rows.getString(row, "thread_key");
            String url = Sql.Rows.getString(row, "source_url");
            long sourceVersion = Sql.Rows.getLong(row, "source_seen_at");
            DirectCoverage.Discovered identity = new DirectCoverage.Discovered(
                    Sql.Rows.getString(row, "message_key"), Sql.Rows.getLong(row, "from_me") != 0,
                    Sql.Rows.getString(row, "media_kind"), Sql.Rows.getLong(row, "view_once") != 0,
                    Sql.Rows.getLong(row, "message_timestamp"));
            report.attempted++;
            progress.event(account, thread, identity.messageKey, "transfer_start", "source_version=" + sourceVersion + " from_me=" + identity.fromMe);
            DirectCapture.Outcome outcome;
            try {
                if (!DirectStoreRow.isCdnUrl(url)) {
                    outcome = capture.capture(packageName, account, thread, identity, null);
                } else {
                    InputStream opened;
                    try {
                        opened = fetcher.open(url);
                    } catch (Cancelled paused) {
                        throw paused;
                    } catch (IOException transientFailure) {
                        DirectCoverage.State state = coverage.settleTransfer(packageName, account, thread,
                                identity, DirectCoverage.State.FAILED, null, "fetch", url, sourceVersion);
                        progress.event(account, thread, identity.messageKey, "outcome",
                                "state=" + state + " reason=fetch_exception");
                        if (state == DirectCoverage.State.PENDING) report.superseded++;
                        else report.failed++;
                        finishRepair(row, url);
                        continue;
                    }
                    progress.event(account, thread, identity.messageKey, "transfer_response",
                            opened == null ? "body=unavailable" : "body=opened");
                    InputStream body = opened == null ? null : new FilterInputStream(opened) {
                        @Override public int read() throws IOException {
                            if (cancelled.getAsBoolean()) throw new Cancelled();
                            return super.read();
                        }

                        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                            if (cancelled.getAsBoolean()) throw new Cancelled();
                            return super.read(buffer, offset, length);
                        }
                    };
                    outcome = capture.capture(packageName, account, thread, identity, body == null ? null : () -> body, url, sourceVersion, trackImport);
                }
            } catch (Cancelled paused) {
                progress.event(account, thread, identity.messageKey, "outcome", "state=paused");
                report.attempted--;
                report.cancelled = true;
                break;
            }
            finishRepair(row, url);
            progress.event(account, thread, identity.messageKey, "outcome",
                    "state=" + outcome.state + " reason=" + outcome.error);
            switch (outcome.state) {
                case IMPORTED -> report.imported++;
                case ALREADY_PRESENT -> report.alreadyPresent++;
                case UNAVAILABLE -> report.unavailable++;
                case REFUSED -> report.refused++;
                case AMBIGUOUS -> report.ambiguous++;
                case FAILED -> report.failed++;
                case PENDING -> report.superseded++;
            }
        }
        report.drained = !report.cancelled
                && library.sql().query("SELECT 1 " + WORK + "LIMIT 1", packageName, DirectCoverage.MAX_ATTEMPTS).isEmpty();
        return report;
    }

    private void finishRepair(Map<String, Object> row, String attemptedSource) throws IOException {
        String before = Sql.Rows.getString(row, "state");
        if (!"imported".equals(before) && !"already_present".equals(before)) return;
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            library.sql().update("UPDATE direct_items SET settled_at=MAX(?,source_seen_at) "
                            + "WHERE id=? AND source_url=? AND source_seen_at=?",
                    library.now(), Sql.Rows.getLong(row, "id"), attemptedSource,
                    Sql.Rows.getLong(row, "source_seen_at"));
        }
    }

    /** Max parts in one IPC offer. */
    public static final int MAX_OFFER_PARTS = 200;

    /**
     * Rebuilds offers that crossed IPC. Returns {@code null} unless every list has the same length,
     * every identity is valid and the page is within {@link #MAX_OFFER_PARTS}: a malformed page is
     * refused whole, never partly applied.
     */
    public static List<Offer> decode(String accountKey, String threadKey, List<String> keys, List<String> kinds,
                                     List<String> urls, List<String> flags, long[] timestamps) {
        if (!ImportRequest.validKey(accountKey) || !ImportRequest.validKey(threadKey) || keys == null
                || kinds == null || urls == null || flags == null || timestamps == null) return null;
        int n = keys.size();
        if (n == 0 || n > MAX_OFFER_PARTS || kinds.size() != n || urls.size() != n || flags.size() != n
                || timestamps.length != n) return null;
        List<Offer> offers = new ArrayList<>(n);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            String flag = flags.get(i);
            if (flag == null || !flag.matches("[01]{3}") || !ImportRequest.validKey(keys.get(i))) return null;
            String kind = kinds.get(i);
            if (!MediaSniffer.KIND_IMAGE.equals(kind) && !MediaSniffer.KIND_VIDEO.equals(kind) && !"unknown".equals(kind))
                return null;
            boolean fromMe = flag.charAt(0) == '1';
            if (!seen.add(keys.get(i) + (fromMe ? "/1" : "/0"))) return null;
            String url = urls.get(i);
            if (url != null && (url.isEmpty() || url.length() > 4096)) url = null;
            offers.add(new Offer(accountKey, threadKey, new DirectCoverage.Discovered(keys.get(i), fromMe, kind,
                    flag.charAt(1) == '1', timestamps[i]), url, flag.charAt(2) == '1'));
        }
        return offers;
    }
}
