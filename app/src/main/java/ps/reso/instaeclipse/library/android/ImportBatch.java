package ps.reso.instaeclipse.library.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.library.core.LibraryException;
import ps.reso.instaeclipse.library.core.MediaLibrary;

/** A private SAF batch checkpoint. Jobs are serialized by the library UI worker. */
public final class ImportBatch {
    public static final int MAX_SELECTION = 200;
    private final AtomicFile file;
    private final ContentResolver resolver;

    public ImportBatch(Context context) {
        File root = new File(context.getFilesDir(), "media_library");
        file = new AtomicFile(new File(root, "import.json"));
        resolver = context.getContentResolver();
    }

    public boolean pending(ImportRequest scope) throws IOException, JSONException {
        JSONObject job = read();
        return job != null && sameScope(job, scope);
    }

    public void select(ImportRequest scope, List<Uri> uris) throws IOException, JSONException {
        if (read() != null) throw new IOException("Another import is pending");
        if (uris.isEmpty() || uris.size() > MAX_SELECTION) throw new IOException("Invalid selection size");
        Set<Uri> existing = new HashSet<>();
        resolver.getPersistedUriPermissions().forEach(permission -> existing.add(permission.getUri()));
        JSONArray selected = new JSONArray();
        List<Uri> acquired = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) throw new IOException("Invalid URI");
                if (!existing.contains(uri)) {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    acquired.add(uri);
                }
                selected.put(new JSONObject().put("uri", uri.toString()).put("owned", !existing.contains(uri)));
            }
            save(new JSONObject().put("package", scope.packageName).put("account", scope.accountKey)
                    .put("thread", scope.threadKey).put("files", selected).put("results", new JSONArray()));
        } catch (IOException | JSONException | RuntimeException failure) {
            for (Uri uri : acquired) release(uri);
            throw failure;
        }
    }

    public List<String> run(MediaLibrary library, ImportRequest scope, BooleanSupplier cancelled,
                            Consumer<Integer> progress) throws IOException, JSONException {
        JSONObject job = read();
        if (job == null || !sameScope(job, scope)) throw new IOException("No pending import in this scope");
        JSONArray files = job.getJSONArray("files");
        JSONArray outcomes = job.getJSONArray("results");
        while (outcomes.length() < files.length() && !cancelled.getAsBoolean()) {
            Uri uri = Uri.parse(files.getJSONObject(outcomes.length()).getString("uri"));
            String result;
            try {
                result = library.importMedia(scope, () -> open(uri, cancelled)).status.name();
            } catch (LibraryException failure) {
                if (cancelled.getAsBoolean()) break;
                // A full library or a temporary I/O failure can be retried from the same cursor.
                if (failure.code == LibraryException.Code.QUOTA_EXCEEDED
                        || failure.code == LibraryException.Code.INSUFFICIENT_STORAGE
                        || failure.code == LibraryException.Code.IO) throw failure;
                result = failure.code.name();
            } catch (FileNotFoundException | SecurityException failure) {
                result = "SOURCE_UNAVAILABLE";
            } catch (IOException failure) {
                if (cancelled.getAsBoolean()) break;
                throw failure;
            }
            outcomes.put(result);
            save(job);
            progress.accept(outcomes.length());
        }
        List<String> results = new ArrayList<>();
        for (int i = 0; i < outcomes.length(); i++) results.add(outcomes.getString(i));
        if (outcomes.length() == files.length()) discard(scope);
        return results;
    }

    public void discard(ImportRequest scope) throws IOException, JSONException {
        JSONObject job = read();
        if (job == null || !sameScope(job, scope)) return;
        JSONArray files = job.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject source = files.getJSONObject(i);
            if (source.optBoolean("owned")) release(Uri.parse(source.getString("uri")));
        }
        file.delete();
    }

    private InputStream open(Uri uri, BooleanSupplier cancelled) throws IOException {
        InputStream source = resolver.openInputStream(uri);
        if (source == null) throw new FileNotFoundException();
        return new FilterInputStream(source) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (cancelled.getAsBoolean()) throw new IOException("Import paused");
                return super.read(buffer, offset, length);
            }
        };
    }

    private void release(Uri uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The user or the document provider revoked the grant.
        }
    }

    private JSONObject read() throws IOException, JSONException {
        try {
            return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            return null;
        }
    }

    private void save(JSONObject job) throws IOException {
        FileOutputStream out = file.startWrite();
        try {
            out.write(job.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException failure) {
            file.failWrite(out);
            throw failure;
        }
    }

    private static boolean sameScope(JSONObject job, ImportRequest scope) {
        return scope.packageName.equals(job.optString("package"))
                && scope.accountKey.equals(job.optString("account"))
                && scope.threadKey.equals(job.optString("thread"));
    }
}
