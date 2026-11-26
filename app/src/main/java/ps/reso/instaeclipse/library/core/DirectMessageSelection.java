package ps.reso.instaeclipse.library.core;

import ps.reso.instaeclipse.utils.ghost.ConversationIdentity;

/** Joins the exact native action arguments to the foreground conversation. */
public final class DirectMessageSelection {
    private DirectMessageSelection() {}

    public static ImportRequest match(String packageName, String foregroundAccount, String foregroundThread,
                                      String sessionAccount, String actionThread, String sender,
                                      String modelId, String actionId, String modelKind, String actionKind) {
        if (!"com.instagram.android".equals(packageName) || !ConversationIdentity.validId(sessionAccount)
                || !ConversationIdentity.validId(actionThread) || !ConversationIdentity.validId(sender)
                || !sessionAccount.equals(foregroundAccount) || !actionThread.equals(foregroundThread)
                || !ImportRequest.validKey(modelId) || !modelId.equals(actionId)
                || !"MEDIA".equals(modelKind) || !"MEDIA".equals(actionKind)) return null;
        return ImportRequest.forMessage(packageName, sessionAccount, actionThread, modelId,
                sessionAccount.equals(sender));
    }
}
