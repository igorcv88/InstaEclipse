package ps.reso.instaeclipse.library;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.UUID;

import ps.reso.instaeclipse.library.android.AndroidDecodeProbe;
import ps.reso.instaeclipse.library.android.AndroidSql;
import ps.reso.instaeclipse.library.android.ConversationGalleryProvider;
import ps.reso.instaeclipse.library.android.GallerySaver;
import ps.reso.instaeclipse.library.core.BlobStore;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.library.core.LibraryLock;
import ps.reso.instaeclipse.library.core.MediaLibrary;
import ps.reso.instaeclipse.library.ui.MediaLibraryActivity;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class MediaLibraryDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void androidSqlAndDecodeUsePrivateCopiesAfterSourceCloses() throws Exception {
        File root = new File(context.getCacheDir(), "library-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        File db = new File(root, "catalog.db");
        try (AndroidSql sql = AndroidSql.open(db)) {
            MediaLibrary library = new MediaLibrary(sql, new BlobStore(root, File::getUsableSpace, 0),
                    new LibraryLock(root), System::currentTimeMillis);
            library.setDecodeProbe(new AndroidDecodeProbe());
            for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[]{
                    Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP}) {
                byte[] fixture = image(format);
                MediaLibrary.ImportResult saved = library.importMedia(ImportRequest.localFile(),
                        () -> new ByteArrayInputStream(fixture));
                assertTrue(library.blobs().verify(saved.item.sha256, fixture.length));
            }
            MediaLibrary.ImportResult video = library.importMedia(ImportRequest.localFile(), () ->
                    InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("dash-fixtures/h264.mp4"));
            assertTrue(video.item.isVideo());
        }
        try (AndroidSql sql = AndroidSql.open(db)) {
            MediaLibrary library = new MediaLibrary(sql, new BlobStore(root), new LibraryLock(root),
                    System::currentTimeMillis);
            assertEquals(4, library.listAll(false).size());
        }
    }

    @Test public void mediaStoreExportIsPublishedAndPreservesBytes() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        byte[] fixture = image(Bitmap.CompressFormat.PNG);
        Uri item = GallerySaver.save(context, new ByteArrayInputStream(fixture), "image/png",
                "InstaEclipse-test-" + UUID.randomUUID() + ".png", "DCIM/Instagram Library/");
        try (Cursor row = context.getContentResolver().query(item,
                new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null);
             InputStream stream = context.getContentResolver().openInputStream(item)) {
            assertNotNull(row);
            assertTrue(row.moveToFirst());
            assertEquals(0, row.getInt(0));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = stream.read(buffer)) != -1; ) bytes.write(buffer, 0, read);
            assertArrayEquals(fixture, bytes.toByteArray());
        } finally {
            context.getContentResolver().delete(item, null, null);
        }
    }

    @Test public void unknownUidCannotMintGalleryCapabilities() {
        try {
            context.getContentResolver().call(ConversationGalleryProvider.URI, "gallery", null, null);
            fail("The instrumentation process must not be trusted as Instagram");
        } catch (SecurityException expected) {
            // Same-UID or unrelated process.
        }
    }

    @Test public void companionGalleryLaunchesWithoutInstagramInstalled() {
        try (ActivityScenario<MediaLibraryActivity> scenario = ActivityScenario.launch(MediaLibraryActivity.class)) {
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(android.R.id.list)));
        }
    }

    private static byte[] image(Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(0xff124c93);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(format, 90, out));
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }
}
