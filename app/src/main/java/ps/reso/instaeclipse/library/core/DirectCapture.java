package ps.reso.instaeclipse.library.core;

import java.io.IOException;

/**
 * Keeps bytes the Instagram client already downloaded for one message, keyed by the identity
 * read from the client's own model, and records the outcome in {@link DirectCoverage}.
 *
 * <p>This class never fetches, opens or marks anything: it only receives a stream that the
 * caller obtained from what the client already holds. Owner decision 2026-11-26: no prefetch of
 * view-once or ephemeral items; capture is passive.</p>
 */
public final class DirectCapture {
    private final MediaLibrary library;
    private final DirectCoverage coverage;

    public DirectCapture(MediaLibrary library, DirectCoverage coverage) {
        this.library = library;
        this.coverage = coverage;
    }

    public static final class Outcome {
        public final DirectCoverage.State state;
        public final LibraryItem item;
        public final String error;

        Outcome(DirectCoverage.State state, LibraryItem item, String error) {
            this.state = state;
            this.item = item;
            this.error = error;
        }
    }

    /**
     * @param source the client's bytes, or {@code null} when the client holds none now
     *               (recorded as UNAVAILABLE; a later offer can still settle it)
     */
    public Outcome capture(String packageName, String accountKey, String threadKey,
                           DirectCoverage.Discovered identity, MediaLibrary.StreamSource source) throws IOException {
        if (source == null) {
            DirectCoverage.State state = coverage.settle(packageName, accountKey, threadKey, identity,
                    DirectCoverage.State.UNAVAILABLE, null, "no_bytes");
            return new Outcome(state, null, "no_bytes");
        }
        ImportRequest request = ImportRequest.captured(packageName, accountKey, threadKey, identity.messageKey,
                identity.fromMe, identity.timestamp, identity.viewOnce);
        DirectCoverage.State outcome;
        LibraryItem item = null;
        String error = null;
        try {
            MediaLibrary.ImportResult result = library.importMedia(request, source);
            item = result.item;
            outcome = result.status == MediaLibrary.ImportStatus.ALREADY_PRESENT
                    ? DirectCoverage.State.ALREADY_PRESENT : DirectCoverage.State.IMPORTED;
            // Two different copies now claim this identity: report it, never pick one.
            if (library.lookup(packageName, accountKey, threadKey, identity.messageKey, identity.fromMe) == null) {
                outcome = DirectCoverage.State.AMBIGUOUS;
            }
        } catch (LibraryException failure) {
            error = failure.code.name();
            outcome = switch (failure.code) {
                case UNSUPPORTED_MEDIA, INCOMPLETE_MEDIA, TOO_LARGE, INVALID_REQUEST -> DirectCoverage.State.REFUSED;
                default -> DirectCoverage.State.FAILED;
            };
        } catch (IOException failure) {
            error = "IO";
            outcome = DirectCoverage.State.FAILED;
        }
        DirectCoverage.State recorded = coverage.settle(packageName, accountKey, threadKey, identity, outcome,
                item == null ? null : item.uuid, error);
        return new Outcome(recorded, item, error);
    }
}
