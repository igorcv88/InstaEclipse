package ps.reso.instaeclipse.mods.ghost;

/** Preserve the read-receipt decision made when Instagram queued the mutation. */
final class SeenDispatchPolicy {
    private SeenDispatchPolicy() {}

    static boolean shouldSend(Boolean queuedDecision, boolean ghostSeen, boolean whitelisted) {
        // When a mutation was observed at enqueue time, later toggles must not change it.
        // Persisted/unknown mutations fall back to the current privacy setting.
        return queuedDecision != null ? queuedDecision : !ghostSeen || whitelisted;
    }
}
