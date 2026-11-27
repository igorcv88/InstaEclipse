package ps.reso.instaeclipse.library.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of Instagram's own local Direct cache ({@code databases/direct.db}, table
 * {@code messages}, schema 15 in 454.0.0.0.55; see docs/MEDIA_LIBRARY_DIRECT_454_INVENTORY.md).
 *
 * <p>Identity comes from the row's own columns: {@code user_id} (the account that stored it),
 * {@code thread_id} and {@code server_item_id}. The JSON in {@code message} is the client's own
 * serialization of the message model ({@code X.01hN}); it supplies the sender (direction), the
 * type, the view mode and the CDN URLs the client holds for the media. Nothing is inferred from
 * timestamps, file names or position.</p>
 */
public final class DirectStoreRow {
    /** Server types that carry photos or videos. Everything else is ignored. */
    public static final String TYPE_MEDIA = "media";
    public static final String TYPE_RAVEN = "raven_media";
    public static final String TYPE_VISUAL = "visual_media";

    public static final class Candidate {
        public final String url;
        public final int width, height;
        public final boolean video;

        Candidate(String url, int width, int height, boolean video) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.video = video;
        }
    }

    /** One photo or video of the message; carousel slides get {@code itemId:index} keys. */
    public static final class Part {
        public final DirectCoverage.Discovered identity;
        /** The single best candidate the client holds, or null when none is usable. */
        public final Candidate best;
        /**
         * Whether the module may fetch {@link #best}. Conventional and permanent items always;
         * view-once and replayable items only when they are the account's own or the account is
         * already in {@code seen_user_ids} (the user opened it in Instagram). Fetching then
         * consumes nothing. Owner decision 2026-11-27.
         */
        public final boolean fetchAllowed;

        Part(DirectCoverage.Discovered identity, Candidate best) {
            this(identity, best, !identity.viewOnce);
        }

        Part(DirectCoverage.Discovered identity, Candidate best, boolean fetchAllowed) {
            this.fetchAllowed = fetchAllowed && best != null;
            this.identity = identity;
            this.best = best;
        }
    }

    public final String accountId, threadId, itemId, messageType, senderId;
    public final boolean viewOnce;
    /** For view-once and replayable items: the account appears in {@code seen_user_ids}. */
    public final boolean seenByViewer;
    public final long timestampMs;
    public final List<Part> parts;

    private DirectStoreRow(String accountId, String threadId, String itemId, String messageType, String senderId,
                           boolean viewOnce, boolean seenByViewer, long timestampMs, List<Part> parts) {
        this.seenByViewer = seenByViewer;
        this.accountId = accountId;
        this.threadId = threadId;
        this.itemId = itemId;
        this.messageType = messageType;
        this.senderId = senderId;
        this.viewOnce = viewOnce;
        this.timestampMs = timestampMs;
        this.parts = parts;
    }

    public static boolean isMediaType(String type) {
        return TYPE_MEDIA.equals(type) || TYPE_RAVEN.equals(type) || TYPE_VISUAL.equals(type);
    }

    /**
     * Parses a row. Returns {@code null} when the identity is incomplete (no thread, id or sender)
     * or the JSON contradicts the columns: such rows are reported, never guessed.
     *
     * @param timestampMicros the row's {@code timestamp} column ({@code X.01hK.A0J()}, microseconds)
     */
    public static DirectStoreRow parse(String accountId, String threadId, String serverItemId, String messageType,
                                       long timestampMicros, String json) {
        return parse(accountId, threadId, serverItemId, messageType, timestampMicros, json, null);
    }

    /**
     * @param rewrittenMode the original view mode when InstaEclipse's own "Unlimited View Once"
     *                      option rewrote it to "permanent" in Instagram's model; null otherwise.
     *                      The item is classified by its original mode. A rewrite does not establish
     *                      ownership or that the recipient has opened it.
     */
    public static DirectStoreRow parse(String accountId, String threadId, String serverItemId, String messageType,
                                       long timestampMicros, String json, String rewrittenMode) {
        if (!ImportRequest.validKey(accountId) || !ImportRequest.validKey(threadId)
                || !ImportRequest.validKey(serverItemId) || !isMediaType(messageType) || json == null) return null;
        JSONObject message;
        try {
            message = new JSONObject(json);
        } catch (JSONException invalid) {
            return null;
        }
        String jsonId = message.optString("item_id", null);
        if (jsonId != null && !jsonId.equals(serverItemId)) return null;
        String jsonType = message.optString("item_type", null);
        if (jsonType != null && !jsonType.equals(messageType)) return null;
        String sender = message.optString("user_id", null);
        if (!ImportRequest.validKey(sender)) return null;
        boolean fromMe = accountId.equals(sender);
        long timestampMs = Math.max(0, timestampMicros / 1000L);

        JSONObject visual = message.optJSONObject(TYPE_VISUAL);
        String mode = visual != null ? visual.optString("view_mode", message.optString("view_mode", ""))
                : message.optString("view_mode", "");
        if (ephemeralMode(rewrittenMode)) mode = rewrittenMode;
        boolean visualType = !TYPE_MEDIA.equals(messageType);
        // The host can project a rewritten visual message as item_type=media.
        boolean viewOnce = ephemeralMode(mode) || (visualType && !"permanent".equals(mode));
        JSONObject media = visualType ? (visual == null ? null : visual.optJSONObject("media"))
                : message.optJSONObject(TYPE_MEDIA);
        if (media == null && visualType) media = message.optJSONObject(TYPE_RAVEN);

        boolean seen = false;
        if (viewOnce && visual != null) {
            JSONArray seenBy = visual.optJSONArray("seen_user_ids");
            if (seenBy != null) {
                for (int i = 0; i < seenBy.length(); i++) {
                    if (accountId.equals(seenBy.optString(i, null))) seen = true;
                }
            }
        }
        List<Part> parts = new ArrayList<>();
        if (media != null) {
            JSONArray carousel = media.optJSONArray("carousel_media");
            if (carousel != null && carousel.length() > 0) {
                for (int i = 0; i < carousel.length() && i < 20; i++) {
                    JSONObject slide = carousel.optJSONObject(i);
                    if (slide == null) continue;
                    parts.add(part(serverItemId + ":" + i, fromMe, viewOnce, fromMe || seen,
                            timestampMs, slide));
                }
            } else {
                parts.add(part(serverItemId, fromMe, viewOnce, fromMe || seen, timestampMs, media));
            }
        } else {
            parts.add(new Part(new DirectCoverage.Discovered(serverItemId, fromMe, "unknown", viewOnce, timestampMs), null));
        }
        return new DirectStoreRow(accountId, threadId, serverItemId, messageType, sender, viewOnce, seen, timestampMs,
                Collections.unmodifiableList(parts));
    }

    private static boolean ephemeralMode(String mode) {
        return "once".equals(mode) || "replayable".equals(mode) || "allow_replay".equals(mode);
    }

    private static Part part(String key, boolean fromMe, boolean viewOnce, boolean openedOrOwn, long timestampMs,
                             JSONObject media) {
        int type = media.optInt("media_type", 0);
        Candidate video = best(media.optJSONArray("video_versions"), true);
        JSONObject versions = media.optJSONObject("image_versions2");
        Candidate image = best(versions == null ? null : versions.optJSONArray("candidates"), false);
        boolean isVideo = type == 2 || (type == 0 && video != null);
        Candidate chosen = isVideo ? video : image;
        String kind = chosen == null ? "unknown" : (isVideo ? MediaSniffer.KIND_VIDEO : MediaSniffer.KIND_IMAGE);
        return new Part(new DirectCoverage.Discovered(key, fromMe, kind, viewOnce, timestampMs), chosen,
                !viewOnce || openedOrOwn);
    }

    /** Largest candidate by pixel count with an https URL on an Instagram or Facebook CDN. */
    static Candidate best(JSONArray versions, boolean video) {
        if (versions == null) return null;
        Candidate best = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            String url = version.optString("url", null);
            if (!isCdnUrl(url)) continue;
            Candidate candidate = new Candidate(url, version.optInt("width", 0), version.optInt("height", 0), video);
            if (best == null || (long) candidate.width * candidate.height > (long) best.width * best.height) {
                best = candidate;
            }
        }
        return best;
    }

    /** Only the client's media CDNs: never Instagram API hosts, which could change message state. */
    public static boolean isCdnUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        int end = url.indexOf('/', 8);
        String host = (end < 0 ? url.substring(8) : url.substring(8, end)).toLowerCase(java.util.Locale.ROOT);
        if (host.contains("@") || host.contains(":")) return false;
        return host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net");
    }

    /** Splits rows into IPC batches of at most {@code maxParts} parts, never splitting a row. */
    public static List<List<DirectStoreRow>> batches(List<DirectStoreRow> rows, int maxParts) {
        List<List<DirectStoreRow>> batches = new ArrayList<>();
        List<DirectStoreRow> batch = new ArrayList<>();
        int parts = 0;
        for (DirectStoreRow row : rows) {
            if (!batch.isEmpty() && parts + row.parts.size() > maxParts) {
                batches.add(batch);
                batch = new ArrayList<>();
                parts = 0;
            }
            batch.add(row);
            parts += row.parts.size();
        }
        if (!batch.isEmpty()) batches.add(batch);
        return batches;
    }
}
