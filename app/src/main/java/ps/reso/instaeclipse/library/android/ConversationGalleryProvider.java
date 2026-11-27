package ps.reso.instaeclipse.library.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;

import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.core.IpcSecurity;

/** Issues only scoped launch capabilities. No catalogue or media bytes cross IPC. */
public final class ConversationGalleryProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://ps.reso.instaeclipse.media_library");
    public static final String TOKEN = "library_token";
    public static final String ACCOUNT = "library_account";
    public static final String THREAD = "library_thread";
    public static final String MESSAGE = "library_message";
    public static final String FROM_ME = "library_from_me";
    public static final String PACKAGE = "library_package";
    public static final String ENABLED = "directMediaLibrary";
    /** Separate, off by default: consolidation of conventional Direct media from Instagram's cache. */
    public static final String CONSOLIDATION = "directMediaConsolidation";

    public static boolean enabled(Context context) {
        return ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(context)
                .getBoolean(ENABLED, false);
    }

    public static boolean consolidationEnabled(Context context) {
        return enabled(context) && ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(context)
                .getBoolean(CONSOLIDATION, false);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        String caller = IpcSecurity.supportedCallerPackage(context, Binder.getCallingUid());
        if (caller == null) throw new SecurityException("Unsupported media library caller");
        if (DirectOfferContract.METHOD.equals(method)) return offer(context, caller, extras);
        if (!"gallery".equals(method) || extras == null || !enabled(context)) return null;
        String account = extras.getString(ACCOUNT);
        String thread = extras.getString(THREAD);
        if (!ImportRequest.validKey(account) || !ImportRequest.validKey(thread)) return null;
        String message = extras.getString(MESSAGE);
        boolean fromMe = extras.getBoolean(FROM_ME);
        if (message == null ? fromMe : !ImportRequest.validKey(message)) return null;
        Bundle result = new Bundle();
        result.putString(TOKEN, GalleryAccess.issue(new GalleryAccess.Grant(caller, account, thread, message,
                fromMe, SystemClock.elapsedRealtime())));
        return result;
    }

    /** Records one page of the scanner's offers and starts the worker. No bytes cross IPC. */
    private static Bundle offer(Context context, String caller, Bundle extras) {
        Bundle reply = new Bundle();
        reply.putBoolean(DirectOfferContract.ACCEPTED, false);
        if (extras == null || !consolidationEnabled(context)) return reply;
        java.util.List<ps.reso.instaeclipse.library.core.DirectConsolidation.Offer> offers =
                ps.reso.instaeclipse.library.core.DirectConsolidation.decode(extras.getString(ACCOUNT),
                        extras.getString(THREAD), extras.getStringArrayList(DirectOfferContract.KEYS),
                        extras.getStringArrayList(DirectOfferContract.KINDS),
                        extras.getStringArrayList(DirectOfferContract.URLS),
                        extras.getStringArrayList(DirectOfferContract.FLAGS),
                        extras.getLongArray(DirectOfferContract.TIMESTAMPS));
        if (offers == null) return reply;
        try {
            new ps.reso.instaeclipse.library.core.DirectConsolidation(MediaLibraryRuntime.get(context))
                    .offer(caller, extras.getString(ACCOUNT), extras.getString(THREAD), offers, false);
        } catch (java.io.IOException | RuntimeException failure) {
            return reply;
        }
        // Only a scan the user started clears a pause; automatic capture respects it.
        DirectConsolidationWorker.kick(context, extras.getBoolean(DirectOfferContract.EXPLICIT, false), false);
        reply.putBoolean(DirectOfferContract.ACCEPTED, true);
        return reply;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
