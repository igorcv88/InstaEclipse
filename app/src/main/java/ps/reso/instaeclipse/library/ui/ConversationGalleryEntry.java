package ps.reso.instaeclipse.library.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import ps.reso.instaeclipse.library.android.ConversationGalleryProvider;
import ps.reso.instaeclipse.library.android.GalleryAccess;

/** Public trampoline. Only a fresh, one-use capability can launch the private gallery. */
public final class ConversationGalleryEntry extends Activity {
    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        GalleryAccess.Grant grant = GalleryAccess.redeem(
                getIntent().getStringExtra(ConversationGalleryProvider.TOKEN), SystemClock.elapsedRealtime());
        if (grant != null && ConversationGalleryProvider.enabled(this)) {
            startActivity(new Intent(this, MediaLibraryActivity.class)
                    .putExtra(ConversationGalleryProvider.PACKAGE, grant.packageName)
                    .putExtra(ConversationGalleryProvider.ACCOUNT, grant.accountKey)
                    .putExtra(ConversationGalleryProvider.THREAD, grant.threadKey));
        }
        finish();
    }
}
