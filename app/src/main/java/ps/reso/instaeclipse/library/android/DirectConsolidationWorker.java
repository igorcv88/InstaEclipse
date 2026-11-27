package ps.reso.instaeclipse.library.android;

import android.content.Context;
import android.util.Log;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

import ps.reso.instaeclipse.library.core.DirectConsolidation;
import ps.reso.instaeclipse.library.core.DirectStoreRow;

/**
 * Module-process worker: fetches recorded CDN URLs of conventional Direct media and keeps them.
 * One run at a time; sequential; stops at the first transient failure (retried next run).
 *
 * <p>The request carries no cookies or Instagram credentials and follows no redirect outside the
 * media CDNs. Only sources admitted by DirectConsolidation are recorded and fetched.</p>
 */
public final class DirectConsolidationWorker {
    private static final String TAG = "IeDirectConsolidation";
    private static final int BUDGET = 25;
    private enum Request { AUTO, RESUME, RETRY_FAILURES, PAUSE }

    private static final Object requests = new Object();
    private static Request pending;
    private static volatile boolean running;
    private static volatile boolean pause;
    private static volatile DirectConsolidation.RunReport last;

    private DirectConsolidationWorker() {}

    public static boolean isRunning() { return running; }

    public static DirectConsolidation.RunReport lastReport() { return last; }

    /** Stops the active transfer and serializes the persistent pause with resume requests. */
    public static void requestPause(Context context) {
        submit(context, Request.PAUSE);
    }

    /** Automatic offers respect a pause; an explicit continuation can reset exhausted attempts. */
    public static void kick(Context context, boolean explicit, boolean resetFailures) {
        submit(context, resetFailures ? Request.RETRY_FAILURES : explicit ? Request.RESUME : Request.AUTO);
    }

    private static void submit(Context context, Request request) {
        synchronized (requests) {
            if (request == Request.PAUSE) {
                pause = true;
                pending = request;
            } else if (request == Request.RESUME || request == Request.RETRY_FAILURES) {
                pause = false;
                if (pending != Request.RETRY_FAILURES) pending = request;
            } else if (pending == null) {
                pending = request;
            }
            if (running) return;
            running = true;
            Context app = context.getApplicationContext();
            Thread thread = new Thread(() -> work(app != null ? app : context), "ie-direct-consolidation");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void work(Context app) {
        while (true) {
            Request request;
            synchronized (requests) {
                request = pending;
                pending = null;
                if (request == null) {
                    running = false;
                    return;
                }
            }
            try {
                DirectConsolidation consolidation = new DirectConsolidation(MediaLibraryRuntime.get(app));
                if (request == Request.PAUSE) {
                    consolidation.setPaused(true);
                    continue;
                }
                if (request != Request.AUTO) consolidation.setPaused(false);
                if (request == Request.RETRY_FAILURES) consolidation.resetFailures(DirectStoreScanner.HOST);
                run(app, consolidation);
            } catch (IOException | RuntimeException failure) {
                if (request != Request.PAUSE) {
                    DirectConsolidation.RunReport failed = new DirectConsolidation.RunReport();
                    failed.stoppedOnFailure = true;
                    last = failed;
                }
                Log.w(TAG, "request=" + request + " failed: " + failure.getClass().getSimpleName());
            }
        }
    }

    private static void run(Context app, DirectConsolidation consolidation) throws IOException {
        DirectConsolidation.RunReport total = new DirectConsolidation.RunReport();
        boolean paused = consolidation.isPaused();
        BooleanSupplier stop = () -> pause || !ConversationGalleryProvider.consolidationEnabled(app);
        if (paused || stop.getAsBoolean()) {
            total.cancelled = true;
        } else {
            do {
                DirectConsolidation.RunReport report = consolidation.run(DirectStoreScanner.HOST,
                        DirectConsolidationWorker::open, BUDGET, stop);
                add(total, report);
                if (report.drained || report.stoppedOnFailure || report.cancelled || report.attempted == 0) break;
            } while (!stop.getAsBoolean());
        }
        last = total;
        Log.i(TAG, "run imported=" + total.imported
                + " present=" + total.alreadyPresent + " unavailable=" + total.unavailable + " refused=" + total.refused
                + " ambiguous=" + total.ambiguous + " failed=" + total.failed + " drained=" + total.drained
                + " attempted=" + total.attempted + " paused=" + (paused || pause) + " cancelled=" + total.cancelled);
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
