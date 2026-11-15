package ps.reso.instaeclipse.mods.extras;

/** Pure geometric check shared by the feed gesture gate and its unit tests. */
final class FeedVideoHitTest {
    private FeedVideoHitTest() {}

    static boolean contains(float x, float y, int left, int top, int right, int bottom) {
        return right > left && bottom > top
                && x >= left && x < right
                && y >= top && y < bottom;
    }
}
