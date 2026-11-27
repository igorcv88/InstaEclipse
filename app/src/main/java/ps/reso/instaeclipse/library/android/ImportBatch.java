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
import ps.reso.instaeclipse.library.core.BatchCoverage;
import ps.reso.instaeclipse.library.core.LibraryException;
import ps.reso.instaeclipse.library.core.MediaLibrary;

/** A private SAF batch checkpoint. Jobs are serialized by the library UI worker. */
public final class ImportBatch {
    public static final int MAX_SELECTION = 200;
    private final AtomicFile file, progressFile, lastReport;
    private final ContentResolver resolver;

    public ImportBatch(Context context) {
        this(context.getContentResolver(), new File(context.getFilesDir(), "media_library"));
    }

    ImportBatch(ContentResolver content, File root) {
        file = new AtomicFile(new File(root, "import.json"));
        progressFile = new AtomicFile(new File(root, "import_progress.json"));
        lastReport = new AtomicFile(new File(root, "import_report.json"));
        resolver = content;
    }

    public boolean pending(ImportRequest scope) throws IOException, JSONException {
        JSONObject job = read();
        return job != null && sameScope(job, scope);
    }

    /** Recovery from the companion works even when the original Direct message is off-screen. */
    public ImportRequest pendingScope() throws IOException, JSONException {
        JSONObject job = read();
        if (job == null) return null;
        String thread = job.getString("thread");
        if (thread.isEmpty()) return ImportRequest.localFile();
        String account = job.getString("account");
        String pkg = job.getString("package");
        if (!"com.instagram.android".equals(pkg) || !ImportRequest.validKey(account)
                || !ImportRequest.validKey(thread)) throw new IOException("Invalid checkpoint scope");
        String message = job.optString("message", null);
        return message == null ? ImportRequest.forConversation(pkg, account, thread)
                : ImportRequest.forMessage(pkg, account, thread, message, job.optBoolean("fromMe"));
    }

    public void select(ImportRequest scope, List<Uri> uris) throws IOException, JSONException {
        if (uris.isEmpty() || uris.size() > (scope.messageKey == null ? MAX_SELECTION : 1)) {
            throw new IOException("Invalid selection size");
        }
        select(scope, uris, null, uris.size(), 0);
    }

    public void selectExport(ImportRequest scope, ExportFolder.Selection selection) throws IOException, JSONException {
        if (scope.messageKey != null || selection.candidates().isEmpty()) throw new IOException("Invalid export");
        select(scope, selection.candidates(), selection.tree(), selection.visited(), selection.skipped());
    }

    private void select(ImportRequest scope, List<Uri> uris, Uri tree, int visited, int skipped)
            throws IOException, JSONException {
        if (read() != null) throw new IOException("Another import is pending");
        Set<Uri> existing = new HashSet<>();
        resolver.getPersistedUriPermissions().forEach(permission -> existing.add(permission.getUri()));
        JSONArray selected = new JSONArray();
        List<Uri> acquired = new ArrayList<>();
        try {
            List<Uri> grants = tree == null ? uris : java.util.Collections.singletonList(tree);
            for (Uri uri : grants) {
                if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) throw new IOException("Invalid URI");
                if (!existing.contains(uri)) {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    acquired.add(uri);
                }
            }
            JSONArray owned = new JSONArray();
            acquired.forEach(uri -> owned.put(uri.toString()));
            for (Uri uri : uris) selected.put(new JSONObject().put("uri", uri.toString()));
            write(file, scopedRecord(scope).put("id", java.util.UUID.randomUUID().toString())
                    .put("files", selected).put("owned", owned).put("visited", visited).put("skipped", skipped));
        } catch (IOException | JSONException | RuntimeException failure) {
            for (Uri uri : acquired) release(uri);
            throw failure;
        }
    }

    public record Result(BatchCoverage coverage, List<String> outcomes) {}

    public Result run(MediaLibrary library, ImportRequest scope, BooleanSupplier cancelled,
                            Consumer<Integer> progress) throws IOException, JSONException {
        JSONObject job = read();
        if (job == null || !sameScope(job, scope)) throw new IOException("No pending import in this scope");
        JSONArray files = job.getJSONArray("files");
        String id = job.optString("id", "legacy");
        JSONObject checkpoint = read(progressFile);
        JSONArray outcomes = checkpoint != null && id.equals(checkpoint.optString("id"))
                ? checkpoint.getJSONArray("results") : job.optJSONArray("results");
        if (outcomes == null) outcomes = new JSONArray();
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
            write(progressFile, new JSONObject().put("id", id).put("results", outcomes));
            progress.accept(outcomes.length());
        }
        List<String> results = new ArrayList<>();
        for (int i = 0; i < outcomes.length(); i++) results.add(outcomes.getString(i));
        BatchCoverage coverage = BatchCoverage.measure(files.length(), scope.messageKey != null,
                job.optInt("visited", files.length()), job.optInt("skipped"), results);
        if (coverage.complete()) {
            write(lastReport, scopedRecord(scope).put("candidates", coverage.candidates())
                    .put("visited", coverage.visited()).put("skipped", coverage.skipped()).put("results", outcomes));
            discard(scope);
        }
        return new Result(coverage, results);
    }

    public void discard(ImportRequest scope) throws IOException, JSONException {
        JSONObject job = read();
        if (job == null || !sameScope(job, scope)) return;
        JSONArray files = job.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject source = files.getJSONObject(i);
            if (source.optBoolean("owned")) release(Uri.parse(source.getString("uri")));
        }
        JSONArray owned = job.optJSONArray("owned");
        if (owned != null) for (int i = 0; i < owned.length(); i++) release(Uri.parse(owned.getString(i)));
        file.delete();
        progressFile.delete();
    }

    public Result last(ImportRequest scope) throws IOException, JSONException {
        JSONObject report = read(lastReport);
        if (report == null || !sameScope(report, scope)) return null;
        JSONArray values = report.getJSONArray("results");
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) outcomes.add(values.getString(i));
        return new Result(BatchCoverage.measure(report.getInt("candidates"), scope.messageKey != null,
                report.getInt("visited"), report.getInt("skipped"), outcomes), outcomes);
    }

    private static JSONObject scopedRecord(ImportRequest scope) throws JSONException {
        return new JSONObject().put("package", scope.packageName).put("account", scope.accountKey)
                .put("thread", scope.threadKey).put("message", scope.messageKey).put("fromMe", scope.fromMe);
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
        return read(file);
    }

    private static JSONObject read(AtomicFile file) throws IOException, JSONException {
        try {
            return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            return null;
        }
    }

    private static void write(AtomicFile file, JSONObject job) throws IOException {
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
                && scope.threadKey.equals(job.optString("thread"))
                && java.util.Objects.equals(scope.messageKey, job.optString("message", null))
                && scope.fromMe == job.optBoolean("fromMe");
    }
}
