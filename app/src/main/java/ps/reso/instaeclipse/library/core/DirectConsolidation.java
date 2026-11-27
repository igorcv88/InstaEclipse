package ps.reso.instaeclipse.library.core;

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
 * <p>Binding owner decisions (2026-11-26, newest first):</p>
 * <ul>
 *   <li>Never run, automatically, anything that consumes a view-once view or changes a message's
 *       state on the server. View-once and other expiring items are recorded as discovered and
 *       left {@code UNAVAILABLE} with {@code view_once_not_fetched}; they are never fetched here.</li>
 *   <li>Historical consolidation of eligible files stays separate from opening ephemeral items.</li>
 *   <li>Validate first with the owner's own or authorised test media; no bulk run over real
 *       conversations by default.</li>
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

        public Offer(String accountKey, String threadKey, DirectCoverage.Discovered identity, String url) {
            this.accountKey = accountKey;
            this.threadKey = threadKey;
            this.identity = identity;
            this.url = url;
        }
    }

    public static final class RunReport {
        public int attempted, imported, alreadyPresent, unavailable, refused, ambiguous, failed;
        public boolean stoppedOnFailure, cancelled;
        /** True only when no retryable item is left at the end of the run. */
        public boolean drained;
    }

    private final MediaLibrary library;
    private final DirectCoverage coverage;
    private final DirectCapture capture;

    public DirectConsolidation(MediaLibrary library) {
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
            if (offer.identity.viewOnce) {
                coverage.settle(packageName, accountKey, threadKey, offer.identity,
                        DirectCoverage.State.UNAVAILABLE, null, VIEW_ONCE_NOT_FETCHED);
            } else if (!DirectStoreRow.isCdnUrl(offer.url)) {
                coverage.settle(packageName, accountKey, threadKey, offer.identity,
                        DirectCoverage.State.UNAVAILABLE, null, NO_URL);
            } else {
                recordSource(packageName, accountKey, threadKey, offer.identity, offer.url);
            }
        }
        return added;
    }

    private void recordSource(String packageName, String accountKey, String threadKey,
                              DirectCoverage.Discovered identity, String url) throws IOException {
        try (LibraryLock.Held ignored = library.lock().acquire(LOCK_TIMEOUT_MS)) {
            Long account = library.findAccount(packageName, accountKey);
            if (account == null) return;
            library.sql().update("UPDATE direct_items SET source_url=?, source_seen_at=? WHERE account_id=? "
                            + "AND thread_key=? AND message_key=? AND from_me=? AND view_once=0",
                    url, library.now(), account, threadKey, identity.messageKey, identity.fromMe ? 1 : 0);
        }
    }

    /**
     * Fetches and imports up to {@code budget} items that have a recorded CDN URL and are not
     * final, oldest first. The first transient failure stops the run so the item is retried next
     * time; an expired URL is UNAVAILABLE until the scanner offers a fresh one.
     */
    public RunReport run(String packageName, Fetcher fetcher, int budget, BooleanSupplier cancelled) throws IOException {
        RunReport report = new RunReport();
        List<Map<String, Object>> work = library.sql().query("SELECT d.thread_key, d.message_key, d.from_me, "
                        + "d.media_kind, d.view_once, d.message_timestamp, d.source_url, a.account_key "
                        + "FROM direct_items d JOIN accounts a ON a.id=d.account_id "
                        + "WHERE a.package_name=? AND d.view_once=0 AND d.source_url IS NOT NULL "
                        + "AND (d.state='pending' OR (d.state='failed' AND d.attempts<?) "
                        + "OR (d.state='unavailable' AND d.source_seen_at>d.settled_at)) "
                        + "ORDER BY d.message_timestamp ASC, d.id ASC LIMIT ?",
                packageName, DirectCoverage.MAX_ATTEMPTS, Math.max(1, budget));
        for (Map<String, Object> row : work) {
            if (cancelled.getAsBoolean()) {
                report.cancelled = true;
                break;
            }
            String account = Sql.Rows.getString(row, "account_key");
            String thread = Sql.Rows.getString(row, "thread_key");
            String url = Sql.Rows.getString(row, "source_url");
            DirectCoverage.Discovered identity = new DirectCoverage.Discovered(
                    Sql.Rows.getString(row, "message_key"), Sql.Rows.getLong(row, "from_me") != 0,
                    Sql.Rows.getString(row, "media_kind"), false, Sql.Rows.getLong(row, "message_timestamp"));
            report.attempted++;
            DirectCapture.Outcome outcome;
            if (!DirectStoreRow.isCdnUrl(url)) {
                outcome = capture.capture(packageName, account, thread, identity, null);
            } else {
                final InputStream[] opened = new InputStream[1];
                try {
                    opened[0] = fetcher.open(url);
                } catch (IOException transientFailure) {
                    coverage.settle(packageName, account, thread, identity, DirectCoverage.State.FAILED, null, "fetch");
                    report.failed++;
                    report.stoppedOnFailure = true;
                    break;
                }
                if (opened[0] == null) {
                    outcome = capture.capture(packageName, account, thread, identity, null);
                } else {
                    outcome = capture.capture(packageName, account, thread, identity, () -> opened[0]);
                }
            }
            switch (outcome.state) {
                case IMPORTED -> report.imported++;
                case ALREADY_PRESENT -> report.alreadyPresent++;
                case UNAVAILABLE -> report.unavailable++;
                case REFUSED -> report.refused++;
                case AMBIGUOUS -> report.ambiguous++;
                case FAILED -> {
                    report.failed++;
                    report.stoppedOnFailure = true;
                }
                default -> { }
            }
            if (report.stoppedOnFailure) break;
        }
        report.drained = !report.stoppedOnFailure && !report.cancelled && library.sql().query(
                "SELECT 1 FROM direct_items d JOIN accounts a ON a.id=d.account_id WHERE a.package_name=? "
                        + "AND d.view_once=0 AND d.source_url IS NOT NULL AND (d.state='pending' "
                        + "OR (d.state='failed' AND d.attempts<?) "
                        + "OR (d.state='unavailable' AND d.source_seen_at>d.settled_at)) LIMIT 1",
                packageName, DirectCoverage.MAX_ATTEMPTS).isEmpty();
        return report;
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
            if (flag == null || !flag.matches("[01]{2}") || !ImportRequest.validKey(keys.get(i))) return null;
            String kind = kinds.get(i);
            if (!MediaSniffer.KIND_IMAGE.equals(kind) && !MediaSniffer.KIND_VIDEO.equals(kind) && !"unknown".equals(kind))
                return null;
            boolean fromMe = flag.charAt(0) == '1';
            if (!seen.add(keys.get(i) + (fromMe ? "/1" : "/0"))) return null;
            String url = urls.get(i);
            if (url != null && (url.isEmpty() || url.length() > 4096)) url = null;
            offers.add(new Offer(accountKey, threadKey, new DirectCoverage.Discovered(keys.get(i), fromMe, kind,
                    flag.charAt(1) == '1', timestamps[i]), url));
        }
        return offers;
    }
}
