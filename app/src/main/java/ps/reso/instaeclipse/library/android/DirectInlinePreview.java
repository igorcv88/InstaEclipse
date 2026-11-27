package ps.reso.instaeclipse.library.android;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentConversation;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/** Local preview on the 454 conventional media row, without replacing the native media view. */
final class DirectInlinePreview {
    private static final ThreadPoolExecutor READS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> new Thread(task, "ie-direct-inline"));
    private final Map<FrameLayout, WeakReference<Binding>> bindings = new WeakHashMap<>();

    private static final class Binding {
        ImageView overlay;
        int generation;
        View.OnAttachStateChangeListener attachment;
    }

    private DirectInlinePreview() {}

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        new DirectInlinePreview().hook(loader);
    }

    private void hook(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> definition = DirectLibraryHook.hostClass(loader, "X.0ThI");
        Class<?> holder = DirectLibraryHook.hostClass(loader, "X.0TiJ");
        Class<?> model = DirectLibraryHook.hostClass(loader, "X.0BYS");
        Class<?> sessionType = DirectLibraryHook.hostClass(loader, "com.instagram.common.session.UserSession");
        Class<?> identifierType = DirectLibraryHook.hostClass(loader, "com.instagram.model.direct.messageid.MessageIdentifier");
        Class<?> hostHolder = DirectLibraryHook.hostClass(loader, "X.0YmP");
        Class<?> hostModel = DirectLibraryHook.hostClass(loader, "X.0mbl");
        Method bind = definition.getDeclaredMethod("AWs", hostHolder, hostModel);
        Method unbind = definition.getDeclaredMethod("Hjg", hostHolder);
        Method parentId = model.getMethod("CpM");
        Method partId = model.getMethod("Brl");
        Method kind = model.getMethod("BrO");
        Field frame = field(holder, "A08");
        Field nativeImage = field(holder, "A03");
        Field session = field(definition, "A04");
        Field accountId = field(sessionType, "userId");
        Field props = field(model, "A07");
        Field threadId = field(props.getType(), "A05");
        Field direction = field(props.getType(), "A09");
        Field media = field(model, "A06");
        Field privacyOverlay = field(media.getType(), "A0I");
        Field serverId = field(identifierType, "A01");
        Field clientContext = field(identifierType, "A00");

        XposedBridge.hookMethod(unbind, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws IllegalAccessException {
                if (mainThread() && holder.isInstance(call.args[0])
                        && frame.get(call.args[0]) instanceof FrameLayout container) clear(container);
            }
        });
        XposedBridge.hookMethod(bind, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws IllegalAccessException {
                if (mainThread() && holder.isInstance(call.args[0])
                        && frame.get(call.args[0]) instanceof FrameLayout container) clear(container);
            }

            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable() || !mainThread() || !FeatureFlags.directMediaLibrary
                        || !holder.isInstance(call.args[0]) || !model.isInstance(call.args[1])) return;
                try {
                    Object row = call.args[1];
                    Object type = kind.invoke(row);
                    if (!(type instanceof Enum<?> e) || !"MEDIA".equals(e.name())) return;
                    if (!(frame.get(call.args[0]) instanceof FrameLayout container)
                            || !(nativeImage.get(call.args[0]) instanceof View image)
                            || !contains(container, image)) return;
                    Object visual = media.get(row);
                    if (visual == null || privacyOverlay.get(visual) != null) return;
                    Object identifier = parentId.invoke(row);
                    if (!identifierType.isInstance(identifier)) return;
                    String key = (String) serverId.get(identifier);
                    String local = (String) clientContext.get(identifier);
                    Object slide = partId.invoke(row);
                    if (!ImportRequest.validKey(key) || !key.matches("[0-9]+") || key.equals(local)
                            || slide != null && (!identifierType.isInstance(slide)
                            || !key.equals(serverId.get(slide)))) return;
                    Object nativeSession = session.get(call.thisObject);
                    Object rowProps = props.get(row);
                    if (nativeSession == null || rowProps == null) return;
                    String account = (String) accountId.get(nativeSession);
                    String thread = (String) threadId.get(rowProps);
                    Activity activity = activity(container.getContext());
                    if (activity == null) return;
                    CurrentConversation.Snapshot foreground = CurrentConversation.resolve(activity);
                    if (foreground == null || !foreground.accountId.equals(account)
                            || !foreground.threadId.equals(thread)) {
                        DirectTrace.event(account, thread, key, "inline_bind", "result=scope_unavailable");
                        return;
                    }
                    ImportRequest selection = ImportRequest.forMessage(DirectStoreScanner.HOST,
                            account, thread, key, direction.getBoolean(rowProps));
                    request(container, activity, foreground, selection);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    DirectTrace.summary("inline_bind", "result=" + failure.getClass().getSimpleName());
                }
            }
        });
    }

    private void request(FrameLayout frame, Activity activity, CurrentConversation.Snapshot foreground,
                         ImportRequest selection) {
        Binding binding = new Binding();
        binding.attachment = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (current(frame) == binding) load(frame, activity, foreground, selection, binding);
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (current(frame) != binding) return;
                binding.generation++;
                removeOverlay(frame, binding);
            }
        };
        bindings.put(frame, new WeakReference<>(binding));
        frame.addOnAttachStateChangeListener(binding.attachment);
        if (frame.isAttachedToWindow()) load(frame, activity, foreground, selection, binding);
    }

    private void load(FrameLayout frame, Activity activity, CurrentConversation.Snapshot foreground,
                      ImportRequest selection, Binding binding) {
        int generation = ++binding.generation;
        Bundle scope = new Bundle();
        scope.putString(ConversationGalleryProvider.ACCOUNT, selection.accountKey);
        scope.putString(ConversationGalleryProvider.THREAD, selection.threadKey);
        scope.putString(ConversationGalleryProvider.MESSAGE, selection.messageKey);
        scope.putBoolean(ConversationGalleryProvider.FROM_ME, selection.fromMe);
        try {
            READS.execute(() -> {
                try {
                    Bundle result = activity.getContentResolver().call(ConversationGalleryProvider.URI,
                            ConversationGalleryProvider.INLINE_METHOD, null, scope);
                    byte[] bytes = result == null ? null : result.getByteArray(ConversationGalleryProvider.INLINE_BYTES);
                    if (bytes == null) return;
                    Bitmap thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (thumbnail == null) return;
                    activity.runOnUiThread(() -> {
                        if (current(frame) != binding || binding.generation != generation
                                || !frame.isAttachedToWindow()
                                || !FeatureFlags.directMediaLibrary
                                || !CurrentConversation.revalidate(activity, foreground)) {
                            thumbnail.recycle();
                            return;
                        }
                        ImageView overlay = new ImageView(frame.getContext());
                        overlay.setImageBitmap(thumbnail);
                        overlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        overlay.setContentDescription(I18n.t(activity, R.string.media_library_title));
                        overlay.setOnClickListener(view -> DirectGalleryLauncher.open(activity, selection,
                                () -> current(frame) == binding && FeatureFlags.directMediaLibrary
                                        && CurrentConversation.revalidate(activity, foreground)));
                        frame.addView(overlay, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        binding.overlay = overlay;
                        DirectTrace.event(selection.accountKey, selection.threadKey, selection.messageKey,
                                "inline_bind", "result=applied from_me=" + selection.fromMe);
                    });
                } catch (RuntimeException failure) {
                    DirectTrace.summary("inline_preview", "result=" + failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException busy) {
            DirectTrace.summary("inline_preview", "result=queue_full");
        }
    }

    private void clear(FrameLayout frame) {
        Binding binding = current(frame);
        bindings.remove(frame);
        if (binding == null) return;
        frame.removeOnAttachStateChangeListener(binding.attachment);
        removeOverlay(frame, binding);
    }

    private static void removeOverlay(FrameLayout frame, Binding binding) {
        if (binding.overlay == null) return;
        frame.removeView(binding.overlay);
        binding.overlay.setImageDrawable(null);
        binding.overlay = null;
    }

    private Binding current(FrameLayout frame) {
        WeakReference<Binding> entry = bindings.get(frame);
        return entry == null ? null : entry.get();
    }

    private static boolean mainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static boolean contains(ViewGroup root, View child) {
        for (ViewParent owner = child.getParent(); owner instanceof View; owner = owner.getParent()) {
            if (owner == root) return true;
        }
        return false;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity activity) return activity;
            Context next = wrapper.getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
