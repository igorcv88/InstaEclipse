package ps.reso.instaeclipse.mods.core;

import java.util.concurrent.ThreadPoolExecutor;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Crash guard for the carousel / realtime teardown race on Instagram 446+ and 447.0.0.39+.
 *
 * When a carousel's realtime media stream (and downstream the Analytics2 event pipeline) is torn
 * down — e.g. after a download or copy-media action — IG's native
 * {@code TigonRepeatingForwardingRequestToken} / analytics processor can still submit a task to a
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor} that has already shut down. The default
 * {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} then throws
 * {@link java.util.concurrent.RejectedExecutionException}, which unwinds through native JNI and
 * hard-crashes Instagram. A dead executor can never run the task, so drop it instead of crashing.
 *
 * Intercepts {@code AbortPolicy.rejectedExecution} and swallows the rejection only when the target
 * executor is already shut down. A rejection on a live executor (genuine backpressure) still throws.
 */
public class TerminatedExecutorGuard {

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.util.concurrent.ThreadPoolExecutor$AbortPolicy", classLoader,
                    "rejectedExecution",
                    Runnable.class, ThreadPoolExecutor.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            try {
                                ThreadPoolExecutor ex = (ThreadPoolExecutor) param.args[1];
                                // isShutdown() covers SHUTDOWN/STOP/TERMINATED — no new task can run, so drop it.
                                if (ex != null && ex.isShutdown()) {
                                    param.setResult(null); // return void without throwing => task dropped
                                    ModuleLog.probe("(IE|ExecGuard) dropped task rejected by shut-down executor");
                                }
                            } catch (Throwable ignored) {
                                // Never let the guard itself interfere with rejection handling.
                            }
                        }
                    }
            );
            ModuleLog.line("(IE|ExecGuard) ✅ installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExecGuard) ⚠️ install failed: " + t.getMessage());
        }
    }
}
