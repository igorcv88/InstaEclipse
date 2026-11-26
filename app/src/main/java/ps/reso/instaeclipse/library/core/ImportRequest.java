package ps.reso.instaeclipse.library.core;

/** Scope for a user-supplied file. An empty thread means a local, unassociated import. */
public final class ImportRequest {
    public static final String LOCAL_PACKAGE = "ps.reso.instaeclipse";
    public static final String LOCAL_ACCOUNT = "local";

    public final String packageName, accountKey, threadKey, messageKey, origin, caption;
    public final boolean fromMe;
    public final long messageTimestamp;

    ImportRequest(String packageName, String accountKey, String threadKey, String messageKey,
                  boolean fromMe, long messageTimestamp, String origin, String caption) {
        this.packageName = packageName;
        this.accountKey = accountKey;
        this.threadKey = threadKey;
        this.messageKey = messageKey;
        this.fromMe = fromMe;
        this.messageTimestamp = messageTimestamp;
        this.origin = origin;
        this.caption = caption;
    }

    public static ImportRequest localFile() {
        return new ImportRequest(LOCAL_PACKAGE, LOCAL_ACCOUNT, "", null, false, 0, "imported", null);
    }

    public static ImportRequest forConversation(String packageName, String accountKey, String threadKey) {
        return new ImportRequest(packageName, accountKey, threadKey, null, false, 0, "imported", null);
    }

    public static ImportRequest forMessage(String packageName, String accountKey, String threadKey,
                                           String messageKey, boolean fromMe) {
        if (!validKey(messageKey)) throw new IllegalArgumentException("Invalid message key");
        return new ImportRequest(packageName, accountKey, threadKey, messageKey, fromMe, 0, "normal", null);
    }

    void validate() throws LibraryException {
        boolean local = LOCAL_PACKAGE.equals(packageName) && LOCAL_ACCOUNT.equals(accountKey);
        boolean scoped = "com.instagram.android".equals(packageName) && validKey(accountKey) && validKey(threadKey);
        if (!(local && "".equals(threadKey) && messageKey == null || scoped)
                || (messageKey != null && !validKey(messageKey))
                || !(("imported".equals(origin) && messageKey == null) || "unknown".equals(origin)
                    || ("normal".equals(origin) && messageKey != null))
                || (messageKey == null && fromMe)
                || (caption != null && caption.length() > 4096) || messageTimestamp < 0) {
            throw new LibraryException(LibraryException.Code.INVALID_REQUEST, "Invalid source or identity");
        }
    }

    public static boolean validKey(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,256}");
    }
}
