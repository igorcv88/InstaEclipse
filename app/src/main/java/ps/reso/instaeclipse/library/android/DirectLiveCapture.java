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
    private static final String BUILDERS = "X.0BMD";
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
    private static Handler worker;

    /** (account|thread|id|fetchable) already offered in this process. */
    private static final Map<String, Boolean> offered = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    };
    /** Pending rows per account|thread, flushed together. */
    private static final Map<String, List<DirectStoreRow>> pending = new LinkedHashMap<>();

    private DirectLiveCapture() {}

    public static void install(Context context, ClassLoader loader) {
        try {
            Class<?> builders = Class.forName(BUILDERS, false, loader);
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
            for (Method method : builders.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) || types.length != 5 || types[0] != Context.class
                        || types[1] != session || types[3] != contextArg) continue;
                XposedBridge.hookMethod(method, HOOK);
                hooked++;
            }
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

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
            if (param.hasThrowable() || !FeatureFlags.directMediaLibrary || !FeatureFlags.directMediaConsolidation) return;
            try {
                Object model = rowModel.get(param.args[3]);
                if (model == null) return;
                Object type = messageType.get(model);
                String name = type instanceof Enum<?> e ? e.name() : null;
                if (!"MEDIA".equals(name) && !"EXPIRING_MEDIA".equals(name) && !"VISUAL_MEDIA".equals(name)) return;
                String id = (String) serverId.invoke(model);
                Object key = threadKey.get(model);
                String thread = key == null ? null : (String) threadId.get(key);
                String account = (String) sessionUser.get(param.args[1]);
                if (!ImportRequest.validKey(id) || !ImportRequest.validKey(thread) || !ImportRequest.validKey(account)) return;
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
                if (row == null) return;
                boolean fetchable = false;
                for (DirectStoreRow.Part part : row.parts) fetchable |= part.fetchAllowed;
                // A view-once item is offered again once the user opens it (fetchable changes).
                String seenKey = account + '|' + thread + '|' + id + '|' + fetchable;
                synchronized (offered) {
                    if (offered.put(seenKey, Boolean.TRUE) != null) return;
                }
                enqueue(row);
            } catch (ReflectiveOperationException | RuntimeException | org.json.JSONException | LinkageError failure) {
                ModuleLog.line("(IE|MediaLibrary) live capture skipped a row: " + failure.getClass().getSimpleName());
            }
        }
    };

    private static void enqueue(DirectStoreRow row) {
        String scope = row.accountId + '|' + row.threadId;
        boolean schedule;
        synchronized (pending) {
            schedule = pending.isEmpty();
            pending.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(row);
        }
        if (schedule) worker.postDelayed(DirectLiveCapture::flush, FLUSH_DELAY_MS);
    }

    private static void flush() {
        Map<String, List<DirectStoreRow>> batch;
        synchronized (pending) {
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        Context context = appContext;
        if (context == null) return;
        int sent = 0;
        for (List<DirectStoreRow> rows : batch.values()) {
            DirectStoreRow first = rows.get(0);
            for (List<DirectStoreRow> part : DirectStoreRow.batches(rows,
                    ps.reso.instaeclipse.library.core.DirectConsolidation.MAX_OFFER_PARTS)) {
                Boolean accepted = DirectStoreScanner.offer(context, first.accountId, first.threadId, part);
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
