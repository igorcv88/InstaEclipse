package ps.reso.instaeclipse.library.android;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.library.core.DirectConsolidation;
import ps.reso.instaeclipse.library.core.DirectLiveOfferQueue;
import ps.reso.instaeclipse.library.core.DirectStoreRow;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Runs in the Instagram process. When Instagram 454 builds a Direct row or delivers a native
 * thread page (see docs/MEDIA_LIBRARY_DIRECT_454_INVENTORY.md), each eligible message model is serialized with Instagram's own serializer ({@code X.01hN}, the same JSON that
 * {@code direct.db} stores) and parsed by {@link DirectStoreRow}. Media parts are offered to the
 * module, which keeps them keyed by the message's own ids.
 *
 * <p>Nothing is fetched here and nothing is changed in Instagram. View-once and replayable items
 * are offered with a URL only when they are the account's own or the account is already in
 * {@code seen_user_ids} (the user opened them in Instagram).</p>
 */
public final class DirectLiveCapture {
    /** Row builders in 454: X.0BMD (media rows and A08 for visual rows) and X.0Bu5.A00 (visual rows). */
    private static final String[] BUILDERS = { "X.0BMD", "X.0Bu5" };
    private static final String CONTEXT_ARG = "X.01Fo";
    private static final String MODEL = "X.01hK";
    private static final String MODEL_BASE = "X.0DKm";
    private static final String SERIALIZER = "X.01hN";
    private static final String JSON_FACTORY_HOLDER = "X.02bf";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final long FLUSH_DELAY_MS = 1_500L;

    private static Context hostContext;
    private static Field rowModel, rowMetadata, metadataThread, wrappedThreadId, messageType, threadKey, threadId,
            sessionUser, visualModel, feedMedia;
    private static Method serverId, timestamp, serialize, newGenerator;
    private static Object jsonFactory;
    private static Class<?> sessionClass, messageContextClass;
    private static volatile Handler worker;

    private static final DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
    private static boolean flushScheduled;

    /** Discard reasons already logged, with counts; each reason is logged at most a few times. */
    private static final Map<String, Integer> discards = new LinkedHashMap<>();
    /** Rows seen per message type; the first two of each type are logged. */
    private static final Map<String, Integer> typesSeen = new LinkedHashMap<>();
    private static final AtomicInteger pendingPages = new AtomicInteger();

    private static void discard(String reason) {
        int count;
        synchronized (discards) {
            count = discards.merge(reason, 1, Integer::sum);
        }
        if (count <= 3 || count % 100 == 0) {
            ModuleLog.line("(IE|MediaLibrary) live capture discarded reason=" + reason + " count=" + count);
        }
    }

    private DirectLiveCapture() {}

