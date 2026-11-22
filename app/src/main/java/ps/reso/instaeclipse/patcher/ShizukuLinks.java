package ps.reso.instaeclipse.patcher;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku path to auto-enable a patched app's "supported web addresses" (Android App
 * Links). A re-signed build fails App Links verification and a non-root app can't approve its own
 * links, but the shell user can via {@code pm set-app-links-user-selection}. Shizuku runs that
 * command with ADB-shell privileges, so when it's available and authorized we enable the links in
 * one tap; otherwise the caller falls back to the system "Open by default" screen.
 */
public final class ShizukuLinks {

    private ShizukuLinks() {}

    public static final int PERMISSION_REQUEST_CODE = 0x5712;

    public interface Result { void done(boolean ok, String message); }

    /** True if the Shizuku service is running and reachable. */
    public static boolean available() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** True if Shizuku is reachable and this app already holds its permission. */
    public static boolean hasPermission() {
        try {
            return available() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    public static void requestPermission() {
        try { Shizuku.requestPermission(PERMISSION_REQUEST_CODE); } catch (Throwable ignored) {}
    }

    /**
     * Enables every supported web address of {@code pkg} for the current user via Shizuku. Runs
     * synchronously (call it off the UI thread) and reports through {@code cb}.
     */
    public static void enable(String pkg, Result cb) {
        try {
            List<String> domains = domainsOf(pkg);
            if (domains.isEmpty()) { cb.done(false, "No link domains found"); return; }
            List<String> cmd = new ArrayList<>();
            cmd.add("pm");
            cmd.add("set-app-links-user-selection");
            cmd.add("--user");
            cmd.add("0");
            cmd.add("--package");
            cmd.add(pkg);
            cmd.add("true");
            cmd.addAll(domains);
            exec(cmd.toArray(new String[0]));
            cb.done(true, domains.size() + " links enabled");
        } catch (Throwable t) {
            cb.done(false, String.valueOf(t));
        }
    }

    /** Parses the hosts under "Domain verification state" from {@code pm get-app-links PKG}. */
    private static List<String> domainsOf(String pkg) throws Throwable {
        String out = exec(new String[]{"pm", "get-app-links", pkg});
        List<String> domains = new ArrayList<>();
        boolean inState = false;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Domain verification state")) { inState = true; continue; }
            if (!inState) continue;
            if (line.startsWith("User ")) break;           // per-user section follows the hosts
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String host = line.substring(0, c).trim();
            String rest = line.substring(c + 1).trim();
            if (host.contains(".") && rest.matches("\\d+")) domains.add(host);
        }
        return domains;
    }

    /** Runs a command through Shizuku (ADB-shell uid) and returns its stdout. */
    private static String exec(String[] cmd) throws Throwable {
        Method newProcess = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        newProcess.setAccessible(true);
        Process process = (Process) newProcess.invoke(null, cmd, null, null);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        process.waitFor();
        return sb.toString();
    }
}
