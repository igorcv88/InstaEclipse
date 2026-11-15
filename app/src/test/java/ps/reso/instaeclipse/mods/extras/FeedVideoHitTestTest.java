package ps.reso.instaeclipse.mods.extras;

import org.junit.Test;
import static org.junit.Assert.*;

public class FeedVideoHitTestTest {
    @Test public void ignoresControlsOutsideActualVideoEvenInsideScreenMiddleBand() {
        assertTrue(FeedVideoHitTest.contains(220, 480, 100, 200, 800, 900));
        assertFalse(FeedVideoHitTest.contains(900, 480, 100, 200, 800, 900));
    }

    @Test public void boundaryAndEmptyRectsFailOpen() {
        assertTrue(FeedVideoHitTest.contains(100, 200, 100, 200, 500, 700));
        assertFalse(FeedVideoHitTest.contains(500, 450, 100, 200, 500, 700));
        assertFalse(FeedVideoHitTest.contains(300, 700, 100, 200, 500, 700));
        assertFalse(FeedVideoHitTest.contains(100, 100, 0, 0, 0, 1000));
    }

    @Test public void offsetVideoBoundsUseRawScreenCoordinates() {
        assertFalse(FeedVideoHitTest.contains(120, 195, 80, 200, 950, 1200));
        assertTrue(FeedVideoHitTest.contains(120, 201, 80, 200, 950, 1200));
    }
}
