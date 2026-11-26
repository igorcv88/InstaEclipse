package ps.reso.instaeclipse.library.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.i18n.I18n;

/** Revalidates the exact UI selection before requesting and before launching a scoped gallery. */
public final class DirectGalleryLauncher {
    private static final ThreadPoolExecutor REQUESTS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> new Thread(task, "ie-conversation-gallery"));

    private DirectGalleryLauncher() {}

    public static void open(Activity activity, ImportRequest selection, BooleanSupplier stillSelected) {
        if (!stillSelected.getAsBoolean()) return;
        Bundle scope = new Bundle();
        scope.putString(ConversationGalleryProvider.ACCOUNT, selection.accountKey);
        scope.putString(ConversationGalleryProvider.THREAD, selection.threadKey);
        scope.putString(ConversationGalleryProvider.MESSAGE, selection.messageKey);
        scope.putBoolean(ConversationGalleryProvider.FROM_ME, selection.fromMe);
        try {
            REQUESTS.execute(() -> {
                Bundle reply = null;
                try {
                    reply = activity.getContentResolver().call(
                            ConversationGalleryProvider.URI, "gallery", null, scope);
                } catch (RuntimeException ignored) {
                    // The companion may be missing, or access may be disabled.
                }
                String token = reply == null ? null : reply.getString(ConversationGalleryProvider.TOKEN);
                activity.runOnUiThread(() -> launch(activity, stillSelected, token));
            });
        } catch (RejectedExecutionException busy) {
            unavailable(activity);
        }
    }

    private static void launch(Activity activity, BooleanSupplier stillSelected, String token) {
        if (activity.isFinishing() || activity.isDestroyed() || !stillSelected.getAsBoolean()) return;
        if (token != null) {
            try {
                activity.startActivity(new Intent().setClassName("ps.reso.instaeclipse",
                        "ps.reso.instaeclipse.library.ui.ConversationGalleryEntry")
                        .putExtra(ConversationGalleryProvider.TOKEN, token));
                return;
            } catch (RuntimeException ignored) {
                // The companion activity is unavailable.
            }
        }
        unavailable(activity);
    }

    private static void unavailable(Activity activity) {
        Toast.makeText(activity, I18n.t(activity, R.string.media_library_scope_unavailable),
                Toast.LENGTH_SHORT).show();
    }
}
