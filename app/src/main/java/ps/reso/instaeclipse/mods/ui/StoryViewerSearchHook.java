package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Search and locally sort your story's viewers (Instagram Plus–style; no Plus check is
 * touched). Operates only on the viewer records Instagram has already loaded.
 *
 * - Viewer adapter: declaring class of the method using "search_result_rendered"
 *   (X.0U9B on 452, X.0Q5U on 451). Its private (List, boolean×3) void method is the only place
 *   viewer rows are built; we filter that list. Its public no-arg method using
 *   "dismissed_reel_viewers_list_megaphone" rebuilds the list (called after the query changes).
 * - Viewer record: class using "XDTViewerFeedbackDict" holds the viewer's
 *   com.instagram.user.model.User (found by type); username via UserUtils.
 * - UI: a search field and sort popup added above the RecyclerView using this adapter.
 * Sort order is persistent, but no individual viewer timestamps or replays are inferred.
 */
public class StoryViewerSearchHook {

    private static final String TAG = "(IE|ViewerSearch) ";
    private static final Map<Object, String> QUERIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Integer> SORT_MODES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String SORT_PREFS = "ie_story_viewer_controls";
    private static final String SORT_KEY = "sort_mode";
    private static final Map<Object, Boolean> INJECTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static Method rebuild;
    private static Class<?> recordClass;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> adapter = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("search_result_rendered")))) {
                adapter = cl.loadClass(md.getClassName());
                break;
            }
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("XDTViewerFeedbackDict")))) {
                recordClass = cl.loadClass(md.getClassName());
                break;
            }
            if (adapter == null) { ModuleLog.line(TAG + "❌ viewer adapter not found"); return; }
            Method build = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(adapter)) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 4 && p[0] == List.class
                        && p[1] == boolean.class && p[2] == boolean.class && p[3] == boolean.class) build = m;
            }
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(adapter.getName()).paramCount(0).returnType("void")
                    .usingStrings("dismissed_reel_viewers_list_megaphone")))) {
                rebuild = md.getMethodInstance(cl);
                break;
            }
            if (build == null) { ModuleLog.line(TAG + "❌ row builder not found"); return; }
            build.setAccessible(true);
            if (rebuild != null) rebuild.setAccessible(true);

            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.storyViewerSearch) return;
                    Object ad = param.thisObject;
                    injectSearchBox(ad);
                    if (!(param.args[0] instanceof List<?> list)) return;
                    int mode = SORT_MODES.getOrDefault(ad, StoryViewerListOrder.INSTAGRAM);
                    // The source list is never modified; only loaded viewer sections are changed.
                    param.args[0] = StoryViewerListOrder.apply(list, recordClass, QUERIES.get(ad),
                            mode, StoryViewerSearchHook::username);
                }
            });
            ModuleLog.line(TAG + "✅ adapter " + adapter.getName() + "." + build.getName()
                    + " rebuild=" + (rebuild != null ? rebuild.getName() : "none"));
            FeatureStatusTracker.setHooked("StoryViewerSearch");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static String username(Object record) {
        try {
            for (Class<?> k = record.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("com.instagram.user.model.User")) continue;
                    f.setAccessible(true);
                    return UserUtils.callUsernameGetter(f.get(record));
                }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Adds the search box above the RecyclerView showing this adapter (once per adapter). */
    private static void injectSearchBox(Object adapter) {
        if (INJECTED.containsKey(adapter)) return;
        Activity act = UIHookManager.getCurrentActivity();
        if (act == null) return;
        act.runOnUiThread(() -> {
            if (INJECTED.containsKey(adapter) || act.isFinishing() || act.getWindow() == null) return;
            View list = findListFor(act.getWindow().getDecorView(), adapter);
            if (list == null || !(list.getParent() instanceof ViewGroup parent)) return;
            float d = act.getResources().getDisplayMetrics().density;
            int savedSort = StoryViewerListOrder.validatedMode(
                    act.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).getInt(
                            SORT_KEY, StoryViewerListOrder.INSTAGRAM));
            SORT_MODES.put(adapter, savedSort);
            EditText et = new EditText(act);
            et.setHint(I18n.t(act, R.string.ig_viewer_search_hint));
            et.setSingleLine(true);
            et.setTextColor(Color.WHITE);
            et.setHintTextColor(0x99FFFFFF);
            int ph = (int) (14 * d), pv = (int) (10 * d);
            et.setPadding(ph, pv, ph, pv);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(10 * d);
            et.setBackground(bg);
            et.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    QUERIES.put(adapter, s.toString().trim().toLowerCase(Locale.ROOT));
                    requestRebuild(adapter);
                }
            });
            LinearLayout controls = new LinearLayout(act);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            searchLp.setMarginEnd((int) (8 * d));
            controls.addView(et, searchLp);

            TextView sort = new TextView(act);
            sort.setText(sortLabel(act, savedSort));
            sort.setContentDescription(sortLabel(act, savedSort));
            sort.setSingleLine(true);
            sort.setTextColor(Color.WHITE);
            sort.setTextSize(12f);
            sort.setGravity(Gravity.CENTER);
            sort.setPadding((int) (10 * d), (int) (10 * d),
                    (int) (10 * d), (int) (10 * d));
            GradientDrawable sortBg = new GradientDrawable();
            sortBg.setColor(0x33FFFFFF);
            sortBg.setCornerRadius(10 * d);
            sort.setBackground(sortBg);
            sort.setOnClickListener(v -> showSortMenu(act, sort, adapter));
            controls.addView(sort, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = (int) (12 * d);
            lp.setMargins(m, m / 2, m, m / 2);
            try {
                parent.addView(controls, parent.indexOfChild(list), lp);
                INJECTED.put(adapter, Boolean.TRUE);
                ModuleLog.line(TAG + "search and sort added in " + parent.getClass().getSimpleName());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ add controls: " + t);
            }
        });
    }

    private static void requestRebuild(Object adapter) {
        try {
            if (rebuild != null) rebuild.invoke(adapter);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ rebuild: " + t);
        }
    }

    private static String sortLabel(Activity act, int mode) {
        return switch (StoryViewerListOrder.validatedMode(mode)) {
            case StoryViewerListOrder.NAME_AZ -> I18n.t(act, R.string.ig_viewer_sort_control_az);
            case StoryViewerListOrder.NAME_ZA -> I18n.t(act, R.string.ig_viewer_sort_control_za);
            default -> I18n.t(act, R.string.ig_viewer_sort_control_original);
        };
    }

    private static void showSortMenu(Activity act, TextView button, Object adapter) {
        PopupMenu popup = new PopupMenu(act, button);
        int selected = SORT_MODES.getOrDefault(adapter, StoryViewerListOrder.INSTAGRAM);
        popup.getMenu().add(0, StoryViewerListOrder.INSTAGRAM, 0,
                I18n.t(act, R.string.ig_viewer_sort_menu_original)).setCheckable(true);
        popup.getMenu().add(0, StoryViewerListOrder.NAME_AZ, 1,
                I18n.t(act, R.string.ig_viewer_sort_menu_az)).setCheckable(true);
        popup.getMenu().add(0, StoryViewerListOrder.NAME_ZA, 2,
                I18n.t(act, R.string.ig_viewer_sort_menu_za)).setCheckable(true);
        popup.getMenu().setGroupCheckable(0, true, true);
        for (int i = 0; i < popup.getMenu().size(); i++)
            popup.getMenu().getItem(i).setChecked(popup.getMenu().getItem(i).getItemId() == selected);
        popup.setOnMenuItemClickListener(item -> {
            int chosen = StoryViewerListOrder.validatedMode(item.getItemId());
            SORT_MODES.put(adapter, chosen);
            act.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(SORT_KEY, chosen).apply();
            button.setText(sortLabel(act, chosen));
            button.setContentDescription(sortLabel(act, chosen));
            requestRebuild(adapter);
            return true;
        });
        popup.show();
    }

    private static View findListFor(View root, Object adapter) {
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            try {
                Method ga = v.getClass().getMethod("getAdapter");
                if (ga.invoke(v) == adapter) return v;
            } catch (Throwable ignored) {}
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }
}
