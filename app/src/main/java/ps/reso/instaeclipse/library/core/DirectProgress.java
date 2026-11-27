package ps.reso.instaeclipse.library.core;

/** Per-part pipeline events. An observer receives identifiers only in-process. */
@FunctionalInterface
public interface DirectProgress {
    DirectProgress NONE = (account, thread, part, stage, detail) -> { };

    void event(String account, String thread, String part, String stage, String detail);
}
