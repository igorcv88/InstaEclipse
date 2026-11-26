package ps.reso.instaeclipse.library.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.library.core.BlobStore;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.library.core.LibraryLock;
import ps.reso.instaeclipse.library.core.MediaLibrary;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ImportCheckpointDeviceTest {
    @Test public void restoresTheCursorAndScopeAfterReopeningAndroidStorage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "batch-test").toFile();
        ImportRequest scope = ImportRequest.forConversation("com.instagram.android", "111", "222");
        AtomicBoolean pause = new AtomicBoolean();
        JSONArray sources = new JSONArray();
        for (int color : new int[]{0xff226611, 0xff226688}) {
            File fixture = new File(root, color + ".png");
            Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            try (FileOutputStream sink = new FileOutputStream(fixture)) {
                bitmap.eraseColor(color);
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 90, sink));
            } finally {
                bitmap.recycle();
            }
            // Private fixture at the same boundary the SAF discovery phase commits.
            sources.put(new JSONObject().put("uri", Uri.fromFile(fixture).toString()));
        }
        File jobFile = new File(root, "import.json");
        JSONObject discovery = new JSONObject().put("id", "test").put("package", scope.packageName)
                .put("account", scope.accountKey).put("thread", scope.threadKey).put("files", sources);
        Files.write(jobFile.toPath(), discovery.toString().getBytes(StandardCharsets.UTF_8));

        File db = new File(root, "catalog.db");
        try (AndroidSql sql = AndroidSql.open(db)) {
            ImportBatch batch = new ImportBatch(context.getContentResolver(), root);
            ImportBatch.Result partial = batch.run(open(sql, root), scope, pause::get, count -> pause.set(true));
            assertFalse(partial.coverage().complete());
            assertEquals(1, partial.coverage().processed());
        }
        try (AndroidSql sql = AndroidSql.open(db)) {
            ImportBatch resumed = new ImportBatch(context.getContentResolver(), root);
            assertEquals("222", resumed.pendingScope().threadKey);
            assertFalse(resumed.pending(ImportRequest.forConversation("com.instagram.android", "999", "222")));
            MediaLibrary library = open(sql, root);
            ImportBatch.Result result = resumed.run(library, scope, () -> false, count -> { });
            assertTrue(result.coverage().complete());
            assertEquals(2, result.coverage().unknownMessage());
            assertEquals(2, library.listChat(scope.packageName, scope.accountKey, scope.threadKey).size());
            assertFalse(jobFile.exists());
            assertEquals(result.coverage(), resumed.last(scope).coverage());
        }
    }

    private static MediaLibrary open(AndroidSql sql, File root) throws Exception {
        return new MediaLibrary(sql, new BlobStore(root, File::getUsableSpace, 0), new LibraryLock(root),
                System::currentTimeMillis);
    }
}
