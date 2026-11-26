package ps.reso.instaeclipse.library.core;

import java.util.Map;

/** Catalog metadata for one kept media item. The bytes live in {@link BlobStore} under {@link #sha256}. */
public final class LibraryItem {
    public final long id;
    public final String uuid;
    public final long accountId;
    public final String packageName;
    public final String accountKey;
    public final String threadKey;
    public final String messageKey;
    public final boolean fromMe;
    public final String mediaKind;
    public final String mimeType;
    public final String sha256;
    public final long sizeBytes;
    public final long messageTimestamp;
    public final long importedAt;
    public final String origin;
    public final String caption;
    public final long trashedAt;
    public final String blobState;

    LibraryItem(Map<String, Object> row) {
        id = Sql.Rows.getLong(row, "id");
        uuid = Sql.Rows.getString(row, "uuid");
        accountId = Sql.Rows.getLong(row, "account_id");
        packageName = Sql.Rows.getString(row, "package_name");
        accountKey = Sql.Rows.getString(row, "account_key");
        threadKey = Sql.Rows.getString(row, "thread_key");
        messageKey = Sql.Rows.getString(row, "message_key");
        fromMe = Sql.Rows.getLong(row, "from_me") != 0;
        mediaKind = Sql.Rows.getString(row, "media_kind");
        mimeType = Sql.Rows.getString(row, "mime_type");
        sha256 = Sql.Rows.getString(row, "sha256");
        sizeBytes = Sql.Rows.getLong(row, "size_bytes");
        messageTimestamp = Sql.Rows.getLong(row, "message_timestamp");
        importedAt = Sql.Rows.getLong(row, "imported_at");
        origin = Sql.Rows.getString(row, "origin");
        caption = Sql.Rows.getString(row, "caption");
        trashedAt = Sql.Rows.getLong(row, "trashed_at");
        blobState = Sql.Rows.getString(row, "state");
    }

    public boolean isTrashed() {
        return trashedAt > 0;
    }

    public boolean isAvailable() {
        return "ok".equals(blobState);
    }

    public boolean isVideo() {
        return MediaSniffer.KIND_VIDEO.equals(mediaKind);
    }
}
