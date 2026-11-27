package ps.reso.instaeclipse.library.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coalesces live Direct rows until the companion acknowledges them. */
public final class DirectLiveOfferQueue {
    private static final int MAX_PENDING = 1024;
    private static final int MAX_ACKNOWLEDGED = 4096;
    private static final int MAX_ATTEMPTS = 4;

    public enum Add { QUEUED, DUPLICATE, FULL }

    public static final class Entry {
        public final DirectStoreRow row;
        private final String key;
        private final List<String> state;
        private int attempts;

        private Entry(DirectStoreRow row) {
            this.row = row;
            key = row.accountId + '|' + row.threadId + '|' + row.itemId;
            state = new ArrayList<>();
            for (DirectStoreRow.Part part : row.parts) {
                state.add(part.identity.messageKey);
                state.add(part.identity.mediaKind);
                state.add(part.identity.fromMe + ":" + part.identity.viewOnce);
                state.add(part.fetchAllowed ? part.best.url : "");
            }
        }
    }

    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private final Map<String, List<String>> acknowledged = new LinkedHashMap<>(256, 0.75f, true);

    public synchronized Add add(DirectStoreRow row) {
        Entry next = new Entry(row);
        Entry previous = pending.get(next.key);
        if (previous != null) {
            next = new Entry(previous.row.mergeObservation(row));
            if (previous.state.equals(next.state)) return Add.DUPLICATE;
        } else if (next.state.equals(acknowledged.get(next.key))) {
            return Add.DUPLICATE;
        }
        if (previous == null && pending.size() >= MAX_PENDING) return Add.FULL;

        pending.put(next.key, next);
        return Add.QUEUED;
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(pending.values());
    }

    public synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Returns the number dropped after exhausting the retry budget. */
    public synchronized int finish(List<Entry> entries, boolean accepted) {
        int exhausted = 0;
        for (Entry entry : entries) {
            if (accepted) {
                acknowledged.put(entry.key, entry.state);
                if (acknowledged.size() > MAX_ACKNOWLEDGED) {
                    acknowledged.remove(acknowledged.keySet().iterator().next());
                }
            }
            // A rebind may already have queued a newer representation while IPC was in flight.
            if (pending.get(entry.key) != entry) continue;
            if (accepted) {
                pending.remove(entry.key);
            } else if (++entry.attempts >= MAX_ATTEMPTS) {
                pending.remove(entry.key);
                exhausted++;
            }
        }
        return exhausted;
    }
}
