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

import ps.reso.instaeclipse.library.core.DirectStoreRow;
import ps.reso.instaeclipse.library.core.ImportRequest;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Runs in the Instagram process. Every time Instagram 454 builds a Direct row for a message
 * ({@code X.0BMD} static builders, see docs/MEDIA_LIBRARY_DIRECT_454_INVENTORY.md), the message
 * model is serialized with Instagram's own serializer ({@code X.01hN}, the same JSON that
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
    private static final int SEEN_LIMIT = 4_096;

    private static volatile Context appContext;
    private static Field rowModel, messageType, threadKey, threadId, sessionUser, visualModel;
    private static Method serverId, timestamp, serialize, newGenerator;
    private static Object jsonFactory;
    private static Class<?> sessionClass, messageContextClass;
    private static Handler worker;

    /** (account|thread|id|fetchable) already offered in this process. */
    private static final Map<String, Boolean> offered = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    };
    /** Discard reasons already logged, with counts; each reason is logged at most a few times. */
    private static final Map<String, Integer> discards = new LinkedHashMap<>();
    /** Rows seen per message type; the first two of each type are logged. */
    private static final Map<String, Integer> typesSeen = new LinkedHashMap<>();
    private static final Handler main = new Handler(android.os.Looper.getMainLooper());

    private static void discard(String reason) {
        int count;
        synchronized (discards) {
            count = discards.merge(reason, 1, Integer::sum);
        }
        if (count <= 3 || count % 100 == 0) {
            ModuleLog.line("(IE|MediaLibrary) live capture discarded reason=" + reason + " count=" + count);
        }
    }

    /** Pending rows per account|thread, keyed by message id: a later state replaces an earlier one. */
    private static final Map<String, Map<String, DirectStoreRow>> pending = new LinkedHashMap<>();

    private DirectLiveCapture() {}

    public static void install(Context context, ClassLoader loader) {
        try {
            Class<?> contextArg = Class.forName(CONTEXT_ARG, false, loader);
            Class<?> model = Class.forName(MODEL, false, loader);
            Class<?> base = Class.forName(MODEL_BASE, false, loader);
            Class<?> session = Class.forName(SESSION, false, loader);
            rowModel = field(contextArg, "A0y", MODEL);
            messageType = field(base, "A0V", "X.01iJ");
            threadKey = field(base, "A0T", "com.instagram.model.direct.DirectThreadKey");
            threadId = field(threadKey.getType(), "A00", String.class.getName());
            sessionUser = field(session, "userId", String.class.getName());
            visualModel = field(model, "A0J", "X.02jG");
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
            sessionClass = session;
            messageContextClass = contextArg;
            if (hooked == 0) throw new NoSuchMethodException("Row builders");
            appContext = context.getApplicationContext();
            HandlerThread thread = new HandlerThread("ie-direct-live-capture");
            thread.start();
            worker = new Handler(thread.getLooper());
            ModuleLog.line("(IE|MediaLibrary) 454 live capture installed on " + hooked + " row builders");
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
                Object type = messageType.get(model);
                String name = type instanceof Enum<?> e ? e.name() : String.valueOf(type);
                int seenOfType;
                synchronized (typesSeen) {
                    seenOfType = typesSeen.merge(name, 1, Integer::sum);
                }
                if (seenOfType <= 2) {
                    ModuleLog.line("(IE|MediaLibrary) live capture saw a row type=" + name
                            + " via=" + method.getDeclaringClass().getName() + "." + method.getName());
                }
                if (!"MEDIA".equals(name) && !"EXPIRING_MEDIA".equals(name) && !"VISUAL_MEDIA".equals(name)) return;
                String id = (String) serverId.invoke(model);
                Object key = threadKey.get(model);
                String thread = key == null ? null : (String) threadId.get(key);
                String account = (String) sessionUser.get(session);
                if (!ImportRequest.validKey(id)) { discard("no_server_id type=" + name); return; }
                if (!ImportRequest.validKey(account)) { discard("no_account"); return; }
                if (!ImportRequest.validKey(thread)) {
                    // Messages loaded from the server may carry no thread key on the model; use the
                    // conversation open on screen, resolved and validated on the main thread.
                    Object pendingModel = model;
                    main.post(() -> fromForeground(pendingModel, id, account, name));
                    return;
                }
                capture(model, id, account, thread, name);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                ModuleLog.line("(IE|MediaLibrary) live capture skipped a row: " + failure);
            }
    }

    private static void fromForeground(Object model, String id, String account, String type) {
        try {
            android.app.Activity activity = ps.reso.instaeclipse.utils.ghost.CurrentConversation.foregroundActivity();
            ps.reso.instaeclipse.utils.ghost.CurrentConversation.Snapshot snapshot = activity == null ? null
                    : ps.reso.instaeclipse.utils.ghost.CurrentConversation.resolve(activity);
            if (snapshot == null) { discard("no_thread_and_no_foreground_conversation type=" + type); return; }
            if (!account.equals(snapshot.accountId)) { discard("foreground_account_differs"); return; }
            capture(model, id, account, snapshot.threadId, type);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ModuleLog.line("(IE|MediaLibrary) live capture skipped a row: " + failure);
        }
    }

    private static void capture(Object model, String id, String account, String thread, String type)
            throws ReflectiveOperationException {
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
                DirectStoreRow row = DirectStoreRow.parse(account, thread, id, itemType, micros, text, rewritten);
                if (row == null) { discard("parse_refused type=" + type + " item_type=" + itemType); return; }
                // Offered again when fetchability or a URL changes: a view-once the user opened, or
                // a refreshed CDN URL after the previous one expired.
                StringBuilder state = new StringBuilder();
                for (DirectStoreRow.Part part : row.parts) {
                    state.append(part.fetchAllowed ? part.best.url : "-").append('\n');
                }
                String seenKey = account + '|' + thread + '|' + id + '|' + state.toString().hashCode();
                synchronized (offered) {
                    if (offered.put(seenKey, Boolean.TRUE) != null) return;
                }
                int allowed = 0;
                for (DirectStoreRow.Part part : row.parts) if (part.fetchAllowed) allowed++;
                ModuleLog.line("(IE|MediaLibrary) live capture queued type=" + itemType + " parts=" + row.parts.size()
                        + " fetchable=" + allowed + " viewOnce=" + row.viewOnce + " seen=" + row.seenByViewer
                        + " rewritten=" + rewritten + " fromMe=" + account.equals(row.senderId));
                enqueue(row);
        } catch (org.json.JSONException failure) {
            discard("json " + failure.getClass().getSimpleName());
        }
    }

    private static void enqueue(DirectStoreRow row) {
        String scope = row.accountId + '|' + row.threadId;
        boolean schedule;
        synchronized (pending) {
            schedule = pending.isEmpty();
            pending.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).put(row.itemId, row);
        }
        if (schedule) worker.postDelayed(DirectLiveCapture::flush, FLUSH_DELAY_MS);
    }

    private static void flush() {
        Map<String, Map<String, DirectStoreRow>> batch;
        synchronized (pending) {
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        Context context = appContext;
        if (context == null) return;
        int sent = 0;
        for (Map<String, DirectStoreRow> byId : batch.values()) {
            List<DirectStoreRow> rows = new ArrayList<>(byId.values());
            DirectStoreRow first = rows.get(0);
            for (List<DirectStoreRow> part : DirectStoreRow.batches(rows,
                    ps.reso.instaeclipse.library.core.DirectConsolidation.MAX_OFFER_PARTS)) {
                Boolean accepted = DirectStoreScanner.offer(context, first.accountId, first.threadId, part, false);
                if (!Boolean.TRUE.equals(accepted)) {
                    forget(part);
                    ModuleLog.line("(IE|MediaLibrary) live capture offer "
                            + (accepted == null ? "unavailable" : "refused") + " rows=" + part.size());
                    continue;
                }
                sent += part.size();
            }
        }
        if (sent > 0) ModuleLog.line("(IE|MediaLibrary) live capture offered rows=" + sent);
    }

    /** Rows the module did not take are offered again on their next bind. */
    private static void forget(List<DirectStoreRow> rows) {
        synchronized (offered) {
            for (DirectStoreRow row : rows) {
                offered.keySet().removeIf(key -> key.startsWith(row.accountId + '|' + row.threadId + '|' + row.itemId + '|'));
            }
        }
    }

    private static Field field(Class<?> owner, String name, String type) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!type.equals(field.getType().getName())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }
}
