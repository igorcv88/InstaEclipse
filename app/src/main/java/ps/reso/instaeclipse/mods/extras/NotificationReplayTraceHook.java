package ps.reso.instaeclipse.mods.extras;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Opt-in, observation-only diagnostic for notifications posted by Instagram.
 *
 * Enable with "setprop debug.instaeclipse.notif_trace 1" before launching Instagram;
 * disable by setting 0 and restarting Instagram. No notification is modified, cancelled
 * or dropped. A private per-install salt keeps message titles and bodies out of logs and
 * stored preferences. Repeated fingerprints can be correlated across process restarts.
 */
public final class NotificationReplayTraceHook {
    private static final String PREFS = "instaeclipse_notification_trace";
    private static final long WINDOW_MS = 48L * 60L * 60L * 1000L;
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    public void install() {
        XposedHelpers.findAndHookMethod(NotificationManager.class, "notify",
                int.class, Notification.class, new ObserveNotify(false));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "notify",
                String.class, int.class, Notification.class, new ObserveNotify(true));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel",
                int.class, new ObserveCancel(false));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel",
                String.class, int.class, new ObserveCancel(true));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancelAll", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ModuleLog.line("(IE|NotifTrace) cancel_all run=" + RUN);
            }
        });
        ModuleLog.line("(IE|NotifTrace) installed run=" + RUN
                + " (observation only; notification payloads are never logged)");
    }

    private static final class ObserveNotify extends XC_MethodHook {
        private final boolean tagged;
        ObserveNotify(boolean tagged) { this.tagged = tagged; }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String tag = tagged ? (String) param.args[0] : null;
                int id = (int) param.args[tagged ? 1 : 0];
                Notification notification = (Notification) param.args[tagged ? 2 : 1];
                if (notification == null) return;
                // Ignore our own synthetic grouping posts, which are not fresh
                // Instagram push deliveries and would create misleading duplicates.
                if (NotificationControlHook.SUMMARY_TAG.equals(tag)) return;
                if (notification.extras != null
                        && notification.extras.containsKey(NotificationControlHook.POST_GENERATION)) return;
                record((NotificationManager) param.thisObject, tag, id, notification);
            } catch (Throwable t) {
                ModuleLog.line("(IE|NotifTrace) observe failure: " + t.getClass().getSimpleName());
            }
        }
    }

    private static final class ObserveCancel extends XC_MethodHook {
        private final boolean tagged;
        ObserveCancel(boolean tagged) { this.tagged = tagged; }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String tag = tagged ? (String) param.args[0] : null;
                int id = (int) param.args[tagged ? 1 : 0];
                Context context = AndroidAppHelper.currentApplication();
                if (context == null) return;
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String salt = salt(prefs);
                String identity = digest(salt, "identity", tag, String.valueOf(id));
                ModuleLog.line("(IE|NotifTrace) cancel run=" + RUN + " id=" + identity);
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized void record(NotificationManager manager, String tag, int id,
                                            Notification notification) {
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String salt = salt(prefs);
        String channel = notification.getChannelId();
        String identity = digest(salt, "identity", tag, String.valueOf(id));
        String fingerprint = fingerprint(salt, tag, id, notification);
        // Observe Android's live state *before* Instagram's post. This separates a
        // new post from a replacement of a notification already present in the shade.
        boolean activeSameId = false;
        boolean activeSamePayload = false;
        try {
            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                if (shown.getId() == id && java.util.Objects.equals(shown.getTag(), tag)) {
                    activeSameId = true;
                    activeSamePayload = fingerprint.equals(
                            fingerprint(salt, tag, id, shown.getNotification()));
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        long now = System.currentTimeMillis();
        long previous = prefs.getLong("sig:" + fingerprint, 0L);
        String oldRun = prefs.getString("run:" + fingerprint, "");
        String oldFingerprint = prefs.getString("id:" + identity, "");
        boolean replay = previous > 0L && now >= previous && now - previous <= WINDOW_MS;
        boolean replacement = !oldFingerprint.isEmpty() && !oldFingerprint.equals(fingerprint);

        // Only this dedicated diagnostic preference file is touched. Bound its size.
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.getAll().size() > 800) {
            editor.clear().putString("salt", salt);
        }
        editor.putLong("sig:" + fingerprint, now)
                .putString("run:" + fingerprint, RUN)
                .putString("id:" + identity, fingerprint)
                .apply();

        ModuleLog.line("(IE|NotifTrace) notify run=" + RUN
                + " pid=" + android.os.Process.myPid()
                + " id=" + identity + " fp=" + fingerprint
                + " activeSameId=" + activeSameId
                + " activeSamePayload=" + activeSamePayload
                + " channel=" + digest(salt, "channel", channel)
                + " category=" + String.valueOf(notification.category)
                + " replay48h=" + replay
                + " previousRun=" + (replay && !RUN.equals(oldRun))
                + " intervalMs=" + (replay ? now - previous : -1)
                + " replacedPayload=" + replacement
                + " ghostSeen=" + FeatureFlags.isGhostSeen
                + " localRead=" + FeatureFlags.markSeenReadLocally
                + " groupEnabled=" + FeatureFlags.groupNotifications);
    }

    private static String salt(SharedPreferences prefs) {
        String existing = prefs.getString("salt", null);
        if (existing != null && !existing.isEmpty()) return existing;
        String generated = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        prefs.edit().putString("salt", generated).commit();
        return generated;
    }

    private static String fingerprint(String salt, String tag, int id, Notification notification) {
        StringBuilder fields = new StringBuilder();
        append(fields, tag);
        append(fields, String.valueOf(id));
        append(fields, notification.getChannelId());
        append(fields, notification.category);
        Bundle extras = notification.extras;
        if (extras != null) {
            for (String key : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
                    Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_CONVERSATION_TITLE}) {
                append(fields, extras.getCharSequence(key));
            }
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence line : lines) append(fields, line);
            Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages != null) {
                for (Parcelable item : messages) {
                    if (item instanceof Bundle message) {
                        append(fields, message.getCharSequence("text"));
                        append(fields, String.valueOf(message.getLong("time", -1)));
                    }
                }
            }
        }
        return digest(salt, "payload", fields.toString());
    }

    private static void append(StringBuilder out, CharSequence value) {
        if (value == null) {
            out.append("-1:");
        } else {
            out.append(value.length()).append(':').append(value);
        }
    }

    private static String digest(String salt, String... fields) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            for (String field : fields) {
                md.update((byte) 0);
                md.update((field == null ? "<null>" : field).getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = md.digest();
            StringBuilder hex = new StringBuilder(20);
            for (int i = 0; i < 10; i++) {
                hex.append(Character.forDigit((bytes[i] >>> 4) & 0xf, 16))
                        .append(Character.forDigit(bytes[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "hash_error";
        }
    }
}
