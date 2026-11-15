package ps.reso.instaeclipse.mods.extras;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class NotificationGroupCountsTest {
    @Test public void firstPostIsOneAndSecondIsTwo() {
        Map<String,String> byId = new HashMap<>();
        byId.put("user:1", "group");
        assertEquals(Integer.valueOf(1), NotificationGroupCounts.count(byId).get("group"));
        byId.put("user:2", "group");
        assertEquals(Integer.valueOf(2), NotificationGroupCounts.count(byId).get("group"));
    }

    @Test public void activeAndPendingWithSameIdentityCountOnce() {
        Map<String,String> byId = new HashMap<>();
        byId.put("tag:7", "old"); // active record
        byId.put("tag:7", "new"); // newer pending record replaces it
        assertEquals(1, NotificationGroupCounts.count(byId).size());
        assertEquals(Integer.valueOf(1), NotificationGroupCounts.count(byId).get("new"));
        assertNull(NotificationGroupCounts.count(byId).get("old"));
    }

    @Test public void repostWithSameIdDoesNotIncreaseCount() {
        Map<String,String> byId = new HashMap<>();
        byId.put("same:1", "one");
        byId.put("same:1", "one");
        assertEquals(Integer.valueOf(1), NotificationGroupCounts.count(byId).get("one"));
    }

    @Test public void groupsRemainIsolated() {
        Map<String,String> byId = new HashMap<>();
        byId.put("1", "a");
        byId.put("2", "b");
        byId.put("3", "a");
        assertEquals(Map.of("a",2,"b",1), NotificationGroupCounts.count(byId));
    }
}
