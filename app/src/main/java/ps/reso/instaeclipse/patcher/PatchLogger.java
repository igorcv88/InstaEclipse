package ps.reso.instaeclipse.patcher;

/** Receives a human-readable line for each step of the patch pipeline. */
public interface PatchLogger {
    void log(String line);

    /**
     * Pipeline progress as a 0..100 percentage. Plain loggers (e.g. a bare lambda) ignore it; the
     * patch screen uses it to drive the determinate progress bar.
     */
    default void progress(int percent) {
    }
}
