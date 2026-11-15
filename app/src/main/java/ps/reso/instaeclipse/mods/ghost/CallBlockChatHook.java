package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Per-chat incoming-call block, toggled from the thread header menu: adds "Block calls from this
 * chat" / "Unblock calls from this chat" (top-left menu). The chat's header title goes into
 * {@link FeatureFlags#callBlockChats}, which {@link CallBlocker} always honours regardless of the
 * global call-block mode. Same triggers and thread lookup as {@link ReadReceiptChatHook}.
 */
public class CallBlockChatHook {

    private static int threadHeaderId, headerTitleId;
    private static final Set<View> pendingDecors = Collections.newSetFromMap(new WeakHashMap<>());

    public void install(ClassLoader classLoader) {
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, act, "onResume", (a, param) -> a.runOnUiThread(() -> register(a)));
            } catch (Throwable t) {
                ModuleLog.line("(IE|CallBlockChat) ⚠️ hook " + act + ": " + t);
            }
        }
        CurrentThreadTracker.install(classLoader);
        CurrentThreadTracker.addListener((a, threadId) -> register(a));
        ModuleLog.line("(IE|CallBlockChat) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private static void register(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
                headerTitleId = a.getResources().getIdentifier("header_title", "id", a.getPackageName());
            }
            View decor = a.getWindow().getDecorView();
            if (tryInject(a)) return;
            if (!pendingDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (tryInject(a)) {
                        pendingDecors.remove(decor);
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|CallBlockChat) ⚠️ register: " + t);
        }
    }

    /** True once the entry is in this thread's menu (the header must be on screen with a title). */
    private static boolean tryInject(Activity a) {
        View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
        if (header == null || !header.isShown()) return false;
        if (ThreadHeaderMenu.has(a, "blockcalls")) return true;
        if (titleOf(header).isEmpty()) return false;
        return addEntry(a, header);
    }

    /** (Re)adds the entry so its label matches whether this chat is currently blocked. */
    private static boolean addEntry(Activity a, View header) {
        boolean blocked = CallBlocker.isChatBlocked(titleOf(header));
        String label = I18n.t(a, blocked ? R.string.ie_call_unblock_chat : R.string.ie_call_block_chat);
        return ThreadHeaderMenu.add(a, "blockcalls", label, () -> {
            String name = titleOf(header);
            if (name.isEmpty()) return;
            boolean now = !CallBlocker.isChatBlocked(name);
            FeatureFlags.callBlockChats = CallBlocker.toggleChat(name, now);
            SettingsManager.saveAllFlags();
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a,
                    I18n.t(a, now ? R.string.ie_call_block_chat_on : R.string.ie_call_block_chat_off),
                    Toast.LENGTH_SHORT).show();
            addEntry(a, header);
        });
    }

    private static String titleOf(View header) {
        View t = headerTitleId != 0 ? header.findViewById(headerTitleId) : null;
        return t instanceof TextView tv && tv.getText() != null ? tv.getText().toString().trim() : "";
    }
}
