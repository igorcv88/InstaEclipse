package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import static org.junit.Assert.*;

public class StoryViewerSnapshotThrottleTest {
    private static final class FakeTime implements LongSupplier {
        long now;
        @Override public long getAsLong() { return now; }
    }
    private static final class FakeScheduler implements StoryViewerSnapshotThrottle.Scheduler {
        final FakeTime time;
        final List<Task> tasks = new ArrayList<>();
        FakeScheduler(FakeTime time) { this.time = time; }
        private static final class Task {
            final long due;
            final Runnable runnable;
            Task(long due, Runnable runnable) { this.due = due; this.runnable = runnable; }
        }
        @Override public void postDelayed(Runnable runnable, long delay) {
            tasks.add(new Task(time.now + delay, runnable));
        }
        void advanceTo(long millis) {
            time.now = millis;
            for (int i = 0; i < tasks.size();) {
                Task item = tasks.get(i);
                if (item.due <= millis) {
                    tasks.remove(i);
                    item.runnable.run();
                } else i++;
            }
        }
    }

    @Test public void emptyInitialSnapshotStillDeliversNewViewersAfterThrottle() {
        FakeTime clock = new FakeTime();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<String> emitted = new ArrayList<>();
        StoryViewerSnapshotThrottle<String> probe =
                new StoryViewerSnapshotThrottle<>(2500, clock, scheduler, emitted::add);

        probe.offer("empty"); // first synchronous observation
        clock.now = 100;
        probe.offer("loaded");
        assertEquals(List.of("empty"), emitted);
        scheduler.advanceTo(2500);
        assertEquals(List.of("empty", "loaded"), emitted);
    }

    @Test public void rapidUpdatesCoalesceToLatestNotEarliest() {
        FakeTime clock = new FakeTime();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<String> emitted = new ArrayList<>();
        StoryViewerSnapshotThrottle<String> probe =
                new StoryViewerSnapshotThrottle<>(2500, clock, scheduler, emitted::add);

        probe.offer("first");
        clock.now = 200; probe.offer("stale");
        clock.now = 1000; probe.offer("latest");
        assertEquals(1, scheduler.tasks.size());
        scheduler.advanceTo(2499);
        assertEquals(List.of("first"), emitted);
        scheduler.advanceTo(2500);
        assertEquals(List.of("first", "latest"), emitted);
    }

    @Test public void aLateTimerIncludesTheNewestBuilderCall() {
        FakeTime clock = new FakeTime();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<String> emitted = new ArrayList<>();
        StoryViewerSnapshotThrottle<String> probe =
                new StoryViewerSnapshotThrottle<>(2500, clock, scheduler, emitted::add);

        probe.offer("initial");
        clock.now = 100;
        probe.offer("old");
        clock.now = 3100;
        probe.offer("new");
        scheduler.advanceTo(3100);
        assertEquals(List.of("initial", "new"), emitted);
    }

    @Test public void intervalsContinueAfterTrailingDelivery() {
        FakeTime clock = new FakeTime();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<String> emitted = new ArrayList<>();
        StoryViewerSnapshotThrottle<String> probe =
                new StoryViewerSnapshotThrottle<>(2500, clock, scheduler, emitted::add);

        probe.offer("initial");
        clock.now = 100; probe.offer("second");
        scheduler.advanceTo(2500);
        clock.now = 2700; probe.offer("third");
        scheduler.advanceTo(4999);
        assertEquals(List.of("initial", "second"), emitted);
        scheduler.advanceTo(5000);
        assertEquals(List.of("initial", "second", "third"), emitted);
    }

    @Test public void anEarlyTimerReschedulesUntilDue() {
        FakeTime clock = new FakeTime();
        FakeScheduler scheduler = new FakeScheduler(clock);
        List<String> emitted = new ArrayList<>();
        StoryViewerSnapshotThrottle<String> probe =
                new StoryViewerSnapshotThrottle<>(2500, clock, scheduler, emitted::add);
        probe.offer("initial");
        clock.now = 100; probe.offer("updated");
        Runnable task = scheduler.tasks.remove(0).runnable;
        task.run(); // scheduler woke too early
        assertEquals(List.of("initial"), emitted);
        assertEquals(1, scheduler.tasks.size());
        scheduler.advanceTo(2500);
        assertEquals(List.of("initial", "updated"), emitted);
    }
}
