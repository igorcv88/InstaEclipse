package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class StoryViewerListOrderTest {
    private static final class Viewer {
        final String username;
        Viewer(String username) { this.username = username; }
    }
    private static final class Header {
        final String name;
        Header(String name) { this.name = name; }
    }

    @Test public void originalOrderWithoutQueryIsUntouched() {
        Viewer z = new Viewer("zeta"), a = new Viewer("alpha");
        List<Object> rows = List.of(z, a);
        assertSame(rows, StoryViewerListOrder.apply(rows, Viewer.class, "", 0,
                row -> ((Viewer) row).username));
    }

    @Test public void alphabeticSortingIsScopedToEachViewerSection() {
        Header one = new Header("section one"), two = new Header("section two");
        Viewer z = new Viewer("Zoe"), a = new Viewer("anna");
        Viewer y = new Viewer("Yuri"), b = new Viewer("Bia");
        List<Object> rows = List.of(one, z, a, two, y, b);
        assertEquals(List.of(one, a, z, two, b, y),
                StoryViewerListOrder.apply(rows, Viewer.class, "", 1, row -> ((Viewer) row).username));
        assertEquals(List.of(one, z, a, two, y, b), rows);
    }

    @Test public void descendingOrderAndMissingNames() {
        Viewer b = new Viewer("bia"), missing = new Viewer(null), a = new Viewer("Ana");
        assertEquals(List.of(b, a, missing),
                StoryViewerListOrder.apply(List.of(a, missing, b), Viewer.class, null, 2,
                        row -> ((Viewer) row).username));
    }

    @Test public void searchCombinesWithSortWithoutFilteringHeaders() {
        Header header = new Header("heading");
        Viewer zoe = new Viewer("zoe"), ana = new Viewer("Anabela"), andrea = new Viewer("Andrea");
        List<Object> input = List.of(header, zoe, andrea, ana);
        assertEquals(List.of(header, ana, andrea),
                StoryViewerListOrder.apply(input, Viewer.class, "an", 1,
                        row -> ((Viewer) row).username));
        assertEquals(4, input.size());
    }

    @Test public void failsClosedWithoutKnownViewerClassOrForInvalidMode() {
        Viewer z = new Viewer("Z"), a = new Viewer("A");
        List<Object> rows = List.of(z, a);
        assertSame(rows, StoryViewerListOrder.apply(rows, null, "a", 1, row -> ((Viewer) row).username));
        assertEquals(0, StoryViewerListOrder.validatedMode(77));
        assertSame(rows, StoryViewerListOrder.apply(rows, Viewer.class, null, 77,
                row -> ((Viewer) row).username));
    }

    @Test public void equalNamesRetainInstagramRelativeOrder() {
        Viewer first = new Viewer("ALICE"), second = new Viewer("alice"), last = new Viewer("bob");
        assertEquals(List.of(first, second, last),
                StoryViewerListOrder.apply(List.of(last, first, second), Viewer.class, "", 1,
                        row -> ((Viewer) row).username));
    }
}
