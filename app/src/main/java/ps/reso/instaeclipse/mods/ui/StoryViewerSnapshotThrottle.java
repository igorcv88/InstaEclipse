package ps.reso.instaeclipse.mods.ui;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Emits the first observation immediately and coalesces subsequent observations
 * into one trailing delivery of the newest snapshot per interval.
 *
 * Snapshots are immutable metadata captured at hook time: delayed work must never
 * retain the mutable Instagram adapter list or reference a live viewer record.
 */
final class StoryViewerSnapshotThrottle<T> {
    interface Scheduler {
        void postDelayed(Runnable action, long delayMillis);
    }

    private final long intervalMillis;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Consumer<T> receiver;
    private boolean emitted;
    private long lastEmittedAt;
    private boolean trailingScheduled;
    private T latest;

    StoryViewerSnapshotThrottle(long intervalMillis, LongSupplier clock,
                                 Scheduler scheduler, Consumer<T> receiver) {
        if (intervalMillis <= 0 || clock == null || scheduler == null || receiver == null)
            throw new IllegalArgumentException("A positive interval and callbacks are required");
        this.intervalMillis = intervalMillis;
        this.clock = clock;
        this.scheduler = scheduler;
        this.receiver = receiver;
    }

    void offer(T sample) {
        if (sample == null) return;
        T immediate = null;
        synchronized (this) {
            long now = clock.getAsLong();
            if (!emitted || (!trailingScheduled && now - lastEmittedAt >= intervalMillis)) {
                emitted = true;
                lastEmittedAt = now;
                immediate = sample;
            } else {
                latest = sample;
                if (!trailingScheduled) {
                    trailingScheduled = true;
                    long due = lastEmittedAt + intervalMillis;
                    scheduler.postDelayed(this::flush, Math.max(1, due - now));
                }
            }
        }
        if (immediate != null) receiver.accept(immediate);
    }

    private void flush() {
        T sample;
        synchronized (this) {
            if (latest == null) {
                trailingScheduled = false;
                return;
            }
            long now = clock.getAsLong();
            long remaining = lastEmittedAt + intervalMillis - now;
            if (remaining > 0) {
                scheduler.postDelayed(this::flush, remaining);
                return;
            }
            sample = latest;
            latest = null;
            trailingScheduled = false;
            emitted = true;
            lastEmittedAt = now;
        }
        receiver.accept(sample);
    }
}
