package ps.reso.instaeclipse.library.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.util.AtomicFile;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import ps.reso.instaeclipse.library.core.PendingPublish;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Explicit "save to gallery": inserts one photo/video into MediaStore under a relative album
 * path. Ordering and failure handling are in {@link PendingPublish}.
 * Needs no storage permission on Android 10+, where it is used; callers fall back on older versions.
 */
public final class GallerySaver {
    private GallerySaver() { }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    @androidx.annotation.RequiresApi(29)
    public static synchronized Uri save(Context context, InputStream input, String mimeType, String displayName,
                           String relativePath) throws IOException {
        if (!supported()) throw new IOException("MediaStore albums need Android 10+");
        ContentResolver resolver = context.getContentResolver();
        AtomicFile journal = journal(context);
        recover(context);
        boolean video = mimeType != null && mimeType.startsWith("video/");
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return PendingPublish.save(new PendingPublish.Store<Uri>() {
            @Override
            public Uri createPending() throws IOException {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri entry = resolver.insert(collection, values);
                if (entry == null) return null;
                FileOutputStream state = null;
                try {
                    state = journal.startWrite();
                    state.write(entry.toString().getBytes(StandardCharsets.UTF_8));
                    journal.finishWrite(state);
                } catch (IOException failure) {
                    if (state != null) journal.failWrite(state);
                    resolver.delete(entry, null, null);
                    throw failure;
                }
                return entry;
            }

            @Override
            public OutputStream open(Uri entry) throws IOException {
                return resolver.openOutputStream(entry, "w");
            }

            @Override
            public int publish(Uri entry) {
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                int updated = resolver.update(entry, publish, null, null);
                if (updated == 1) journal.delete();
                return updated;
            }

            @Override
            public void delete(Uri entry) {
                try {
                    resolver.delete(entry, null, null);
                    journal.delete();
                } catch (RuntimeException ignored) {
                }
            }
        }, input);
    }

    @androidx.annotation.RequiresApi(29)
    static synchronized void recover(Context context) throws IOException {
        AtomicFile journal = journal(context);
        Uri entry;
        try {
            entry = Uri.parse(new String(journal.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            return;
        }
        if (!"media".equals(entry.getAuthority()) || !"content".equals(entry.getScheme())
                || entry.getPathSegments().size() != 4 || entry.getLastPathSegment() == null
                || !entry.getLastPathSegment().matches("[0-9]+")) {
            throw new IOException("Invalid pending export journal");
        }
        ContentResolver resolver = context.getContentResolver();
        boolean pending;
        try (Cursor row = resolver.query(entry, new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
            if (row == null) throw new IOException("Could not inspect pending export");
            pending = row.moveToFirst() && row.getInt(0) == 1;
        }
        if (pending) resolver.delete(entry, null, null);
        journal.delete();
    }

    private static AtomicFile journal(Context context) {
        return new AtomicFile(new File(new File(context.getFilesDir(), "media_library"), "pending_export"));
    }
}
