package ps.reso.instaeclipse.library.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.library.core.DirectMessageSelection;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentConversation;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/** Bindings observed in Instagram 454.0.0.0.55. The native adapter, media and receipts stay untouched. */
public final class DirectLibraryHook {
    private static final String HOST = "com.instagram.android";
    private static final String THREAD = "com.instagram.model.direct.DirectThreadKey";
    private static final String MESSAGE = "com.instagram.model.direct.messageid.DirectMessageIdentifier";
    private static final String MESSAGE_BASE = "com.instagram.model.direct.messageid.MessageIdentifier";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String NATIVE_MODEL = "X.01hK";
    private static final String MEDIA_KIND = "X.01iJ";

    private DirectLibraryHook() {}

    public static void install(Context context, ClassLoader loader) {
        try {
            if (!HOST.equals(context.getPackageName())) return;
            PackageInfo host = context.getPackageManager().getPackageInfo(HOST, 0);
            if (!"454.0.0.0.55".equals(host.versionName) || host.getLongVersionCode() != 386005100L) return;
        } catch (Exception unavailable) {
            return;
        }
        try {
            hookMediaAction(loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            ModuleLog.line("(IE|MediaLibrary) message action unavailable");
        }
        try {
            hookSharedMedia(loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            ModuleLog.line("(IE|MediaLibrary) shared media unavailable");
        }
    }

    private static void hookMediaAction(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> modelClass = loader.loadClass(NATIVE_MODEL);
        Class<?> identifierClass = loader.loadClass(MESSAGE);
        Method identifier = modelClass.getDeclaredMethod("A0d");
        identifier.setAccessible(true);
        Field serverId = field(identifierClass, "A02", String.class.getName());
        Field kind = field(identifierClass, "A01", MEDIA_KIND);
        Field sender = field(modelClass.getSuperclass(), "A1D", String.class.getName());
        if (identifier.getReturnType() != identifierClass) throw new NoSuchMethodException("Unexpected identifier");

        Method action = null;
        for (Method candidate : loader.loadClass("X.0QtM").getDeclaredMethods()) {
            Class<?>[] types = candidate.getParameterTypes();
            if ("A08".equals(candidate.getName()) && Modifier.isStatic(candidate.getModifiers())
                    && candidate.getReturnType() == void.class && types.length > 38
                    && types[0] == Activity.class && types[1] == View.class
                    && SESSION.equals(types[2].getName()) && "X.0OHZ".equals(types[34].getName())
                    && types[35] == modelClass && THREAD.equals(types[36].getName())
                    && MESSAGE_BASE.equals(types[38].getName())) {
                if (action != null) throw new NoSuchMethodException("Ambiguous action");
                action = candidate;
            }
        }
        if (action == null) throw new NoSuchMethodException("Direct action");
        XposedBridge.hookMethod(action, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (!FeatureFlags.directMediaLibrary || param.hasThrowable()
                        || Looper.myLooper() != Looper.getMainLooper()
                        || !"SAVE_MEDIA".equals(enumName(param.args[34]))
                        || !(param.args[0] instanceof Activity activity)
                        || param.args[35] == null || !identifierClass.isInstance(param.args[38])) return;
                try {
                    CurrentConversation.Snapshot foreground = CurrentConversation.resolve(activity);
                    if (foreground == null) return;
                    Object model = param.args[35];
                    Object callback = param.args[38];
                    Object nativeId = identifier.invoke(model);
                    if (nativeId == null) return;
                    ImportRequest selection = DirectMessageSelection.match(activity.getPackageName(),
                            foreground.accountId, foreground.threadId,
                            CurrentConversation.accountIdOf(param.args[2]),
                            CurrentConversation.threadIdOfKey(param.args[36]), (String) sender.get(model),
                            (String) serverId.get(nativeId), (String) serverId.get(callback),
                            enumName(kind.get(nativeId)), enumName(kind.get(callback)));
                    if (selection == null) return;
                    IeDialog prompt = new IeDialog(activity).title(I18n.t(activity, R.string.media_library_message_title))
                            .message(I18n.t(activity, R.string.media_library_message_offer));
                    prompt.primary(I18n.t(activity, R.string.media_library_title),
                                    () -> DirectGalleryLauncher.open(activity, selection,
                                            () -> FeatureFlags.directMediaLibrary
                                                    && CurrentConversation.revalidate(activity, foreground)));
                    prompt.show();
                } catch (ReflectiveOperationException | RuntimeException mismatch) {
                    ModuleLog.line("(IE|MediaLibrary) message identity unavailable");
                }
            }
        });
        ModuleLog.line("(IE|MediaLibrary) 454 message action installed");
    }

