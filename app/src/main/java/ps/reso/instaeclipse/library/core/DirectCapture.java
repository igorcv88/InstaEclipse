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
        return capture(packageName, accountKey, threadKey, identity, source, null, 0L, DirectProgress.NONE);
    }

    Outcome capture(String packageName, String accountKey, String threadKey,
                    DirectCoverage.Discovered identity, MediaLibrary.StreamSource source,
                    String attemptedSource, long sourceVersion, DirectProgress progress) throws IOException {
        if (source == null) {
            DirectCoverage.State state = coverage.settleTransfer(packageName, accountKey, threadKey, identity,
                    DirectCoverage.State.UNAVAILABLE, null, "no_bytes", attemptedSource, sourceVersion);
            return new Outcome(state == DirectCoverage.State.PENDING ? state : DirectCoverage.State.UNAVAILABLE,
                    null, "no_bytes");
        }
        ImportRequest request = ImportRequest.captured(packageName, accountKey, threadKey, identity.messageKey,
                identity.fromMe, identity.timestamp, identity.viewOnce);
        DirectCoverage.State outcome;
        LibraryItem item = null;
        String error = null;
        try {
            MediaLibrary.ImportResult result = library.importMedia(request, source, progress);
            item = result.item;
            progress.event(accountKey, threadKey, identity.messageKey, "persisted",
                    "status=" + result.status + " bytes=" + item.sizeBytes);
            outcome = result.status == MediaLibrary.ImportStatus.ALREADY_PRESENT
                    ? DirectCoverage.State.ALREADY_PRESENT : DirectCoverage.State.IMPORTED;
            // Two different copies now claim this identity: report it, never pick one.
            if (library.lookup(packageName, accountKey, threadKey, identity.messageKey, identity.fromMe) == null) {
                outcome = DirectCoverage.State.AMBIGUOUS;
            }
        } catch (LibraryException failure) {
            error = failure.code.name();
            // INCOMPLETE_MEDIA stays retryable: a transfer cut short looks exactly like a damaged
            // file, and a final REFUSED would lose the media for good. MAX_ATTEMPTS bounds it.
            outcome = switch (failure.code) {
                case UNSUPPORTED_MEDIA, TOO_LARGE, INVALID_REQUEST -> DirectCoverage.State.REFUSED;
                default -> DirectCoverage.State.FAILED;
            };
        } catch (DirectConsolidation.Cancelled paused) {
            // A pause is not an outcome: nothing is recorded and the attempt is not counted.
            throw paused;
        } catch (IOException failure) {
            error = "IO";
            outcome = DirectCoverage.State.FAILED;
        }
        DirectCoverage.State recorded = coverage.settleTransfer(packageName, accountKey, threadKey, identity, outcome,
                item == null ? null : item.uuid, error, attemptedSource, sourceVersion);
        return new Outcome(recorded == DirectCoverage.State.PENDING ? recorded : outcome, item, error);
    }
}
