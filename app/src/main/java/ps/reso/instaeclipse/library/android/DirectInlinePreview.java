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
import java.util.ArrayList;
import java.util.List;
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
        /** Visual (view-once/replay) bubble: native children hidden while the copy is shown. */
        View[] hidden;
        int[] hiddenVisibility;
        /** Resize the frame to a photo bubble (the "(1) Photo" pill). */
        boolean visual;
        /** An ephemeral row: hide the native glyphs while the copy is shown. */
        boolean ephemeral;
    }

    private DirectInlinePreview() {}

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        DirectInlinePreview preview = new DirectInlinePreview();
        preview.hook(loader);
        try {
            preview.hookVisual(loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            DirectTrace.summary("inline_visual_install", "result=" + mismatch.getClass().getSimpleName());
            ps.reso.instaeclipse.utils.log.ModuleLog.line("(IE|MediaLibrary) visual inline unavailable: " + mismatch);
        }
    }

    /**
     * 454 ephemeral rows (view-once, replay, keep in chat, expired). Both bind a model that extends
     * X.0B79, whose delegate (X.0B79.A00) is the row props X.03QJ: identifier CpM(), thread A05,
     * direction A09, as on the conventional row.
     * <ul>
     *   <li>X.02vL pill ("(1) Photo"): holder X.0AHX, bubble frame A04, icon A05, label A06,
     *       progress A08; the pill becomes a photo bubble.</li>
     *   <li>X.0ThE thumbnail tile (blurred or expired noise): holder X.0Ths, frame A01, image A03,
     *       glyph A02; the copy covers the tile.</li>
     * </ul>
     * With a kept copy for exactly this message the copy is shown and opens from the library;
     * otherwise the row stays native.
     */
    private void hookVisual(ClassLoader loader) throws ReflectiveOperationException {
        hookEphemeralRow(loader, "X.02vL", "X.0AHX", "A04", new String[] {"A05", "A06", "A08"}, true);
        hookEphemeralRow(loader, "X.0ThE", "X.0Ths", "A01", new String[] {"A02"}, false);
        ps.reso.instaeclipse.utils.log.ModuleLog.line("(IE|MediaLibrary) 454 visual inline installed");
    }

    private void hookEphemeralRow(ClassLoader loader, String definitionName, String holderName, String frameName,
                                  String[] hiddenNames, boolean resize) throws ReflectiveOperationException {
        Class<?> definition = DirectLibraryHook.hostClass(loader, definitionName);
        Class<?> holder = DirectLibraryHook.hostClass(loader, holderName);
        Class<?> rowBase = DirectLibraryHook.hostClass(loader, "X.0B79");
        Class<?> rowProps = DirectLibraryHook.hostClass(loader, "X.03QJ");
        Class<?> sessionType = DirectLibraryHook.hostClass(loader, "com.instagram.common.session.UserSession");
        Class<?> identifierType = DirectLibraryHook.hostClass(loader, "com.instagram.model.direct.messageid.MessageIdentifier");
        Class<?> hostHolder = DirectLibraryHook.hostClass(loader, "X.0YmP");
        Class<?> hostModel = DirectLibraryHook.hostClass(loader, "X.0mbl");
        Method bind = definition.getDeclaredMethod("AWs", hostHolder, hostModel);
        Method unbind = definition.getDeclaredMethod("Hjg", hostHolder);
        Method parentId = rowBase.getMethod("CpM");
        Method kind = rowBase.getMethod("BrO");
        Field delegate = field(rowBase, "A00");
        Field frame = field(holder, frameName);
        Field[] hiddenFields = new Field[hiddenNames.length];
        for (int i = 0; i < hiddenNames.length; i++) hiddenFields[i] = field(holder, hiddenNames[i]);
        Field session = field(definition, "A01");
        Field accountId = field(sessionType, "userId");
        Field threadId = field(rowProps, "A05");
        Field direction = field(rowProps, "A09");
        Field serverId = field(identifierType, "A01");
        Field clientContext = field(identifierType, "A00");
        String stage = resize ? "inline_visual" : "inline_tile";

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
                        || !holder.isInstance(call.args[0]) || !rowBase.isInstance(call.args[1])) return;
                try {
                    Object row = call.args[1];
                    DirectTrace.summary(stage, "result=bind");
                    if (!(frame.get(call.args[0]) instanceof FrameLayout container)) {
                        DirectTrace.summary(stage, "result=no_frame");
                        return;
                    }
                    Object props = delegate.get(row);
                    if (!rowProps.isInstance(props)) {
                        DirectTrace.summary(stage, "result=no_row_props");
                        return;
                    }
                    Object identifier = parentId.invoke(row);
                    if (!identifierType.isInstance(identifier)) {
                        DirectTrace.summary(stage, "result=no_identifier");
                        return;
                    }
                    String key = (String) serverId.get(identifier);
                    String local = (String) clientContext.get(identifier);
                    if (!ImportRequest.validKey(key) || !key.matches("[0-9]+") || key.equals(local)) {
                        DirectTrace.summary(stage, "result=key_rejected numeric=" + (key != null && key.matches("[0-9]+"))
                                + " same_as_local=" + (key != null && key.equals(local)));
                        return;
                    }
                    Object nativeSession = session.get(call.thisObject);
                    if (nativeSession == null) {
                        DirectTrace.summary(stage, "result=no_session");
                        return;
                    }
                    String account = (String) accountId.get(nativeSession);
                    String thread = (String) threadId.get(props);
                    Activity activity = activity(container.getContext());
                    if (activity == null) {
                        DirectTrace.event(account, thread, key, stage, "result=no_activity");
                        return;
                    }
                    CurrentConversation.Snapshot foreground = CurrentConversation.resolve(activity);
                    if (foreground == null || !foreground.accountId.equals(account)
                            || !foreground.threadId.equals(thread)) {
                        DirectTrace.event(account, thread, key, stage, "result=scope_unavailable foreground="
                                + (foreground != null) + " account_match=" + (foreground != null && foreground.accountId.equals(account))
                                + " thread_match=" + (foreground != null && foreground.threadId.equals(thread)));
                        return;
                    }
                    Object type = kind.invoke(row);
                    DirectTrace.event(account, thread, key, stage, "result=lookup kind="
                            + (type instanceof Enum<?> e ? e.name() : String.valueOf(type)));
                    ImportRequest selection = ImportRequest.forMessage(DirectStoreScanner.HOST,
                            account, thread, key, direction.getBoolean(props));
                    Binding binding = request(container, activity, foreground, selection);
                    binding.visual = resize;
                    binding.ephemeral = true;
                    List<View> natives = new ArrayList<>();
                    for (Field hidden : hiddenFields) {
                        if (hidden.get(call.args[0]) instanceof View view) natives.add(view);
                    }
                    binding.hidden = natives.toArray(new View[0]);
                    binding.hiddenVisibility = new int[binding.hidden.length];
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    DirectTrace.summary(stage, "result=" + failure.getClass().getSimpleName());
                }
            }
        });
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
                    String name = type instanceof Enum<?> e ? e.name() : null;
                    DirectTrace.summary("inline_bind", "result=bind kind=" + name);
                    // Ephemeral media the client renders as a conventional row also get their copy.
                    if (!"MEDIA".equals(name) && !"EXPIRING_MEDIA".equals(name) && !"VISUAL_MEDIA".equals(name)) return;
                    if (!(frame.get(call.args[0]) instanceof FrameLayout container)
                            || !(nativeImage.get(call.args[0]) instanceof View image)
                            || !contains(container, image)) return;
                    Object visual = media.get(row);
                    // A privacy overlay (blur) is covered by the copy instead of skipping the row.
                    if (visual == null) return;
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

    private Binding request(FrameLayout frame, Activity activity, CurrentConversation.Snapshot foreground,
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
        // Loaded after the caller finished configuring the binding (visual rows set it up first).
        frame.post(() -> {
            if (current(frame) == binding && frame.isAttachedToWindow()) load(frame, activity, foreground, selection, binding);
        });
        return binding;
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
                        FrameLayout.LayoutParams params;
                        if (binding.visual) {
                            // The ephemeral pill becomes a photo bubble: 240dp wide, aspect kept
                            // between 140dp and 340dp tall.
                            float dp = frame.getResources().getDisplayMetrics().density;
                            int width = Math.round(240 * dp);
                            int height = Math.round(width * (float) thumbnail.getHeight() / Math.max(1, thumbnail.getWidth()));
                            height = Math.max(Math.round(140 * dp), Math.min(Math.round(340 * dp), height));
                            params = new FrameLayout.LayoutParams(width, height);
                            overlay.setClipToOutline(true);
                            overlay.setOutlineProvider(new android.view.ViewOutlineProvider() {
                                @Override public void getOutline(View view, android.graphics.Outline outline) {
                                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 18 * dp);
                                }
                            });
                        } else {
                            params = new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        }
                        if (binding.ephemeral && binding.hidden != null) {
                            for (int i = 0; i < binding.hidden.length; i++) {
                                binding.hiddenVisibility[i] = binding.hidden[i].getVisibility();
                                binding.hidden[i].setVisibility(View.GONE);
                            }
                        }
                        overlay.setClickable(true);
                        frame.addView(overlay, params);
                        binding.overlay = overlay;
                        DirectTrace.event(selection.accountKey, selection.threadKey, selection.messageKey,
                                binding.visual ? "inline_visual" : binding.ephemeral ? "inline_tile" : "inline_bind",
                                "result=applied from_me=" + selection.fromMe);
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
        // Compare-and-restore: only views this binding hid, and only if still hidden by it.
        if (binding.hidden != null) {
            for (int i = 0; i < binding.hidden.length; i++) {
                if (binding.hidden[i].getVisibility() == View.GONE) {
                    binding.hidden[i].setVisibility(binding.hiddenVisibility[i]);
                }
            }
        }
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
