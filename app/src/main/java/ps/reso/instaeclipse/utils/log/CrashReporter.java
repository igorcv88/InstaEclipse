package ps.reso.instaeclipse.utils.log;

/**
 * Logs every uncaught exception in Instagram's process, then delegates to the handler already in
 * place so the app crashes as it normally would. On installs where Instagram dies unattended (seen
 * non-root) the stack lands in the Logs tab, readable without a live adb connection.
 *
 * Each record flags whether a module class is on the stack, separating a crash this module caused
 * from one in Instagram or the framework it only observed.
 */
public final class CrashReporter {
    private static volatile boolean installed;

    private CrashReporter() {}

    public static void install() {
        if (installed) return;
        installed = true;
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof Reporting) return; // already ours
            Thread.setDefaultUncaughtExceptionHandler(new Reporting(previous));
        } catch (Throwable ignored) {
        }
    }

    private static final class Reporting implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        Reporting(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                boolean ours = involvesModule(ex);
                StringBuilder sb = new StringBuilder("(InstaEclipse | Crash): ")
                        .append(ours ? "module on stack · " : "not module · ")
                        .append("thread ").append(thread != null ? thread.getName() : "?")
                        .append(" · ").append(summary(ex));
                ModuleLog.line(sb.toString());
                for (String line : stackLines(ex)) ModuleLog.line("(InstaEclipse | Crash):   " + line);
            } catch (Throwable ignored) {
            }
            // Never swallow: let the app crash as it normally would.
            try {
                if (previous != null) previous.uncaughtException(thread, ex);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean involvesModule(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("ps.reso.instaeclipse")) return true;
            }
        }
        return false;
    }

    private static String summary(Throwable ex) {
        if (ex == null) return "null";
        String msg = ex.getMessage();
        return ex.getClass().getName() + (msg != null ? ": " + msg : "");
    }

    /** The top frames of the exception and its causes, capped so the log stays readable. */
    private static java.util.List<String> stackLines(Throwable ex) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int budget = 24;
        for (Throwable t = ex; t != null && budget > 0; t = t.getCause()) {
            if (t != ex) out.add("Caused by: " + summary(t));
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length && budget > 0; i++, budget--) out.add("at " + frames[i]);
            if (t.getCause() == t) break;
        }
        return out;
    }
}
