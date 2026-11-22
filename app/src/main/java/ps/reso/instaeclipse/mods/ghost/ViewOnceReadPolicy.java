package ps.reso.instaeclipse.mods.ghost;

/**
 * Network-level safeguards for disappearing visual media only.
 *
 * A normal Direct thread's /item_seen/ request must never be suppressed by
 * the "Hide View Once Opened" switch. Shared DM read mutations are not visual-only.
 */
public final class ViewOnceReadPolicy {
    private ViewOnceReadPolicy() {}

    public static boolean blocksPath(String path) {
        if (path == null) return false;
        // Replay endpoints are independent of normal "mark thread seen" processing.
        if (path.endsWith("/item_replayed/")) return true;

        // Seen/opened operations must belong to an explicitly visual thread.
        return path.contains("/direct_v2/visual_threads/")
                && (path.endsWith("/item_seen/") || path.endsWith("/seen/"));
    }
}