    private static void hookSharedMedia(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fragment = loader.loadClass("X.0KWO");
        Field threadKey = field(fragment, "A03", THREAD);
        Method getActivity = fragment.getMethod("getActivity");
        Method getView = fragment.getMethod("getView");
        Method getSession = fragment.getMethod("getSession");
        Method isResumed = fragment.getMethod("isResumed");
        Map<Object, WeakReference<Button>> buttons = new WeakHashMap<>();
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onViewCreated", View.class, Bundle.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (param.hasThrowable() || !FeatureFlags.directMediaLibrary
                                || !(param.args[0] instanceof ViewGroup root)) return;
                        Object owner = param.thisObject;
                        try {
                            if (!(getActivity.invoke(owner) instanceof Activity activity)) return;
                            Scope scope = readScope(owner, getSession, threadKey);
                            if (scope == null) return;
                            Button button = attachButton(activity, root);
                            if (button == null) return;
                            removeButton(buttons.put(owner, new WeakReference<>(button)));
                            button.setOnClickListener(view -> DirectGalleryLauncher.open(activity,
                                    ImportRequest.forConversation(HOST, scope.account, scope.thread),
                                    () -> {
                                        try {
                                            return FeatureFlags.directMediaLibrary && button.isShown()
                                                    && root.isAttachedToWindow()
                                                    && getView.invoke(owner) == root
                                                    && getActivity.invoke(owner) == activity
                                                    && Boolean.TRUE.equals(isResumed.invoke(owner))
                                                    && scope.equals(readScope(owner, getSession, threadKey));
                                        } catch (ReflectiveOperationException | RuntimeException mismatch) {
                                            return false;
                                        }
                                    }));
                        } catch (ReflectiveOperationException | RuntimeException mismatch) {
                            ModuleLog.line("(IE|MediaLibrary) shared media identity unavailable");
                        }
                    }
                });
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onDestroyView"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                removeButton(buttons.remove(param.thisObject));
            }
        });
        ModuleLog.line("(IE|MediaLibrary) 454 shared media hook installed");
    }

    @SuppressLint("DiscouragedApi")
    private static Button attachButton(Activity activity, ViewGroup root) {
        int mediaList = activity.getResources().getIdentifier("shared_media_list", "id", HOST);
        if (mediaList == 0 || root.findViewById(mediaList) == null) return null;
        Button button = new Button(activity);
        button.setText(I18n.t(activity, R.string.media_library_title));
        if (root instanceof LinearLayout column && column.getOrientation() == LinearLayout.VERTICAL) {
            column.addView(button, 0, new LinearLayout.LayoutParams(-1, -2));
        } else if (root instanceof FrameLayout frame) {
            frame.addView(button, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        } else return null;
        return button;
    }

    private static void removeButton(WeakReference<Button> reference) {
        Button button = reference == null ? null : reference.get();
        if (button == null) return;
        button.setOnClickListener(null);
        if (button.getParent() instanceof ViewGroup parent) parent.removeView(button);
    }

    private record Scope(String account, String thread) {}

    private static Scope readScope(Object owner, Method session, Field key) throws ReflectiveOperationException {
        String account = CurrentConversation.accountIdOf(session.invoke(owner));
        String thread = CurrentConversation.threadIdOfKey(key.get(owner));
        return account == null || thread == null ? null : new Scope(account, thread);
    }

    private static Field field(Class<?> owner, String name, String type) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!type.equals(field.getType().getName())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> e ? e.name() : null;
    }
}