    public static synchronized void install(Context context, ClassLoader loader) {
        if (worker != null) return;
        try {
            Class<?> contextArg = Class.forName(CONTEXT_ARG, false, loader);
            Class<?> model = Class.forName(MODEL, false, loader);
            Class<?> base = Class.forName(MODEL_BASE, false, loader);
            Class<?> session = Class.forName(SESSION, false, loader);
            rowModel = field(contextArg, "A0y", MODEL);
            rowMetadata = field(contextArg, "A0K", "X.019w");
            metadataThread = field(rowMetadata.getType(), "A0T", "X.00OP");
            wrappedThreadId = field(metadataThread.getType(), "A00", String.class.getName());
            messageType = field(base, "A0V", "X.01iJ");
            threadKey = field(base, "A0T", "com.instagram.model.direct.DirectThreadKey");
            threadId = field(threadKey.getType(), "A00", String.class.getName());
            sessionUser = field(session, "userId", String.class.getName());
            visualModel = field(model, "A0J", "X.02jG");
            feedMedia = field(model, "A0V", "com.instagram.feed.media.Media");
            serverId = model.getMethod("A0o");
            timestamp = model.getMethod("A0J");
            if (serverId.getReturnType() != String.class || timestamp.getReturnType() != long.class) {
                throw new NoSuchMethodException("Unexpected model accessors");
            }
            Class<?> serializer = Class.forName(SERIALIZER, false, loader);
            for (Method candidate : serializer.getDeclaredMethods()) {
                Class<?>[] types = candidate.getParameterTypes();
                if ("A00".equals(candidate.getName()) && Modifier.isStatic(candidate.getModifiers())
                        && types.length == 2 && types[1] == model && "X.0B6n".equals(types[0].getName())) {
                    serialize = candidate;
                }
            }
            Field factory = Class.forName(JSON_FACTORY_HOLDER, false, loader).getField("A00");
            jsonFactory = factory.get(null);
            newGenerator = jsonFactory.getClass().getMethod("A01", java.io.Writer.class);
            if (serialize == null || !"X.0B6n".equals(newGenerator.getReturnType().getName())) {
                throw new NoSuchMethodException("Serializer");
            }
            // Application.attach can run before ContextImpl can return its Application.
            // Its attached host Context is already usable for ContentResolver calls.
            Context app = context.getApplicationContext();
            hostContext = app != null ? app : context;
            sessionClass = session;
            messageContextClass = contextArg;
            worker = startWorker();
            int hooked = 0;
            // Every Direct row, whatever its type or builder, starts from an X.01Fo wrapping the
            // message model (454: one constructor, six call sites). The device test showed media rows
            // that never reach the static builders, so the constructor is the capture point.
            for (java.lang.reflect.Constructor<?> constructor : contextArg.getDeclaredConstructors()) {
                boolean hasSession = false, hasModel = false;
                for (Class<?> type : constructor.getParameterTypes()) {
                    hasSession |= type == session;
                    hasModel |= type == model;
                }
                if (!hasSession || !hasModel) continue;
                XposedBridge.hookMethod(constructor, CONSTRUCTOR_HOOK);
                hooked++;
            }
            for (String name : BUILDERS) {
                Class<?> builders;
                try {
                    builders = Class.forName(name, false, loader);
                } catch (ClassNotFoundException absent) {
                    continue;
                }
                for (Method method : builders.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) continue;
                    // Any static builder that receives the session and the message context, wherever they
                    // sit: visual rows (view-once, replay, keep in chat) use a 4-argument variant.
                    boolean hasSession = false, hasMessage = false;
                    for (Class<?> type : method.getParameterTypes()) {
                        hasSession |= type == session;
                        hasMessage |= type == contextArg;
                    }
                    if (!hasSession || !hasMessage) continue;
                    XposedBridge.hookMethod(method, HOOK);
                    hooked++;
                }
            }
            if (hooked == 0) throw new NoSuchMethodException("Row builders");
            ModuleLog.line("(IE|MediaLibrary) 454 live capture installed on " + hooked + " row builders");
            try {
                installNativePageCapture(loader);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
                discard("page_hook_" + mismatch.getClass().getSimpleName());
            }
            try {
                installRowBatchCapture(loader);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
                discard("row_batch_hook_" + mismatch.getClass().getSimpleName());
            }
            try {
                installSharedMediaCapture(loader);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
                discard("shared_media_model_hook_" + mismatch.getClass().getSimpleName());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError mismatch) {
            ModuleLog.line("(IE|MediaLibrary) live capture unavailable: " + mismatch);
        }
    }

