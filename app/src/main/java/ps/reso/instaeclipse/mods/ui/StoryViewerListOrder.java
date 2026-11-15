package ps.reso.instaeclipse.mods.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure list transformation for the story-viewer adapter. Never mutates Instagram's list and
 * never reorders headers, footers or separate viewer sections.
 *
 * Sorting and searching apply only to viewer records that Instagram has already loaded.
 * No attempt is made to infer replay/view timestamps from ranking changes.
 */
final class StoryViewerListOrder {
    static final int INSTAGRAM = 0;
    static final int NAME_AZ = 1;
    static final int NAME_ZA = 2;

    private StoryViewerListOrder() {}

    static int validatedMode(int mode) {
        return mode == NAME_AZ || mode == NAME_ZA ? mode : INSTAGRAM;
    }

    static List<?> apply(List<?> original, Class<?> viewerClass, String query, int mode,
                         Function<Object, String> username) {
        if (original == null || viewerClass == null || username == null) return original;
        mode = validatedMode(mode);
        String needle = normalized(query);
        boolean filtering = needle != null && !needle.isEmpty();
        if (!filtering && mode == INSTAGRAM) return original;

        List<Object> result = new ArrayList<>(original.size());
        for (Object row : original) {
            if (!viewerClass.isInstance(row)) {
                result.add(row);
                continue;
            }
            if (filtering) {
                String name = normalized(username.apply(row));
                if (name == null || !name.contains(needle)) continue;
            }
            result.add(row);
        }
        if (mode == INSTAGRAM) return result;

        Map<Object, String> names = new IdentityHashMap<>();
        final int direction = mode == NAME_ZA ? -1 : 1;
        Comparator<Object> compare = (a, b) -> {
            String left = cachedName(a, username, names);
            String right = cachedName(b, username, names);
            // Unknown names stay at the end for both orders, preserving their relative order.
            if (left == null) return right == null ? 0 : 1;
            if (right == null) return -1;
            return direction * left.compareTo(right);
        };

        // Never move a viewer across a non-viewer row (section/header/footer boundary).
        for (int start = 0; start < result.size();) {
            if (!viewerClass.isInstance(result.get(start))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < result.size() && viewerClass.isInstance(result.get(end))) end++;
            result.subList(start, end).sort(compare);
            start = end;
        }
        return result;
    }

    private static String cachedName(Object row, Function<Object, String> username,
                                     Map<Object, String> cache) {
        if (!cache.containsKey(row)) cache.put(row, normalized(username.apply(row)));
        return cache.get(row);
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
