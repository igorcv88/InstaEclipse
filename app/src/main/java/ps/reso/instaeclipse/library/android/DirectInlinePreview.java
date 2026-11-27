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
    private static final java.util.Set<String> BINDER_COMBOS = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        hookEphemeralRow(loader, "X.0ThE", "X.0Ths", "A01", new String[] {"A00", "A02", "A03", "A04"}, false);
        ps.reso.instaeclipse.utils.log.ModuleLog.line("(IE|MediaLibrary) 454 visual inline installed");
        try {
            hookCompose(loader);
            ps.reso.instaeclipse.utils.log.ModuleLog.line("(IE|MediaLibrary) 454 compose inline installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            DirectTrace.summary("inline_compose_install", "result=" + mismatch.getClass().getSimpleName());
            ps.reso.instaeclipse.utils.log.ModuleLog.line("(IE|MediaLibrary) compose inline unavailable: " + mismatch);
        }
        try {
            traceBinders(loader, "X.0BID", "X.0Yza", "BrX");
            traceBinders(loader, "X.0BO7", "X.0Zb2", "DLm");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            DirectTrace.summary("row_binder_install", "result=" + mismatch.getClass().getSimpleName());
        }
    }

    /**
     * 454 Compose message rows (the view-once pill "(1) Photo"): X.0BUa creates a MetaComposeView
     * row held by X.0D7T, bound with the message context X.01Fo. A ComposeView takes no child
     * views, so with a kept copy the row is drawn over through its ViewOverlay: the Compose content
     * is made invisible, the row takes the photo's height and a tap opens the copy. Everything is
     * restored on unbind and before every bind.
     */
    private void hookCompose(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> holder = DirectLibraryHook.hostClass(loader, "X.0D7T");
        Class<?> wrapper = DirectLibraryHook.hostClass(loader, "X.01Fo");
        Class<?> sessionType = DirectLibraryHook.hostClass(loader, "com.instagram.common.session.UserSession");
        Class<?> model = DirectLibraryHook.hostClass(loader, "X.01hK");
        Class<?> base = DirectLibraryHook.hostClass(loader, "X.0DKm");
        Class<?> propsBuilder = DirectLibraryHook.hostClass(loader, "X.03QH");
        Class<?> rowProps = DirectLibraryHook.hostClass(loader, "X.03QJ");
        Class<?> identifierType = DirectLibraryHook.hostClass(loader, "com.instagram.model.direct.messageid.MessageIdentifier");
        Field view = field(holder, "A02");
        Field session = field(wrapper, "A0x");
        Field message = field(wrapper, "A0y");
        Field type = field(base, "A0V");
        Field accountId = field(sessionType, "userId");
        Field identifierField = null;
        for (Field candidate : rowProps.getDeclaredFields()) {
            if (candidate.getType() == identifierType && candidate.getName().equals("A04")) identifierField = candidate;
        }
        if (identifierField == null) throw new NoSuchFieldException("X.03QJ.A04");
        identifierField.setAccessible(true);
        Field identifier = identifierField;
        Field threadId = field(rowProps, "A05");
        Field direction = field(rowProps, "A09");
        Field serverId = field(identifierType, "A01");
        Field clientContext = field(identifierType, "A00");
        Method mediaPart = model.getMethod("A0R");
        Method props = null;
        for (Method candidate : propsBuilder.getDeclaredMethods()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers()) && candidate.getReturnType() == rowProps
                    && types.length == 3 && types[0] == sessionType && types[1] == wrapper
                    && types[2] == mediaPart.getReturnType()) props = candidate;
        }
        if (props == null) throw new NoSuchMethodException("X.03QH.A03");
        Method rowPropsOf = props;
        Method bind = null, unbind = null;
        for (Method candidate : holder.getDeclaredMethods()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 1 && types[0] == wrapper && !candidate.isBridge()) bind = candidate;
            if (types.length == 0 && candidate.getName().equals("A0H")) unbind = candidate;
        }
        if (bind == null || unbind == null) throw new NoSuchMethodException("X.0D7T bind/unbind");
        try {
            // The other Compose row holder (X.0Bqa / X.0D8e) binds another model; trace which one shows up.
            Class<?> other = DirectLibraryHook.hostClass(loader, "X.0D8e");
            for (Method candidate : other.getDeclaredMethods()) {
                if (!candidate.getName().equals("A0I") || candidate.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(candidate, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        Object arg = call.args[0];
                        String combo = "X.0D8e.A0I arg=" + (arg == null ? "null" : arg.getClass().getName());
                        if (DirectTrace.enabled() && BINDER_COMBOS.size() < 256 && BINDER_COMBOS.add(combo)) {
                            DirectTrace.summary("row_binder", combo);
                        }
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            DirectTrace.summary("row_binder_install", "compose_other=" + absent.getClass().getSimpleName());
        }

        XposedBridge.hookMethod(unbind, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws IllegalAccessException {
                if (mainThread() && view.get(call.thisObject) instanceof ViewGroup row) clearCompose(row);
            }
        });
        XposedBridge.hookMethod(bind, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) throws IllegalAccessException {
                if (mainThread() && view.get(call.thisObject) instanceof ViewGroup row) clearCompose(row);
            }

            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable() || !mainThread() || !FeatureFlags.directMediaLibrary) return;
                try {
                    if (!(view.get(call.thisObject) instanceof ViewGroup row)) return;
                    Object context = call.args[0];
                    Object rowModel = context == null ? null : message.get(context);
                    if (!model.isInstance(rowModel)) return;
                    Object kind = type.get(rowModel);
                    String name = kind instanceof Enum<?> e ? e.name() : String.valueOf(kind);
                    DirectTrace.summary("inline_compose", "result=bind kind=" + name);
                    if (!"EXPIRING_MEDIA".equals(name) && !"VISUAL_MEDIA".equals(name)) return;
                    Object nativeSession = session.get(context);
                    Object part = mediaPart.invoke(rowModel);
                    if (nativeSession == null || part == null) {
                        DirectTrace.summary("inline_compose", "result=no_session_or_part");
                        return;
                    }
                    Object built = rowPropsOf.invoke(null, nativeSession, context, part);
                    Object id = built == null ? null : identifier.get(built);
                    if (!identifierType.isInstance(id)) {
                        DirectTrace.summary("inline_compose", "result=no_identifier");
                        return;
                    }
                    String key = (String) serverId.get(id);
                    String local = (String) clientContext.get(id);
                    if (!ImportRequest.validKey(key) || !key.matches("[0-9]+") || key.equals(local)) {
                        DirectTrace.summary("inline_compose", "result=key_rejected");
                        return;
                    }
                    String account = (String) accountId.get(nativeSession);
                    String thread = (String) threadId.get(built);
                    Activity activity = activity(row.getContext());
                    if (activity == null) return;
                    CurrentConversation.Snapshot foreground = CurrentConversation.resolve(activity);
                    if (foreground == null || !foreground.accountId.equals(account)
                            || !foreground.threadId.equals(thread)) {
                        DirectTrace.event(account, thread, key, "inline_compose", "result=scope_unavailable");
                        return;
                    }
                    DirectTrace.event(account, thread, key, "inline_compose", "result=lookup kind=" + name);
                    requestCompose(row, activity, foreground, ImportRequest.forMessage(DirectStoreScanner.HOST,
                            account, thread, key, direction.getBoolean(built)));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    DirectTrace.summary("inline_compose", "result=" + failure.getClass().getSimpleName());
                }
            }
        });
    }

    private static final class ComposeBinding {
        int generation;
        boolean applied;
        View content;
        int contentVisibility;
        int height;
        android.graphics.drawable.Drawable photo;
        View.OnAttachStateChangeListener attachment;
    }

    private final Map<ViewGroup, ComposeBinding> composeBindings = new WeakHashMap<>();

    private void requestCompose(ViewGroup row, Activity activity, CurrentConversation.Snapshot foreground,
                                ImportRequest selection) {
        ComposeBinding binding = new ComposeBinding();
        composeBindings.put(row, binding);
        binding.attachment = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                if (composeBindings.get(row) == binding && !binding.applied) {
                    loadCompose(row, activity, foreground, selection, binding);
                }
            }

            @Override public void onViewDetachedFromWindow(View detached) {
                if (composeBindings.get(row) != binding) return;
                binding.generation++;
                restoreCompose(row, binding);
            }
        };
        row.addOnAttachStateChangeListener(binding.attachment);
        row.post(() -> {
            if (composeBindings.get(row) == binding && row.isAttachedToWindow()) {
                loadCompose(row, activity, foreground, selection, binding);
            }
        });
    }

    private void loadCompose(ViewGroup row, Activity activity, CurrentConversation.Snapshot foreground,
                             ImportRequest selection, ComposeBinding binding) {
        int generation = ++binding.generation;
        try {
            READS.execute(() -> {
                try {
                    Bitmap thumbnail = thumbnail(activity, selection);
                    if (thumbnail == null) {
                        DirectTrace.event(selection.accountKey, selection.threadKey, selection.messageKey,
                                "inline_compose", "result=no_copy");
                        return;
                    }
                    activity.runOnUiThread(() -> {
                        if (composeBindings.get(row) != binding || binding.generation != generation
                                || binding.applied || !row.isAttachedToWindow() || !FeatureFlags.directMediaLibrary
                                || !CurrentConversation.revalidate(activity, foreground)) {
                            thumbnail.recycle();
                            return;
                        }
                        applyCompose(row, activity, foreground, selection, binding, thumbnail);
                    });
                } catch (RuntimeException failure) {
                    DirectTrace.summary("inline_compose", "result=" + failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException busy) {
            DirectTrace.summary("inline_compose", "result=queue_full");
        }
    }

    private static Bitmap thumbnail(Activity activity, ImportRequest selection) {
        Bundle scope = new Bundle();
        scope.putString(ConversationGalleryProvider.ACCOUNT, selection.accountKey);
        scope.putString(ConversationGalleryProvider.THREAD, selection.threadKey);
        scope.putString(ConversationGalleryProvider.MESSAGE, selection.messageKey);
        scope.putBoolean(ConversationGalleryProvider.FROM_ME, selection.fromMe);
        Bundle result = activity.getContentResolver().call(ConversationGalleryProvider.URI,
                ConversationGalleryProvider.INLINE_METHOD, null, scope);
        byte[] bytes = result == null ? null : result.getByteArray(ConversationGalleryProvider.INLINE_BYTES);
        return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void applyCompose(ViewGroup row, Activity activity, CurrentConversation.Snapshot foreground,
                              ImportRequest selection, ComposeBinding binding, Bitmap thumbnail) {
        float dp = row.getResources().getDisplayMetrics().density;
        int width = Math.round(240 * dp);
        int height = Math.round(width * (float) thumbnail.getHeight() / Math.max(1, thumbnail.getWidth()));
        height = Math.max(Math.round(140 * dp), Math.min(Math.round(340 * dp), height));
        int gap = Math.round(4 * dp);
        int rowWidth = row.getWidth() > 0 ? row.getWidth() : row.getResources().getDisplayMetrics().widthPixels;
        // Same edges as the native bubbles: 48dp after the sender avatar, 8dp from the right edge.
        int left = selection.fromMe ? rowWidth - Math.round(8 * dp) - width : Math.round(48 * dp);
        RoundedPhoto photo = new RoundedPhoto(thumbnail, 18 * dp);
        photo.setBounds(left, gap, left + width, gap + height);

        binding.content = row.getChildCount() > 0 ? row.getChildAt(0) : null;
        if (binding.content != null) {
            binding.contentVisibility = binding.content.getVisibility();
            binding.content.setVisibility(View.INVISIBLE);
        }
        ViewGroup.LayoutParams params = row.getLayoutParams();
        binding.height = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
        if (params != null) {
            params.height = height + 2 * gap;
            row.setLayoutParams(params);
        }
        row.getOverlay().add(photo);
        binding.photo = photo;
        binding.applied = true;
        android.graphics.Rect bounds = photo.getBounds();
        row.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            @Override public boolean onTouch(View touched, android.view.MotionEvent event) {
                int x = Math.round(event.getX()), y = Math.round(event.getY());
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        return bounds.contains(x, y);
                    case android.view.MotionEvent.ACTION_UP:
                        if (bounds.contains(x, y) && Math.abs(event.getX() - downX) < 24 * dp
                                && Math.abs(event.getY() - downY) < 24 * dp) {
                            DirectGalleryLauncher.open(activity, selection,
                                    () -> composeBindings.get(row) == binding && FeatureFlags.directMediaLibrary
                                            && CurrentConversation.revalidate(activity, foreground), true);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        DirectTrace.event(selection.accountKey, selection.threadKey, selection.messageKey, "inline_compose",
                "result=applied from_me=" + selection.fromMe);
    }

    private void clearCompose(ViewGroup row) {
        ComposeBinding binding = composeBindings.remove(row);
        if (binding == null) return;
        binding.generation++;
        row.removeOnAttachStateChangeListener(binding.attachment);
        restoreCompose(row, binding);
    }

    private static void restoreCompose(ViewGroup row, ComposeBinding binding) {
        if (!binding.applied) return;
        binding.applied = false;
        row.setOnTouchListener(null);
        if (binding.photo != null) row.getOverlay().remove(binding.photo);
        binding.photo = null;
        // Compare-and-restore: only what this binding changed, and only if still as it left it.
        if (binding.content != null && binding.content.getVisibility() == View.INVISIBLE) {
            binding.content.setVisibility(binding.contentVisibility);
        }
        binding.content = null;
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params != null) {
            params.height = binding.height;
            row.setLayoutParams(params);
        }
    }

    /** A bitmap cropped to fill its bounds, with rounded corners. */
    private static final class RoundedPhoto extends android.graphics.drawable.Drawable {
        private final Bitmap bitmap;
        private final float radius;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF area = new android.graphics.RectF();

        RoundedPhoto(Bitmap bitmap, float radius) {
            this.bitmap = bitmap;
            this.radius = radius;
        }

        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(bitmap,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(), bounds.height() / (float) bitmap.getHeight());
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(bounds.left + (bounds.width() - bitmap.getWidth() * scale) / 2f,
                    bounds.top + (bounds.height() - bitmap.getHeight() * scale) / 2f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            area.set(bounds);
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            canvas.drawRoundRect(area, radius, radius, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Diagnostic, trace only: names each (dispatcher, definition, model, kind) combination once, so a
     * row type that no hook above covers (the view-once pill) can be identified on the device.
     */
    private void traceBinders(ClassLoader loader, String dispatcherName, String wrapperName, String modelGetter)
            throws ReflectiveOperationException {
        Class<?> dispatcher = DirectLibraryHook.hostClass(loader, dispatcherName);
        Class<?> wrapper = DirectLibraryHook.hostClass(loader, wrapperName);
        Class<?> rowBase = DirectLibraryHook.hostClass(loader, "X.0B79");
        Method model = wrapper.getMethod(modelGetter);
        Method kind = rowBase.getMethod("BrO");
        Field definition = field(dispatcher, "A01");
        Method entry = null;
        for (Method candidate : dispatcher.getDeclaredMethods()) {
            if (candidate.getName().equals("A0R") && candidate.getParameterTypes().length == 2) entry = candidate;
        }
        if (entry == null) throw new NoSuchMethodException(dispatcherName + ".A0R");
        XposedBridge.hookMethod(entry, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (!DirectTrace.enabled() || call.hasThrowable()) return;
                try {
                    Object item = call.args[1];
                    Object row = wrapper.isInstance(item) ? model.invoke(item) : null;
                    Object type = rowBase.isInstance(row) ? kind.invoke(row) : null;
                    Object owner = definition.get(call.thisObject);
                    String combo = dispatcherName + " def=" + (owner == null ? "null" : owner.getClass().getName())
                            + " item=" + (item == null ? "null" : item.getClass().getName())
                            + " model=" + (row == null ? "null" : row.getClass().getName())
                            + " kind=" + (type instanceof Enum<?> e ? e.name() : String.valueOf(type));
                    if (BINDER_COMBOS.size() < 256 && BINDER_COMBOS.add(combo)) {
                        DirectTrace.summary("row_binder", combo);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    DirectTrace.summary("row_binder", "result=" + failure.getClass().getSimpleName());
                }
            }
        });
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
                        // A tap anywhere on the bubble opens this message's own copy in the viewer.
                        overlay.setOnClickListener(view -> DirectGalleryLauncher.open(activity, selection,
                                () -> current(frame) == binding && FeatureFlags.directMediaLibrary
                                        && CurrentConversation.revalidate(activity, foreground), true));
                        FrameLayout.LayoutParams params;
                        if (binding.ephemeral) {
                            // An ephemeral bubble (pill or tile) becomes a photo bubble: 240dp wide,
                            // aspect kept between 140dp and 340dp tall. The native views are hidden,
                            // so the wrap-content container takes the copy's size.
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