    /** After X.01Fo(UserSession, X.019w, X.01hK, X.0ZiN): the new object is the message context. */
    private static final XC_MethodHook CONSTRUCTOR_HOOK = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            if (param.hasThrowable()) return;
            Object[] args = new Object[param.args.length + 1];
            System.arraycopy(param.args, 0, args, 0, param.args.length);
            args[param.args.length] = param.thisObject;
            handle(param.method, args);
        }
    };

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            if (param.hasThrowable()) return;
            handle(param.method, param.args);
        }
    };

    private static void handle(java.lang.reflect.Member method, Object[] args) {
        if (!FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation) return;
        try {
            Object session = null, context = null;
            for (Object arg : args) {
                if (sessionClass.isInstance(arg)) session = arg;
                else if (messageContextClass.isInstance(arg)) context = arg;
            }
            if (session == null || context == null) { discard("no_session_or_context"); return; }
            Object model = rowModel.get(context);
            if (model == null) { discard("no_model"); return; }
            String name = modelType(model);
            int seenOfType;
            synchronized (typesSeen) {
                seenOfType = typesSeen.merge(name, 1, Integer::sum);
            }
            if (seenOfType <= 2) {
                ModuleLog.line("(IE|MediaLibrary) live capture saw a row type=" + name
                        + " via=" + method.getDeclaringClass().getName() + "." + method.getName());
            }
            if (!isMediaType(name)) return;
            String thread = threadFromMetadata(rowMetadata.get(context));
            captureScoped(model, (String) sessionUser.get(session), thread, name, method instanceof java.lang.reflect.Constructor<?> ? "constructor" : "builder");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ModuleLog.line("(IE|MediaLibrary) live capture skipped a row: " + failure);
        }
    }

    private static String threadFromMetadata(Object metadata) throws IllegalAccessException {
        Object wrapped = metadata == null ? null : metadataThread.get(metadata);
        return wrapped == null ? null : (String) wrappedThreadId.get(wrapped);
    }

    private static String modelType(Object model) throws IllegalAccessException {
        Object type = messageType.get(model);
        return type instanceof Enum<?> e ? e.name() : String.valueOf(type);
    }

    private static boolean isMediaType(String name) {
        return "MEDIA".equals(name) || "EXPIRING_MEDIA".equals(name) || "VISUAL_MEDIA".equals(name);
    }

    private static boolean captureScoped(Object model, String account, String sourceThread, String type,
                                         String observer)
            throws ReflectiveOperationException {
        String id = (String) serverId.invoke(model);
        Object key = threadKey.get(model);
        String modelThread = key == null ? null : (String) threadId.get(key);
        if (ImportRequest.validKey(modelThread) && ImportRequest.validKey(sourceThread)
                && !modelThread.equals(sourceThread)) {
            DirectTrace.event(account, sourceThread, id, "discovery",
                    "observer=" + observer + " result=scope_mismatch");
            discard("model_thread_differs_from_source");
            return false;
        }
        String thread = ImportRequest.validKey(modelThread) ? modelThread : sourceThread;
        DirectTrace.event(account, thread, id, "discovery",
                "observer=" + observer + " native_type=" + type + " id_valid=" + ImportRequest.validKey(id)
                        + " scoped=" + (ImportRequest.validKey(thread) && ImportRequest.validKey(account)));
        if (!ImportRequest.validKey(id)) { discard("no_message_id type=" + type); return false; }
        if (!ImportRequest.validKey(account)) { discard("no_account type=" + type); return false; }
        if (!ImportRequest.validKey(thread)) { discard("no_thread_key type=" + type); return false; }
        return capture(model, id, account, thread, type, observer);
    }

    /** Observes Instagram's own thread response before any row must be built. */
    private static void installNativePageCapture(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fetch = Class.forName("X.0AYL", false, loader);
        Class<?> response = Class.forName("X.0Ir8", false, loader);
        Method responseMethod = findNoArg(fetch, "A0x", response);
        Method requestThread = findNoArg(fetch, "Dpk", String.class);
        Field requestSession = field(fetch.getSuperclass(), "A04", SESSION);
        Field responseError = field(response, "A04", "com.instagram.realtimeclient.DirectApiError");
        Field responsePage = field(response, "A02", "X.0R7T");
        Field pageItems = field(responsePage.getType(), "A00", List.class.getName());
        Field moreBefore = field(responsePage.getType(), "A08", "boolean");
        Field oldestCursor = field(responsePage.getType(), "A05", String.class.getName());
        XposedBridge.hookMethod(responseMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (param.hasThrowable() || !FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation) return;
                try {
                    Object result = param.getResult();
                    if (result == null || responseError.get(result) != null) return;
                    String thread = (String) requestThread.invoke(param.thisObject);
                    Object session = requestSession.get(param.thisObject);
                    String account = session == null ? null : (String) sessionUser.get(session);
                    Object page = responsePage.get(result);
                    if (page == null || !ImportRequest.validKey(account) || !ImportRequest.validKey(thread)) return;
                    Object rawItems = pageItems.get(page);
                    if (!(rawItems instanceof List<?> items)) return;

                    String cursor = (String) oldestCursor.get(page);
                    boolean hasCursor = cursor != null && !cursor.isEmpty();
                    enqueuePage(items, account, thread, "native_page",
                            " has_older=" + moreBefore.getBoolean(page) + " has_cursor=" + hasCursor);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    discard("page_observer_" + failure.getClass().getSimpleName());
                }
            }
        });
        ModuleLog.line("(IE|MediaLibrary) 454 native thread page observer installed");
    }

    /**
     * The native row generator receives the thread separately from its row metadata. This gives
     * rows without an embedded thread id a second source scoped to the same batch and session.
     */
    private static void installRowBatchCapture(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> generator = Class.forName("X.01CY", false, loader);
        Class<?> controller = Class.forName("X.09WC", false, loader);
        Method getThreadKey = findNoArg(controller, "Ccj", threadKey.getType());
        Field generatorSession = field(generator, "A00", SESSION);
        for (Method batch : generator.getDeclaredMethods()) {
            Class<?>[] types = batch.getParameterTypes();
            if (!batch.getName().equals("A02") || Modifier.isStatic(batch.getModifiers())
                    || batch.getReturnType() != ArrayList.class || types.length != 5
                    || !"X.017Y".equals(types[0].getName()) || !"X.0ZeL".equals(types[1].getName())
                    || types[2] != rowMetadata.getType() || !"X.0qeA".equals(types[3].getName())
                    || types[4] != List.class) continue;
            XposedBridge.hookMethod(batch, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (param.hasThrowable() || !FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation) return;
                    try {
                        if (!(param.args[4] instanceof List<?> items)) return;
                        String thread = threadFromMetadata(param.args[2]);
                        if (!ImportRequest.validKey(thread)) {
                            Object owner = param.args[3];
                            if (!controller.isInstance(owner)) return;
                            Object key = getThreadKey.invoke(owner);
                            thread = key == null ? null : (String) threadId.get(key);
                        }
                        Object session = generatorSession.get(param.thisObject);
                        String account = session == null ? null : (String) sessionUser.get(session);
                        if (!ImportRequest.validKey(account) || !ImportRequest.validKey(thread)) {
                            discard("row_batch_identity_unavailable");
                            return;
                        }
                        enqueuePage(items, account, thread, "native_row_batch", "");
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        discard("row_batch_" + failure.getClass().getSimpleName());
                    }
                }
            });
            ModuleLog.line("(IE|MediaLibrary) 454 native row batch observer installed");
            return;
        }
        throw new NoSuchMethodException("Direct row batch");
    }

    /** Taps original Direct messages used by Shared media. Thumbnail-only records carry no
     * reliable view mode or open state, so those never enter the capture pipeline. */
    private static void installSharedMediaCapture(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> gallery = Class.forName("X.0CXS", false, loader);
        Field sessionField = field(gallery, "A04", SESSION);
        for (Method method : gallery.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("A09") || Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != List.class || types.length != 1
                    || types[0] != rowModel.getType()) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (param.hasThrowable() || !FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation
                            || !(param.getResult() instanceof List<?> items) || items.isEmpty()) return;
                    try {
                        Object model = param.args[0];
                        Object session = sessionField.get(param.thisObject);
                        if (model == null || session == null) return;
                        String type = modelType(model);
                        if (!isMediaType(type)) return;
                        captureScoped(model, (String) sessionUser.get(session), null, type, "shared_media");
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        discard("shared_media_model_" + failure.getClass().getSimpleName());
                    }
                }
            });
            ModuleLog.line("(IE|MediaLibrary) 454 shared media message observer installed");
            return;
        }
        throw new NoSuchMethodException("Shared media message projection");
    }

    private static void enqueuePage(List<?> items, String account, String thread,
                                    String source, String details) {
        if (items.size() > 512) { discard("native_page_too_large"); return; }
        if (pendingPages.incrementAndGet() > 8) {
            pendingPages.decrementAndGet();
            discard("page_queue_full");
            return;
        }
        boolean posted = false;
        try {
            List<?> snapshot = new ArrayList<>(items);
            posted = worker.post(() -> captureNativePage(snapshot, account, thread, source, details));
        } finally {
            if (!posted) pendingPages.decrementAndGet();
        }
    }

    private static void captureNativePage(List<?> items, String account, String thread,
                                          String source, String details) {
        int resolved = 0;
        try {
            if (!FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation) return;
            for (Object model : items) {
                if (!rowModel.getType().isInstance(model)) {
                    discard(source + "_non_message_model");
                    continue;
                }
                try {
                    String type = modelType(model);
                    if (isMediaType(type) && captureScoped(model, account, thread, type, source)) resolved++;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    discard("page_item_" + failure.getClass().getSimpleName());
                }
            }
            ModuleLog.line("(IE|MediaLibrary) " + source + " items=" + items.size()
                    + " media_resolved=" + resolved + details);
        } finally {
            pendingPages.decrementAndGet();
        }
    }

    private static boolean capture(Object model, String id, String account, String thread, String type,
                                   String observer) throws ReflectiveOperationException {
        boolean fromSharedMedia = "shared_media".equals(observer);
        try {
            long micros = (long) timestamp.invoke(model);
            StringWriter json = new StringWriter();
            Object generator = newGenerator.invoke(jsonFactory, json);
            serialize.invoke(null, generator, model);
            generator.getClass().getMethod("close").invoke(generator);
            String text = json.toString();
            String itemType = new org.json.JSONObject(text).optString("item_type", null);
            // "Unlimited View Once" rewrites view_mode to "permanent" in this very model; classify
            // by the original mode it recorded.
            String rewritten = ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook
                    .originalMode(visualModel.get(model));
            if (rewritten == null && DirectStoreRow.TYPE_MEDIA.equals(itemType)) {
                rewritten = ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook
                        .originalModeForMedia(feedMedia.get(model));
            }
            DirectStoreRow row = DirectStoreRow.parse(account, thread, id, itemType, micros, text, rewritten);
            if (row == null) {
                DirectTrace.event(account, thread, id, "parsed", "observer=" + observer + " result=refused");
                discard("parse_refused type=" + type + " item_type=" + itemType);
                return false;
            }
            // A persisted "permanent" visual model may be an older, already rewritten row.
            if (fromSharedMedia && !DirectStoreRow.TYPE_MEDIA.equals(itemType) && !row.viewOnce
                    && !account.equals(row.senderId) && !row.seenByViewer) {
                discard("shared_media_visual_mode_unverified");
                return false;
            }
            DirectLiveOfferQueue.Add added = queue.add(row);
            if (added == DirectLiveOfferQueue.Add.FULL) discard("live_offer_queue_full");
            if (DirectTrace.enabled()) {
                for (DirectStoreRow.Part part : row.parts) {
                    DirectTrace.event(account, thread, part.identity.messageKey, "host_queue",
                            "observer=" + observer + " parent=" + DirectTrace.alias(account, thread, id)
                                    + " result=" + added + " candidate=" + (part.best != null)
                                    + " fetchable=" + part.fetchAllowed + " kind=" + part.identity.mediaKind
                                    + " from_me=" + part.identity.fromMe);
                }
            }
            if (added != DirectLiveOfferQueue.Add.QUEUED) return false;
            scheduleFlush();
            int allowed = 0, missingSource = 0;
            for (DirectStoreRow.Part part : row.parts) {
                if (part.fetchAllowed) allowed++;
                if (part.best == null) missingSource++;
            }
            ModuleLog.line("(IE|MediaLibrary) live capture queued type=" + itemType + " parts=" + row.parts.size()
                    + " fetchable=" + allowed + " missing_source=" + missingSource + " viewOnce=" + row.viewOnce + " seen=" + row.seenByViewer
                    + " rewritten=" + rewritten + " fromMe=" + account.equals(row.senderId)
                    + " source=" + observer);
            return true;
        } catch (org.json.JSONException failure) {
            discard("json " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static Handler startWorker() {
        HandlerThread thread = new HandlerThread("ie-direct-live-capture");
        thread.start();
        return new Handler(thread.getLooper());
    }

    private static synchronized void scheduleFlush() {
        if (flushScheduled || !queue.hasPending()) return;
        flushScheduled = worker.postDelayed(DirectLiveCapture::flush, FLUSH_DELAY_MS);
        if (!flushScheduled) {
            worker = startWorker();
            flushScheduled = worker.postDelayed(DirectLiveCapture::flush, FLUSH_DELAY_MS);
        }
    }

    private static void flush() {
        try {
            Map<String, List<DirectLiveOfferQueue.Entry>> byThread = new LinkedHashMap<>();
            for (DirectLiveOfferQueue.Entry entry : queue.snapshot()) {
                DirectStoreRow row = entry.row;
                byThread.computeIfAbsent(row.accountId + '|' + row.threadId, ignored -> new ArrayList<>()).add(entry);
            }
            for (List<DirectLiveOfferQueue.Entry> entries : byThread.values()) {
                List<DirectStoreRow> rows = new ArrayList<>(entries.size());
                for (DirectLiveOfferQueue.Entry entry : entries) rows.add(entry.row);

                int offset = 0;
                for (List<DirectStoreRow> batch : DirectStoreRow.batches(rows, DirectConsolidation.MAX_OFFER_PARTS)) {
                    DirectStoreRow first = batch.get(0);
                    Boolean accepted = null;
                    if (FeatureFlags.directMediaLibrary && FeatureFlags.directMediaConsolidation) {
                        try {
                            accepted = DirectStoreScanner.offer(hostContext, first.accountId, first.threadId, batch, false);
                        } catch (RuntimeException | LinkageError failure) {
                            discard("offer_" + failure.getClass().getSimpleName());
                        }
                    }
                    int end = offset + batch.size();
                    int dropped = queue.finish(entries.subList(offset, end), Boolean.TRUE.equals(accepted));
                    offset = end;
                    ModuleLog.line("(IE|MediaLibrary) live capture offer "
                            + (accepted == null ? "unavailable" : accepted ? "accepted" : "refused")
                            + " rows=" + batch.size() + " retry_exhausted=" + dropped);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            discard("flush_" + failure.getClass().getSimpleName());
        } finally {
            synchronized (DirectLiveCapture.class) {
                flushScheduled = false;
                scheduleFlush();
            }
        }
    }

    private static Method findNoArg(Class<?> owner, String name, Class<?> result) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0
                    && method.getReturnType() == result && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field field(Class<?> owner, String name, String type) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!type.equals(field.getType().getName())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }
}
