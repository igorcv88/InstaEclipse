package ps.reso.instaeclipse.library.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.library.android.ConversationGalleryProvider;
import ps.reso.instaeclipse.library.android.GallerySaver;
import ps.reso.instaeclipse.library.android.ExportFolder;
import ps.reso.instaeclipse.library.core.BatchCoverage;
import ps.reso.instaeclipse.library.android.ImportBatch;
import ps.reso.instaeclipse.library.android.MediaLibraryRuntime;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.library.core.LibraryBackup;
import ps.reso.instaeclipse.library.core.LibraryException;
import ps.reso.instaeclipse.library.core.LibraryIntegrity;
import ps.reso.instaeclipse.library.core.LibraryItem;
import ps.reso.instaeclipse.library.core.MediaLibrary;

public final class MediaLibraryActivity extends AppCompatActivity implements LibraryGridAdapter.Listener {
    static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(12), command -> new Thread(command, "ie-library-io"));

    private static final int PICK = 501, EXPORT = 502, BACKUP = 503, RESTORE = 504, PICK_EXPORT_FOLDER = 505;
    private final AtomicBoolean paused = new AtomicBoolean();
    private ImportRequest scope;
    private ImportBatch batch;
    private MediaLibrary library;
    private LibraryGridAdapter grid;
    private TextView status;
    private Button add, manage;
    private boolean trash, busy, pending;
    private String exportUuid;
    private Runnable pendingResult;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String thread = getIntent().getStringExtra(ConversationGalleryProvider.THREAD);
        if (thread != null && !getIntent().getBooleanExtra("resume_from_companion", false)
                && !ConversationGalleryProvider.enabled(this)) {
            finish();
            return;
        }
        String message = getIntent().getStringExtra(ConversationGalleryProvider.MESSAGE);
        String pkg = getIntent().getStringExtra(ConversationGalleryProvider.PACKAGE);
        String account = getIntent().getStringExtra(ConversationGalleryProvider.ACCOUNT);
        if (thread == null) scope = ImportRequest.localFile();
        else if (message == null) scope = ImportRequest.forConversation(pkg, account, thread);
        else scope = ImportRequest.forMessage(pkg, account, thread, message,
                getIntent().getBooleanExtra(ConversationGalleryProvider.FROM_ME, false));
        if (state != null) {
            trash = state.getBoolean("trash");
            exportUuid = state.getString("exportUuid");
        }
        batch = new ImportBatch(getApplicationContext());
        setTitle(libraryTitle());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, window) -> {
            Insets inset = window.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(inset.left, inset.top, inset.right, inset.bottom);
            return window;
        });
        LinearLayout actions = new LinearLayout(this);
        add = button(R.string.media_library_import);
        add.setOnClickListener(view -> {
            if (busy) {
                paused.set(true);
                add.setEnabled(false);
            } else if (pending) runBatch(null);
            else pickMedia();
        });
        manage = button(R.string.media_library_manage);
        manage.setOnClickListener(view -> showManagement());
        actions.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(manage, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        status = new TextView(this);
        int inset = Math.round(getResources().getDisplayMetrics().density * 12);
        status.setPadding(inset, inset, inset, inset);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        RecyclerView recycler = new RecyclerView(this);
        recycler.setId(android.R.id.list);
        recycler.setLayoutManager(new GridLayoutManager(this, 3));
        root.addView(recycler, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        work(() -> {
            library = MediaLibraryRuntime.get(getApplicationContext());
            post(() -> {
                grid = new LibraryGridAdapter(library, this);
                recycler.setAdapter(grid);
            });
        }, false);
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (scoped() && !getIntent().getBooleanExtra("resume_from_companion", false)
                && !ConversationGalleryProvider.enabled(this)) {
            finish();
        } else if (library != null && !busy) {
            work(() -> {}, false);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("trash", trash);
        state.putString("exportUuid", exportUuid);
    }

    @Override protected void onDestroy() {
        paused.set(true);
        super.onDestroy();
    }

    private boolean scoped() { return !scope.threadKey.isEmpty(); }

    private Button button(int title) {
        Button button = new Button(this);
        button.setText(title);
        return button;
    }

    private void pickMedia() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, scope.messageKey == null);
        launch(picker, PICK);
    }

    private void launch(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (RuntimeException failure) {
            toast(R.string.media_library_failure);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != Activity.RESULT_OK || data == null) return;
        if (busy) {
            pendingResult = () -> handlePickerResult(request, data);
            return;
        }
        handlePickerResult(request, data);
    }

    private void handlePickerResult(int request, Intent data) {
        Uri uri = data.getData();
        if (request == PICK_EXPORT_FOLDER && uri != null && scope.messageKey == null) {
            work(() -> {
                ExportFolder.Selection discovered = ExportFolder.discover(getContentResolver(), uri);
                post(() -> {
                    if (discovered.candidates().isEmpty()) {
                        detail(getString(R.string.media_library_export_empty));
                        return;
                    }
                    String preview = getString(R.string.media_library_export_discovered,
                            discovered.visited(), discovered.candidates().size(), discovered.skipped());
                    new AlertDialog.Builder(this).setTitle(R.string.media_library_import_folder)
                            .setMessage(preview)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.media_library_import,
                                    (dialog, which) -> runBatch(null, discovered)).show();
                });
            }, false);
        } else if (request == PICK) {
            LinkedHashSet<Uri> selected = new LinkedHashSet<>();
            if (uri != null) selected.add(uri);
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri item = data.getClipData().getItemAt(i).getUri();
                    if (item != null) selected.add(item);
                }
            }
            if (selected.isEmpty() || selected.size() > (scope.messageKey == null ? ImportBatch.MAX_SELECTION : 1)) {
                toast(R.string.media_library_selection_limit);
                return;
            }
            work(() -> {
                StringBuilder preview = new StringBuilder(getResources().getQuantityString(
                        R.plurals.media_library_confirm_import, selected.size(), selected.size()));
                int shown = 0;
                for (Uri item : selected) {
                    if (shown++ == 8) break;
                    String name = displayName(item);
                    if (name != null) preview.append('\n').append(name);
                }
                post(() -> new AlertDialog.Builder(this).setTitle(R.string.media_library_import)
                        .setMessage(preview.toString())
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.media_library_import,
                                (dialog, which) -> runBatch(new ArrayList<>(selected))).show());
            }, false);
        } else if (request == EXPORT && uri != null) {
            String uuid = exportUuid;
            exportUuid = null;
            work(() -> {
                LibraryItem item = requireAvailable(uuid);
                try (InputStream in = library.blobs().open(item.sha256); OutputStream out = output(uri)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
                }
                post(() -> toast(R.string.media_library_exported));
            }, false);
        } else if ((request == BACKUP || request == RESTORE) && uri != null) {
            password(uri, request == RESTORE);
        }
    }

    private String displayName(Uri item) {
        try (Cursor cursor = getContentResolver().query(item,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException unavailable) {
            // The document can still be presented by its position in the selection.
        }
        return null;
    }

    private void runBatch(List<Uri> selected) {
        runBatch(selected, null);
    }

    private void runBatch(List<Uri> selected, ExportFolder.Selection export) {
        paused.set(false);
        work(() -> {
            if (selected != null) batch.select(scope, selected);
            if (export != null) batch.selectExport(scope, export);
            ImportBatch.Result result = batch.run(library, scope, paused::get,
                    count -> post(() -> status.setText(getString(R.string.media_library_progress, count))));
            post(() -> showCoverage(result));
        }, true);
    }

    private void showCoverage(ImportBatch.Result result) {
        BatchCoverage coverage = result.coverage();
        StringBuilder summary = new StringBuilder(getString(R.string.media_library_coverage,
                coverage.processed(), coverage.candidates(), coverage.copied(), coverage.alreadyPresent(),
                coverage.failed(), coverage.unknownMessage(), coverage.visited(), coverage.skipped()));
        int listed = 0;
        for (int i = 0; i < result.outcomes().size() && listed < 20; i++) {
            String outcome = result.outcomes().get(i);
            if (outcome.equals("CREATED") || outcome.equals("DEDUPLICATED") || outcome.equals("REPAIRED")
                    || outcome.equals("ALREADY_PRESENT")) continue;
            listed++;
            summary.append('\n').append(getString(R.string.media_library_result, i + 1, resultLabel(outcome)));
        }
        if (!coverage.complete()) summary.append('\n').append(getString(R.string.media_library_paused));
        new AlertDialog.Builder(this).setTitle(R.string.media_library_import)
                .setMessage(summary.toString()).setPositiveButton(android.R.string.ok, null).show();
    }

    private String resultLabel(String result) {
        if ("CREATED".equals(result) || "DEDUPLICATED".equals(result) || "REPAIRED".equals(result)) {
            return getString(R.string.media_library_imported);
        }
        if ("ALREADY_PRESENT".equals(result)) return getString(R.string.media_library_already_present);
        return getString(R.string.media_library_rejected, result);
    }

    private void showManagement() {
        List<Choice> options = new ArrayList<>();
        if (scoped()) {
            options.add(new Choice(scope.messageKey == null ? R.string.media_library_associate
                    : R.string.media_library_associate_message, this::associate));
        } else {
            options.add(new Choice(trash ? R.string.media_library_title : R.string.media_library_trash, () -> {
                trash = !trash;
                work(() -> {}, false);
            }));
            options.add(new Choice(R.string.media_library_quota, this::chooseQuota));
            options.add(new Choice(R.string.media_library_verify, this::verify));
            options.add(new Choice(R.string.media_library_create_backup, () ->
                    document(Intent.ACTION_CREATE_DOCUMENT, "application/octet-stream", "InstaEclipse.ielib", BACKUP)));
            options.add(new Choice(R.string.media_library_restore_backup, () ->
                    document(Intent.ACTION_OPEN_DOCUMENT, "*/*", null, RESTORE)));
        }
        if (!scoped() && !pending) {
            options.add(new Choice(R.string.media_library_pending_import, () -> work(() -> {
                ImportRequest saved = batch.pendingScope();
                post(() -> {
                    if (saved == null || saved.threadKey.isEmpty()) {
                        detail(getString(R.string.media_library_no_pending_import));
                        return;
                    }
                    startActivity(new Intent(this, MediaLibraryActivity.class)
                            .putExtra("resume_from_companion", true)
                            .putExtra(ConversationGalleryProvider.PACKAGE, saved.packageName)
                            .putExtra(ConversationGalleryProvider.ACCOUNT, saved.accountKey)
                            .putExtra(ConversationGalleryProvider.THREAD, saved.threadKey)
                            .putExtra(ConversationGalleryProvider.MESSAGE, saved.messageKey)
                            .putExtra(ConversationGalleryProvider.FROM_ME, saved.fromMe));
                });
            }, false)));
        }
        if (!pending && !trash && scope.messageKey == null) {
            options.add(new Choice(R.string.media_library_import_folder,
                    () -> launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_EXPORT_FOLDER)));
        }
        options.add(new Choice(R.string.media_library_coverage_action, this::showDirectCoverage));
        options.add(new Choice(R.string.media_library_last_report, () -> work(() -> {
            ImportBatch.Result result = batch.last(scope);
            post(() -> {
                if (result == null) detail(getString(R.string.media_library_no_report));
                else showCoverage(result);
            });
        }, false)));
        if (pending) options.add(new Choice(R.string.media_library_discard_import, () ->
                new AlertDialog.Builder(this).setMessage(R.string.media_library_discard_import_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) ->
                                work(() -> batch.discard(scope), false)).show()));
        choices(options);
    }

    private void associate() {
        work(() -> {
            List<LibraryItem> candidates = new ArrayList<>();
            for (LibraryItem item : library.listAll(false)) {
                if (MediaLibrary.isEligibleForAssociation(item, scope) && item.isAvailable()) candidates.add(item);
            }
            post(() -> {
                if (candidates.isEmpty()) {
                    toast(R.string.media_library_empty);
                    return;
                }
                String[] labels = new String[candidates.size()];
                for (int i = 0; i < labels.length; i++) {
                    LibraryItem item = candidates.get(i);
                    labels[i] = getString(item.isVideo() ? R.string.media_library_video : R.string.media_library_photo)
                            + " · " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(item.importedAt))
                            + " · " + item.sha256.substring(0, 8);
                }
                new AlertDialog.Builder(this).setTitle(scope.messageKey == null ? R.string.media_library_associate
                                : R.string.media_library_associate_message)
                        .setItems(labels, (dialog, index) -> {
                            LibraryItem item = candidates.get(index);
                            // Let the user identify the exact copy visually before linking it.
                            new AlertDialog.Builder(this).setMessage(labels[index])
                                    .setNeutralButton(R.string.media_library_preview, (d, w) ->
                                            openItems(java.util.Collections.singletonList(item.uuid), 0))
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(scope.messageKey == null ? R.string.media_library_associate
                                            : R.string.media_library_associate_message, (d, w) -> work(() -> {
                                        boolean linked = scope.messageKey == null
                                                ? library.associate(item.uuid, scope.packageName, scope.accountKey, scope.threadKey)
                                                : library.associateMessage(item.uuid, scope);
                                        post(() -> toast(linked ? R.string.media_library_associated
                                                : R.string.media_library_already_present));
                                    }, false)).show();
                        }).show();
            });
        }, false);
    }

    private void chooseQuota() {
        int[] amounts = {1, 4, 8, 16, 32, 64, 128, 256};
        String[] labels = new String[amounts.length];
        for (int i = 0; i < amounts.length; i++) labels[i] = getString(R.string.media_library_gib, amounts[i]);
        new AlertDialog.Builder(this).setTitle(R.string.media_library_quota)
                .setItems(labels, (dialog, which) -> work(
                        () -> library.setQuotaBytes(amounts[which] * MediaLibrary.GIB), false)).show();
    }

    private void verify() {
        work(() -> {
            library.reconcile(true);
            LibraryIntegrity.Report report = LibraryIntegrity.inspect(library, false);
            post(() -> new AlertDialog.Builder(this).setTitle(R.string.media_library_verify)
                    .setMessage(getString(R.string.media_library_integrity, report.items, report.blobs,
                            report.recordsWithoutFile.size(), report.damaged.size(), report.quarantined.size(),
                            report.ambiguous.size(), report.unknownOrigin, report.unlinked, report.stagingFiles))
                    .setPositiveButton(android.R.string.ok, null).show());
        }, false);
    }

    @Override public void onOpen(int position) {
        if (busy || !grid.items().get(position).isAvailable()) return;
        int start = Math.max(0, position - 100);
        int end = Math.min(grid.items().size(), position + 101);
        List<String> items = new ArrayList<>();
        for (LibraryItem item : grid.items().subList(start, end)) items.add(item.uuid);
        openItems(items, position - start);
    }

    private void openItems(List<String> items, int position) {
        startActivity(new Intent(this, LibraryViewerActivity.class)
                .putStringArrayListExtra(LibraryViewerActivity.EXTRA_IDS, new ArrayList<>(items))
                .putExtra(LibraryViewerActivity.EXTRA_INDEX, position));
    }

    @Override public void onLongPress(int position) {
        if (busy) return;
        LibraryItem item = grid.items().get(position);
        List<Choice> options = new ArrayList<>();
        if (!item.isTrashed()) {
            if (item.isAvailable()) options.add(new Choice(R.string.media_library_export, () -> export(item)));
            options.add(new Choice(R.string.media_library_move_trash,
                    () -> work(() -> library.trash(item.uuid), false)));
        } else {
            options.add(new Choice(R.string.media_library_restore_item,
                    () -> work(() -> library.restoreFromTrash(item.uuid), false)));
            options.add(new Choice(R.string.media_library_purge, () -> new AlertDialog.Builder(this)
                    .setMessage(R.string.media_library_purge_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, index) ->
                            work(() -> library.purge(item.uuid), false)).show()));
        }
        choices(options);
    }

    private void export(LibraryItem item) {
        String filename = "InstaEclipse-" + item.uuid + "." + extension(item.mimeType);
        if (Build.VERSION.SDK_INT < 29) {
            exportUuid = item.uuid;
            document(Intent.ACTION_CREATE_DOCUMENT, item.mimeType, filename, EXPORT);
            return;
        }
        new AlertDialog.Builder(this).setMessage(R.string.media_library_export_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.media_library_export, (dialog, index) -> work(() -> {
                    LibraryItem current = requireAvailable(item.uuid);
                    try (InputStream in = library.blobs().open(current.sha256)) {
                        GallerySaver.save(getApplicationContext(), in, current.mimeType, filename,
                                "normal".equals(current.origin) ? "DCIM/Instagram/" : "DCIM/Instagram Library/");
                    }
                    post(() -> toast(R.string.media_library_exported));
                }, false)).show();
    }

    private LibraryItem requireAvailable(String uuid) throws LibraryException {
        LibraryItem item = library.get(uuid);
        if (item == null || !item.isAvailable() || item.isTrashed()
                || scoped() && !(scope.packageName.equals(item.packageName)
                && scope.accountKey.equals(item.accountKey) && scope.threadKey.equals(item.threadKey))) {
            throw new LibraryException(LibraryException.Code.NOT_FOUND, "Media is unavailable");
        }
        return item;
    }

    private static String extension(String mime) {
        switch (mime) {
            case "image/jpeg": return "jpg";
            case "video/quicktime": return "mov";
            case "video/3gpp": return "3gp";
            default: return mime.substring(mime.indexOf('/') + 1);
        }
    }

    private void document(String action, String mime, String filename, int request) {
        Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
        if (filename != null) intent.putExtra(Intent.EXTRA_TITLE, filename);
        launch(intent, request);
    }

    private OutputStream output(Uri uri) throws java.io.IOException {
        OutputStream out = getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new FileNotFoundException();
        return out;
    }

    private void password(Uri uri, boolean restore) {
        if (scoped()) return;
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(R.string.media_library_password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(restore
                        ? R.string.media_library_restore_backup : R.string.media_library_create_backup)
                .setView(password).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int length = password.length();
            if (length == 0) {
                password.setError(getString(R.string.media_library_password));
                return;
            }
            char[] secret = new char[length];
            password.getText().getChars(0, length, secret, 0);
            password.getText().clear();
            dialog.dismiss();
            if (!work(() -> {
                try {
                    LibraryBackup backup = new LibraryBackup(library);
                    if (restore) {
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new FileNotFoundException();
                            int count = backup.restore(in, secret).itemsRestored;
                            post(() -> detail(getString(R.string.media_library_restored, count)));
                        }
                    } else {
                        try (OutputStream out = output(uri)) {
                            LibraryBackup.ExportReport report = backup.export(out, secret);
                            post(() -> detail(getString(R.string.media_library_backed_up,
                                    report.items, report.skippedUnavailable)));
                        }
                    }
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }, false)) Arrays.fill(secret, '\0');
        }));
        dialog.show();
    }

    private void showDirectCoverage() {
        work(() -> {
            ps.reso.instaeclipse.library.core.DirectCoverage coverage =
                    new ps.reso.instaeclipse.library.core.DirectCoverage(library);
            String host = "com.instagram.android";
            ps.reso.instaeclipse.library.core.DirectCoverage.Report report = scoped()
                    ? coverage.report(scope.packageName, scope.accountKey, scope.threadKey) : coverage.reportAll(host);
            int kept = 0, refused = 0, ambiguous = 0, viewOnce = 0, damaged = 0, trashedCopies = 0;
            for (ps.reso.instaeclipse.library.core.DirectCoverage.ThreadReport thread : report.threads) {
                kept += thread.keptAvailable;
                damaged += thread.keptDamaged;
                trashedCopies += thread.keptTrashed;
                refused += thread.refused;
                ambiguous += thread.ambiguous;
                viewOnce += thread.viewOnce;
            }
            String body = getString(R.string.media_library_coverage_body, report.threads.size(), report.threadsComplete,
                    report.discovered, kept, report.unavailable, viewOnce, refused, ambiguous,
                    report.pending + report.failed, damaged, trashedCopies);
            post(() -> {
                boolean running = ps.reso.instaeclipse.library.android.DirectConsolidationWorker.isRunning();
                new AlertDialog.Builder(this).setTitle(R.string.media_library_coverage_title).setMessage(body)
                        .setNegativeButton(android.R.string.ok, null)
                        .setPositiveButton(running ? R.string.media_library_coverage_pause : R.string.media_library_coverage_run,
                                (dialog, which) -> {
                                    if (running) ps.reso.instaeclipse.library.android.DirectConsolidationWorker.requestPause(this);
                                    else ps.reso.instaeclipse.library.android.DirectConsolidationWorker.kick(this, true, true);
                                })
                        .show();
            });
        }, false);
    }

    private void detail(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    private void choices(List<Choice> options) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(options.get(i).label);
        new AlertDialog.Builder(this).setItems(labels, (dialog, which) -> options.get(which).action.run()).show();
    }

    private boolean work(Job action, boolean cancellable) {
        if (busy) return false;
        busy = true;
        add.setText(cancellable ? R.string.media_library_pause : R.string.media_library_import);
        add.setEnabled(cancellable);
        manage.setEnabled(false);
        status.setText(R.string.media_library_working);
        try {
            IO.execute(() -> {
                try {
                    action.run();
                } catch (Exception failure) {
                    post(() -> showError(failure));
                }
                try {
                    boolean waiting = batch.pending(scope);
                    List<LibraryItem> items = visibleItems();
                    String usage = library == null ? null : getString(R.string.media_library_usage,
                            items.size(), Formatter.formatFileSize(this, library.usedBytes()),
                            Formatter.formatFileSize(this, library.quotaBytes()));
                    post(() -> showItems(items, usage, waiting));
                } catch (Exception failure) {
                    post(() -> {
                        busy = false;
                        showError(failure);
                        manage.setEnabled(library != null);
                    });
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            busy = false;
            manage.setEnabled(library != null);
            add.setEnabled(library != null);
            status.setText(R.string.media_library_failure);
            return false;
        }
    }

    private List<LibraryItem> visibleItems() {
        if (library == null) return java.util.Collections.emptyList();
        if (scope.messageKey != null) {
            LibraryItem copy = library.lookup(scope.packageName, scope.accountKey, scope.threadKey,
                    scope.messageKey, scope.fromMe);
            return copy == null || !copy.isAvailable() ? java.util.Collections.emptyList()
                    : java.util.Collections.singletonList(copy);
        }
        return scoped() ? library.listChat(scope.packageName, scope.accountKey, scope.threadKey)
                : library.listAll(trash);
    }

    private int libraryTitle() {
        if (scope.messageKey != null) return R.string.media_library_message_title;
        if (scoped()) return R.string.media_library_chat_title;
        return trash ? R.string.media_library_trash : R.string.media_library_title;
    }

    private void showItems(List<LibraryItem> items, String usage, boolean waiting) {
        pending = waiting;
        busy = false;
        if (grid != null) grid.submit(items);
        setTitle(libraryTitle());
        status.setText(usage == null ? getString(R.string.media_library_failure)
                : items.isEmpty() ? getString(R.string.media_library_empty) + "\n" + usage : usage);
        manage.setEnabled(grid != null);
        add.setEnabled(grid != null && !trash);
        add.setText(waiting ? R.string.media_library_resume : R.string.media_library_import);
        Runnable result = pendingResult;
        pendingResult = null;
        if (result != null && library != null) result.run();
    }

    private void showError(Exception failure) {
        if (failure instanceof LibraryException) {
            LibraryException.Code code = ((LibraryException) failure).code;
            if (code == LibraryException.Code.QUOTA_EXCEEDED) {
                toast(R.string.media_library_full);
            } else if (code == LibraryException.Code.INSUFFICIENT_STORAGE) {
                toast(R.string.media_library_no_space);
            } else if (code == LibraryException.Code.BACKUP_INVALID) {
                toast(R.string.media_library_bad_backup);
            } else {
                detail(getString(R.string.media_library_error_code, code.name()));
            }
        } else toast(R.string.media_library_failure);
    }

    private void post(Runnable message) {
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing()) message.run();
        });
    }

    private void toast(int text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private interface Job { void run() throws Exception; }

    private static final class Choice {
        final int label;
        final Runnable action;

        Choice(int label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }
}
