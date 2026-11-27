package ps.reso.instaeclipse.library.android;

import android.content.Context;
import android.util.Log;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

import ps.reso.instaeclipse.library.core.DirectConsolidation;
import ps.reso.instaeclipse.library.core.DirectStoreRow;
import ps.reso.instaeclipse.library.core.MediaLibrary;

/**
 * Module-process worker: fetches recorded CDN URLs of conventional Direct media and keeps them.
 * One run at a time; sequential; stops at the first transient failure (retried next run).
 *
 * <p>The request carries no cookies or Instagram credentials and follows no redirect outside the
 * media CDNs. View-once items never get a URL recorded, so they are never requested.</p>
 */
public final class DirectConsolidationWorker {
    private static final String TAG = "IeDirectConsolidation";
    private static final int BUDGET = 25;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicBoolean pause = new AtomicBoolean();
    /** Set by any request; a finishing worker checks it after clearing {@link #running}. */
    private static final AtomicBoolean requested = new AtomicBoolean();
    private static final AtomicReference<DirectConsolidation.RunReport> last = new AtomicReference<>();

    private DirectConsolidationWorker() {}

    public static boolean isRunning() { return running.get(); }

    public static DirectConsolidation.RunReport lastReport() { return last.get(); }

    /** Pauses now and persistently: neither a restart nor an automatic resume undoes it. */
    public static void requestPause(Context context) {
        pause.set(true);
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                new DirectConsolidation(MediaLibraryRuntime.get(app)).setPaused(true);
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "pause not persisted: " + failure.getClass().getSimpleName());
            }
        }, "ie-direct-consolidation-pause");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * @param explicit a user action (a scan they started, or "continue"): clears a pause and,
     *                 for "continue", gives failed items a fresh set of attempts. Automatic
     *                 resumes ({@code explicit=false}) never override a pause.
     */
    public static void kick(Context context, boolean explicit, boolean resetFailures) {
        Context app = context.getApplicationContext();
        if (explicit) pause.set(false);
        requested.set(true);
        if (!running.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> work(app, explicit, resetFailures), "ie-direct-consolidation");
        thread.setDaemon(true);
        thread.start();
    }

    private static void work(Context app, boolean explicit, boolean resetFailures) {
        DirectConsolidation.RunReport total = new DirectConsolidation.RunReport();
        try {
            MediaLibrary library = MediaLibraryRuntime.get(app);
            DirectConsolidation consolidation = new DirectConsolidation(library);
            if (explicit) consolidation.setPaused(false);
            else if (consolidation.isPaused()) pause.set(true);
            if (resetFailures) consolidation.resetFailures(DirectStoreScanner.HOST);
            // Stop as soon as the user pauses or turns the feature off, also mid-transfer.
            BooleanSupplier stop = () -> pause.get() || !ConversationGalleryProvider.consolidationEnabled(app);
            while (requested.getAndSet(false) && !stop.getAsBoolean()) {
                while (true) {
                    DirectConsolidation.RunReport report = consolidation.run(DirectStoreScanner.HOST,
                            DirectConsolidationWorker::open, BUDGET, stop);
                    add(total, report);
                    if (report.drained || report.stoppedOnFailure || report.cancelled || report.attempted == 0) break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            total.stoppedOnFailure = true;
            Log.w(TAG, "run failed: " + failure.getClass().getSimpleName());
        } finally {
            last.set(total);
            running.set(false);
        }
        Log.i(TAG, "run imported=" + total.imported + " present=" + total.alreadyPresent
                + " unavailable=" + total.unavailable + " refused=" + total.refused
                + " ambiguous=" + total.ambiguous + " failed=" + total.failed + " drained=" + total.drained);
        // A request that arrived after the last check but before running was cleared.
        if (requested.get() && !pause.get() && !total.stoppedOnFailure) kick(app, false, false);
    }

    private static void add(DirectConsolidation.RunReport total, DirectConsolidation.RunReport r) {
        total.attempted += r.attempted;
        total.imported += r.imported;
        total.alreadyPresent += r.alreadyPresent;
        total.unavailable += r.unavailable;
        total.refused += r.refused;
        total.ambiguous += r.ambiguous;
        total.failed += r.failed;
        total.stoppedOnFailure |= r.stoppedOnFailure;
        total.cancelled |= r.cancelled;
        total.drained = r.drained;
    }

    /** GET of a CDN URL; null for 403/404/410 (expired or removed). */
    static InputStream open(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 3; hop++) {
            if (!DirectStoreRow.isCdnUrl(current)) throw new IOException("Refused host");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            if (!(connection instanceof HttpsURLConnection)) throw new IOException("Not HTTPS");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                current = connection.getHeaderField("Location");
                connection.disconnect();
                continue;
            }
            if (code == 403 || code == 404 || code == 410) {
                connection.disconnect();
                return null;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            InputStream body = connection.getInputStream();
            long expected = connection.getContentLengthLong();
            return new FilterInputStream(body) {
                private long seen;

                @Override public int read() throws IOException {
                    int value = super.read();
                    if (value < 0) checkEnd(); else seen++;
                    return value;
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n < 0) checkEnd(); else seen += n;
                    return n;
                }

                /** A body shorter than its Content-Length is a cut transfer, never a valid file. */
                private void checkEnd() throws IOException {
                    if (expected >= 0 && seen != expected) throw new IOException("Truncated transfer");
                }

                @Override public void close() throws IOException {
                    try { super.close(); } finally { connection.disconnect(); }
                }
            };
        }
        throw new IOException("Too many redirects");
    }
}
