package ps.reso.instaeclipse.library.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Discovers candidates inside a user-selected, extracted export. Admission still validates bytes. */
public final class ExportFolder {
    private ExportFolder() {}

    public static final int MAX_CANDIDATES = 5_000;
    private static final int MAX_DOCUMENTS = 50_000;
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME};

    public record Selection(Uri tree, List<Uri> candidates, int visited, int skipped) {}

    public static Selection discover(ContentResolver resolver, Uri tree) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)) {
            throw new IOException("Expected a document tree");
        }
        List<Uri> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> folders = new ArrayDeque<>();
        String root = DocumentsContract.getTreeDocumentId(tree);
        seen.add(root);
        folders.add(root);
        int visited = 0;
        int skipped = 0;
        while (!folders.isEmpty()) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folders.remove());
            try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
                if (cursor == null) throw new IOException("Could not list export folder");
                while (cursor.moveToNext()) {
                    if (++visited > MAX_DOCUMENTS) throw new IOException("Export folder is too large");
                    String id = cursor.getString(0);
                    String mime = cursor.getString(1);
                    if (id == null || !seen.add(id)) {
                        skipped++;
                    } else if (Document.MIME_TYPE_DIR.equals(mime)) {
                        folders.add(id);
                    } else if (candidate(mime, cursor.getString(2))) {
                        if (candidates.size() >= MAX_CANDIDATES) throw new IOException("Too many media candidates");
                        candidates.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    } else {
                        skipped++;
                    }
                }
            }
        }
        candidates.sort(java.util.Comparator.comparing(Uri::toString));
        return new Selection(tree, candidates, visited, skipped);
    }

    static boolean candidate(String mime, String label) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) return true;
        if (label == null || (mime != null && !"application/octet-stream".equals(mime))) return false;
        return label.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|gif|webp|hei[cf]|avif|mp4|mov|webm|3gp)$");
    }
}
