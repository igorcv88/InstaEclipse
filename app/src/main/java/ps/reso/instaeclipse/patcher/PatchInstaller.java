package ps.reso.instaeclipse.patcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs the patched Instagram APK set through a {@link PackageInstaller} session. A single base
 * APK or a base-plus-splits set are both handled by writing every APK into one session. The system
 * shows its own install prompt; the user confirms it.
 */
public final class PatchInstaller {

    public static final String ACTION_STATUS = "ps.reso.instaeclipse.patcher.INSTALL_STATUS";

    private PatchInstaller() {
    }

    /** Turns the internal work name ("0_base-patched.apk") into something readable ("base.apk"). */
    public static String friendlyName(String name) {
        if (name == null) return "Instagram";
        String n = name.replaceFirst("^\\d+_", "");
        n = n.replace("-patched.apk", ".apk").replace("-signed.apk", ".apk");
        return n;
    }

    public static void install(Context ctx, List<File> apks, PatchLogger log) throws IOException {
        if (apks == null || apks.isEmpty()) throw new IOException("Nothing to install");
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);

        // Total bytes across the set, so the determinate bar is weighted by size: the ~285 MB base
        // dominates and the bar moves smoothly through it instead of freezing on a single step.
        long total = 0;
        for (File apk : apks) total += Math.max(0, apk.length());
        if (total <= 0) total = 1;

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            byte[] buffer = new byte[65536];
            long written = 0;
            int lastPct = -1;
            for (File apk : apks) {
                log.log("Installing " + friendlyName(apk.getName()));
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        written += read;
                        // Byte-weighted staging fills the 70..90 band; throttle UI to whole percents
                        // so a 285 MB write doesn't post thousands of updates to the main thread.
                        int pct = 70 + (int) (20L * written / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            log.progress(pct);
                            try { session.setStagingProgress((float) written / total); } catch (Throwable ignored) {}
                        }
                    }
                    session.fsync(out);
                }
            }

            Intent intent = new Intent(ctx, PatchInstallReceiver.class).setAction(ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx.getApplicationContext(), sessionId, intent, flags);

            log.log("Opening the system installer");
            log.progress(92);
            session.commit(pending.getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }
}
