package ps.reso.instaeclipse.library.android;

/** Bundle keys of the {@code direct_offer} call from the Instagram-side scanner to the module. */
final class DirectOfferContract {
    static final String METHOD = "direct_offer";
    static final String KEYS = "offer_keys";
    static final String KINDS = "offer_kinds";
    static final String URLS = "offer_urls";
    /** Two characters per part: from-me, view-once ("0"/"1"). */
    static final String FLAGS = "offer_flags";
    static final String TIMESTAMPS = "offer_timestamps";
    static final String ACCEPTED = "offer_accepted";

    private DirectOfferContract() {}
}
