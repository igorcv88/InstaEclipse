package ps.reso.instaeclipse.mods.extras;

import java.util.HashMap;
import java.util.Map;

/** Count notifications by group after deduplicating them by (tag, id). */
final class NotificationGroupCounts {
    private NotificationGroupCounts() {}

    static <K> Map<String, Integer> count(Map<K, String> groupsByIdentity) {
        Map<String, Integer> counts = new HashMap<>();
        for (String group : groupsByIdentity.values()) {
            if (group != null) counts.merge(group, 1, Integer::sum);
        }
        return counts;
    }
}
