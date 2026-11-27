package ps.reso.instaeclipse.library.android;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import ps.reso.instaeclipse.library.core.DirectProgress;

/** Opt-in logcat trace. No IDs, URLs, content or paths are emitted. */
final class DirectTrace {
    static final String TAG = "IEDirectTrace";
    static final DirectProgress PROGRESS = DirectTrace::event;

    private DirectTrace() {}

    static void event(String account, String thread, String part, String stage, String detail) {
        if (!enabled()) return;
        Log.d(TAG, "thread=" + alias(account, thread) + " part=" + alias(account, thread, part)
                + " stage=" + stage + " " + detail);
    }

    static void summary(String stage, String detail) {
        if (enabled()) Log.d(TAG, "stage=" + stage + " " + detail);
    }

    static boolean enabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    static String alias(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Keep aliases consistent in the host and companion during the same test day.
            digest.update(Long.toString(System.currentTimeMillis() / 86_400_000L)
                    .getBytes(StandardCharsets.UTF_8));
            for (String value : values) {
                digest.update((byte) 0);
                if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(Character.forDigit((bytes[i] >>> 4) & 15, 16));
                result.append(Character.forDigit(bytes[i] & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
