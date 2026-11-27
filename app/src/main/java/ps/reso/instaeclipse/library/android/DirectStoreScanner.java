package ps.reso.instaeclipse.library.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.library.core.DirectStoreRow;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Runs in the Instagram process. Reads Instagram's own local Direct cache read-only and offers
 * the media rows of one conversation to the module, which records and fetches them.
 *
 * <p>Read-only: the database is opened with {@code OPEN_READONLY}; nothing is written, deleted or
 * marked. Only rows of the account and thread the user has open are read, so a scan never
 * crosses accounts. The cache holds a partial, evictable subset of the history: what Instagram
 * already loaded. A scan never claims to cover a thread's whole history.</p>
 */
public final class DirectStoreScanner {
    static final String HOST = "com.instagram.android";
    static final String DATABASE = "direct.db";
    /** Schema version of {@code X.01ab} in 454.0.0.0.55. Another version is not read. */
    static final int SCHEMA_VERSION = 15;
    private static final int PAGE = 50;
    private static final int MAX_ROWS_PER_SCAN = 100_000;
    private static final ThreadPoolExecutor SCANS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> new Thread(task, "ie-direct-store-scan"));

    private DirectStoreScanner() {}

    public interface Done {
        void finished(Result result);
    }

    public static final class Result {
        /** {@code offered} counts parts the module accepted; a refused page stops the scan. */
        public int rows, offered, refusedRows;
        public boolean accepted;
        /** True when a read limit stopped the scan before the cache was exhausted. */
        public boolean truncated;
        public String error;
    }

    /** Queues a scan of one conversation. Returns false when one is already queued. */
    public static boolean scanThread(Context context, String accountId, String threadId, Done done) {
        if (!ImportRequest.validKey(accountId) || !ImportRequest.validKey(threadId)) return false;
        Context app = context.getApplicationContext();
        try {
            SCANS.execute(() -> {
                Result result = scan(app, accountId, threadId);
                if (done != null) done.finished(result);
            });
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    /**
     * Queues a scan of every conversation of one account in the local cache. Explicit action only:
     * never started automatically (owner decision 2026-11-26).
     */
    public static boolean scanAccount(Context context, String accountId, Done done) {
        if (!ImportRequest.validKey(accountId)) return false;
        Context app = context.getApplicationContext();
        try {
            SCANS.execute(() -> {
                Result total = new Result();
                for (String thread : threads(app, accountId, total)) {
                    Result one = scan(app, accountId, thread);
                    total.rows += one.rows;
                    total.offered += one.offered;
                    total.truncated |= one.truncated;
                    total.refusedRows += one.refusedRows;
                    total.accepted |= one.accepted;
                    if (one.error != null) {
                        total.error = one.error;
                        break;
                    }
                }
                if (done != null) done.finished(total);
            });
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    private static List<String> threads(Context context, String accountId, Result result) {
        List<String> threads = new ArrayList<>();
        File path = context.getDatabasePath(DATABASE);
        if (!path.isFile()) {
            result.error = "no_store";
            return threads;
        }
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            if (db.getVersion() != SCHEMA_VERSION) {
                result.error = "schema_" + db.getVersion();
                return threads;
            }
            try (Cursor cursor = db.rawQuery("SELECT DISTINCT thread_id FROM messages WHERE user_id=? "
                    + "AND message_type IN ('media','raven_media','visual_media') AND thread_id IS NOT NULL",
                    new String[] { accountId })) {
                while (cursor.moveToNext()) {
                    if (threads.size() >= 10_000) {
                        result.truncated = true;
                        break;
                    }
                    String thread = cursor.getString(0);
                    if (ImportRequest.validKey(thread)) threads.add(thread);
                }
            }
        } catch (RuntimeException failure) {
            result.error = failure.getClass().getSimpleName();
        }
        return threads;
    }

    static Result scan(Context context, String accountId, String threadId) {
        Result result = new Result();
        File path = context.getDatabasePath(DATABASE);
        if (!path.isFile()) {
            result.error = "no_store";
            return result;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            if (db.getVersion() != SCHEMA_VERSION) {
                result.error = "schema_" + db.getVersion();
                return result;
            }
            List<String[]> page = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            long afterTs = -1;
            String afterId = "";
            while (result.rows < MAX_ROWS_PER_SCAN) {
                page.clear();
                try (Cursor cursor = db.rawQuery("SELECT server_item_id, message_type, timestamp, message FROM messages "
                                + "WHERE user_id=? AND thread_id=? AND message_type IN ('media','raven_media','visual_media') "
                                + "AND server_item_id IS NOT NULL AND (timestamp>? OR (timestamp=? AND server_item_id>?)) "
                                + "ORDER BY timestamp ASC, server_item_id ASC LIMIT " + PAGE,
                        new String[] { accountId, threadId, Long.toString(afterTs), Long.toString(afterTs), afterId })) {
                    while (cursor.moveToNext()) {
                        String message = cursor.getType(3) == Cursor.FIELD_TYPE_BLOB
                                ? new String(cursor.getBlob(3), java.nio.charset.StandardCharsets.UTF_8)
                                : cursor.getString(3);
                        page.add(new String[] { cursor.getString(0), cursor.getString(1),
                                Long.toString(cursor.getLong(2)), message });
                    }
                }
                if (page.isEmpty()) break;
                List<DirectStoreRow> rows = new ArrayList<>();
                for (String[] raw : page) {
                    result.rows++;
                    afterTs = Long.parseLong(raw[2]);
                    afterId = raw[0];
                    if (!seen.add(raw[0])) continue; // the same message stored twice by the client
                    DirectStoreRow row = DirectStoreRow.parse(accountId, threadId, raw[0], raw[1], afterTs, raw[3]);
                    if (row == null) result.refusedRows++;
                    else rows.add(row);
                }
                // IPC pages are capped at MAX_OFFER_PARTS; carousels can exceed it within one DB page.
                for (List<DirectStoreRow> send : DirectStoreRow.batches(rows,
                        ps.reso.instaeclipse.library.core.DirectConsolidation.MAX_OFFER_PARTS)) {
                    Boolean accepted = offer(context, accountId, threadId, send);
                    if (accepted == null) {
                        result.error = "module_unavailable";
                        return result;
                    }
                    if (!accepted) {
                        result.error = "refused_by_module";
                        return result;
                    }
                    result.accepted = true;
                    for (DirectStoreRow row : send) result.offered += row.parts.size();
                }
                if (page.size() < PAGE) break;
                if (result.rows >= MAX_ROWS_PER_SCAN) result.truncated = true;
            }
        } catch (RuntimeException failure) {
            result.error = failure.getClass().getSimpleName();
            ModuleLog.line("(IE|MediaLibrary) direct store scan failed: " + result.error);
        } finally {
            if (db != null) db.close();
        }
        ModuleLog.line("(IE|MediaLibrary) direct store scan rows=" + result.rows + " offered=" + result.offered
                + " refused=" + result.refusedRows + (result.error == null ? "" : " error=" + result.error));
        return result;
    }

    /** @return whether the module accepted the page, or null when it could not be reached */
    static Boolean offer(Context context, String account, String thread, List<DirectStoreRow> rows) {
        ArrayList<String> keys = new ArrayList<>(), kinds = new ArrayList<>(), urls = new ArrayList<>();
        ArrayList<String> flags = new ArrayList<>();
        long[] timestamps;
        List<Long> ts = new ArrayList<>();
        for (DirectStoreRow row : rows) {
            for (DirectStoreRow.Part part : row.parts) {
                keys.add(part.identity.messageKey);
                kinds.add(part.identity.mediaKind);
                urls.add(part.fetchAllowed ? part.best.url : "");
                flags.add((part.identity.fromMe ? "1" : "0") + (part.identity.viewOnce ? "1" : "0")
                        + (part.fetchAllowed ? "1" : "0"));
                ts.add(part.identity.timestamp);
            }
        }
        timestamps = new long[ts.size()];
        for (int i = 0; i < timestamps.length; i++) timestamps[i] = ts.get(i);
        Bundle extras = new Bundle();
        extras.putString(ConversationGalleryProvider.ACCOUNT, account);
        extras.putString(ConversationGalleryProvider.THREAD, thread);
        extras.putStringArrayList(DirectOfferContract.KEYS, keys);
        extras.putStringArrayList(DirectOfferContract.KINDS, kinds);
        extras.putStringArrayList(DirectOfferContract.URLS, urls);
        extras.putStringArrayList(DirectOfferContract.FLAGS, flags);
        extras.putLongArray(DirectOfferContract.TIMESTAMPS, timestamps);
        try {
            Bundle reply = context.getContentResolver().call(ConversationGalleryProvider.URI,
                    DirectOfferContract.METHOD, null, extras);
            return reply != null && reply.getBoolean(DirectOfferContract.ACCEPTED);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
