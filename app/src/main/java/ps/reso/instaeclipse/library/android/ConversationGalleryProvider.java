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

    public static boolean enabled(Context context) {
        return ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(context)
                .getBoolean(ENABLED, false);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        String caller = IpcSecurity.supportedCallerPackage(context, Binder.getCallingUid());
        if (caller == null) throw new SecurityException("Unsupported media library caller");
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

    @Override public Cursor query(Uri uri, String[] p, String s, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
