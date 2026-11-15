package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.mods.misc.FollowStatusHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class IGNetworkInterceptor {

    private static final URI FAKE_URI = URI.create("https://127.0.0.1/404");
    private static final String FAKE_URL = "https://127.0.0.1/404";

    /**
     * Neutralise a dropped request by redirecting it to a dead local URL. IG 446+/447.0.0.39+ keeps
     * the request URL in MULTIPLE fields on the request object (e.g. a String copy plus two java.net.URI
     * copies), and the actual dispatch reads one of the copies — not necessarily the single URI field
     * resolved for inspection. Rewriting only that one field is silently ignored, so the request
     * still goes out. Rewrite EVERY url-bearing field: every java.net.URI field, and every String
     * field that currently holds this request's URL (matched by the original value / same path).
     */
    private static void neutralizeUrlFields(Object requestObj, URI original) {
        String orig = original.toString();
        String origPath = original.getPath();
        for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(requestObj.getClass())) {
            try {
                // Never touch statics: the request class keeps a shared static URI constant
                // ("https://malformed.url.invalid/") that other requests read.
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(requestObj);
                if (v instanceof URI) {
                    f.set(requestObj, FAKE_URI);
                } else if (v instanceof String s && s.startsWith("http")
                        && (s.equals(orig) || (origPath != null && !origPath.isEmpty() && s.contains(origPath)))) {
                    f.set(requestObj, FAKE_URL);
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * TigonServiceLayer is the LAST of ~25 decorator layers (RequestCacheServiceLayer,
     * ZeroRewriteAsyncServiceLayer, ...) that all implement the same startRequest interface. A
     * layer that answers from cache never reaches TigonServiceLayer, so also apply the same
     * (idempotent) drop at the chain ENTRY, AsyncHttpService.startRequest — anchored by its stable
     * systrace string (present in 447 and 451). Rewritten URLs no longer match any drop rule, so a
     * request passing through both hooks is handled exactly once. Best-effort: on failure the
     * TigonServiceLayer hook alone still applies, as before.
     */
    private static void hookChainEntry(ClassLoader classLoader, Class<?> p1, Class<?> p2, Class<?> p3,
                                       XC_MethodHook dropHook) {
        try {
            if (ps.reso.instaeclipse.Xposed.Module.dexKitBridge == null) return;
            java.util.List<org.luckypray.dexkit.result.MethodData> found =
                    DexKitQueryCache.findMethod(ps.reso.instaeclipse.Xposed.Module.dexKitBridge, 
                            org.luckypray.dexkit.query.FindMethod.create().matcher(
                                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .name("startRequest")
                                            .usingStrings("AsyncHttpService.startRequest")));
            int n = 0;
            for (org.luckypray.dexkit.result.MethodData md : found) {
                Method m = md.getMethodInstance(classLoader);
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3 || pt[0] != p1 || pt[1] != p2 || pt[2] != p3) continue;
                XposedBridge.hookMethod(m, dropHook);
                n++;
            }
            ModuleLog.line("(InstaEclipse | Interceptor): chain-entry hooks: " + n);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Interceptor): ⚠️ chain-entry hook: " + t.getMessage());
        }
    }

    /** Once-found IDLE constant of IG's PresenceStatus enum (keyed by its enum class). */
    private static volatile Enum<?> presenceIdle;

    /**
     * Freeze the "active now" green dot. IG reports presence to Meta's presence service by
     * constructing {@code com.facebook.presence.model.upi.PresenceWriteRequest} with a
     * {@code PresenceStatus} (its 3rd arg): ACTIVE while the app is in front, IDLE from the
     * background. All of these class names are stable (unobfuscated Meta presence model), so no
     * DexKit is needed. While SpoofLastSeen is on we swap an outgoing ACTIVE for IDLE — the exact
     * status IG itself sends when backgrounded — so others stop seeing "active now" while the
     * stream, and the presence of people you chat with that comes back on it, are untouched.
     */
    private static void hookActiveStatus(ClassLoader classLoader) {
        try {
            Class<?> writeRequest = XposedHelpers.findClassIfExists(
                    "com.facebook.presence.model.upi.PresenceWriteRequest", classLoader);
            final Class<?> statusEnum = XposedHelpers.findClassIfExists(
                    "com.facebook.presence.model.upi.PresenceStatus", classLoader);
            if (writeRequest == null || statusEnum == null) {
                ModuleLog.line("(InstaEclipse | Interceptor): ⚠️ active-status: presence classes not found");
                return;
            }
            XC_MethodHook swap = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] args = param.args;
                        // The status is the 3rd parameter of the write-request constructor(s).
                        if (args.length <= 2 || !statusEnum.isInstance(args[2])) return;
                        if (!FeatureFlags.spoofLastSeen || !"ACTIVE".equals(((Enum<?>) args[2]).name())) return;
                        Enum<?> idle = presenceIdleOf(statusEnum);
                        if (idle != null) args[2] = idle;
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Arg rewrites in a before-hook don't always reach the stored field under
                    // libxposed/LSPatch, so also set the request's status field to IDLE directly.
                    try {
                        if (!FeatureFlags.spoofLastSeen) return;
                        Object obj = param.thisObject;
                        if (obj == null) return;
                        Enum<?> idle = presenceIdleOf(statusEnum);
                        if (idle == null) return;
                        for (Field f : obj.getClass().getDeclaredFields()) {
                            if (f.getType() != statusEnum) continue;
                            f.setAccessible(true);
                            Object v = f.get(obj);
                            if (v instanceof Enum && "ACTIVE".equals(((Enum<?>) v).name())) f.set(obj, idle);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int n = 0;
            for (java.lang.reflect.Constructor<?> c : writeRequest.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, swap);
                n++;
            }
            if (n > 0) FeatureStatusTracker.setHooked("SpoofLastSeen");
            ModuleLog.line("(InstaEclipse | Interceptor): active-status (presence write) ctor hooks: " + n);

            // The green "active now" dot is driven by the realtime connection's foreground app-state,
            // not only the presence write. RealtimeClientManager.updateAppStateInternal(boolean) reports
            // it (true=foreground). Force background while SpoofLastSeen is on so the server never marks
            // us active now. Name is stable/unobfuscated on 451/452.
            Class<?> rcm = XposedHelpers.findClassIfExists(
                    "com.instagram.realtimeclient.RealtimeClientManager", classLoader);
            if (rcm != null) {
                XposedHelpers.findAndHookMethod(rcm, "updateAppStateInternal", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (FeatureFlags.spoofLastSeen && Boolean.TRUE.equals(param.args[0])) {
                                param.args[0] = Boolean.FALSE;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                ModuleLog.line("(InstaEclipse | Interceptor): hooked updateAppStateInternal");
            } else {
                ModuleLog.line("(InstaEclipse | Interceptor): ⚠️ RealtimeClientManager not found");
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Interceptor): ⚠️ active-status hook: " + t.getMessage());
        }
    }

    /** The IDLE constant of IG's PresenceStatus enum, read from its static fields (no values() needed). */
    private static Enum<?> presenceIdleOf(Class<?> statusEnum) {
        Enum<?> found = presenceIdle;
        if (found != null && found.getDeclaringClass() == statusEnum) return found;
        for (Field f : statusEnum.getDeclaredFields()) {
            if (f.getType() != statusEnum || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof Enum && "IDLE".equals(((Enum<?>) v).name())) {
                    found = (Enum<?>) v;
                    presenceIdle = found;
                    return found;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public void handleInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader classLoader = lpparam.classLoader;

            // Freeze "last seen" (the live "active now" green dot). Modern IG reports presence by
            // constructing a PresenceWriteRequest with an ACTIVE status, not over HTTP, so the URL
            // drops below can't stop it. Swap outgoing ACTIVE -> IDLE instead. See hookActiveStatus.
            hookActiveStatus(classLoader);

            // Locate the TigonServiceLayer class dynamically
            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = ps.reso.instaeclipse.utils.core.SafeReflect.methods(tigonClass);

            Class<?> random_param_1 = null;
            Class<?> random_param_2 = null;
            Class<?> random_param_3 = null;
            String uriFieldName = null;

            // Analyze methods in TigonServiceLayer
            for (Method method : methods) {
                if (method.getName().equals("startRequest") && method.getParameterCount() == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    random_param_1 = paramTypes[0];
                    random_param_2 = paramTypes[1];
                    random_param_3 = paramTypes[2];
                    break;
                }
            }

            // Dynamically identify the URI field in the request object
            if (random_param_1 != null) {
                for (Field field : ps.reso.instaeclipse.utils.core.SafeReflect.fields(random_param_1)) {
                    if (field.getType().equals(URI.class)
                            && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        uriFieldName = field.getName();
                        break;
                    }
                }
            }

            // If classes and fields are resolved, hook the method
            if (random_param_1 != null && random_param_2 != null && random_param_3 != null && uriFieldName != null) {
                String finalUriFieldName = uriFieldName;
                XC_MethodHook dropHook = new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                              try {
                                Object requestObj = param.args[0];
                                URI uri = (URI) XposedHelpers.getObjectField(requestObj, finalUriFieldName);

                                uri = redirectToCopiedProfile(requestObj, finalUriFieldName, uri);

                                if (uri != null && uri.getPath() != null) {
                                    final String host = uri.getHost(); // null for opaque/relative URIs — guard before use
                                    boolean shouldDrop = false;


                                    // Don't save searches (#280): the server keeps Recent from these
                                    if (FeatureFlags.disableSearchHistory) {
                                        shouldDrop |= uri.getPath().contains("/fbsearch/register_recent_search_click/");
                                    }

                                    // Ghost Mode URIs
                                    if (FeatureFlags.isGhostSeen) {
                                        shouldDrop |= uri.getPath().contains("/threads/") && uri.getPath().contains("/opened")
                                                && !ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist.pathMentionsAllowed(uri.getPath());
                                    }
                                    if (FeatureFlags.keepEphemeralMessages) {
                                        shouldDrop |= uri.getPath().contains("/mark_ephemeral_item_ranges_viewed");
                                    }
                                    if (FeatureFlags.isGhostScreenshot) {
                                        shouldDrop |= uri.getPath().endsWith("/screenshot/") || uri.getPath().endsWith("/ephemeral_screenshot/");
                                    }
                                    if (FeatureFlags.isGhostViewOnce) {
                                        shouldDrop |= uri.getPath().endsWith("/item_replayed/");
                                        shouldDrop |= (uri.getPath().contains("/direct") && uri.getPath().endsWith("/item_seen/"));
                                        shouldDrop |= uri.getPath().contains("/visual_threads/") && uri.getPath().endsWith("/seen/");
                                    }
                                    if (FeatureFlags.isGhostStory) {
                                        // Version-agnostic: getPath() excludes the ?reel=... query, so it
                                        // reads /api/vN/media/seen/ on both old (v2) and new (447: v1) builds.
                                        shouldDrop |= uri.getPath().contains("/media/seen/");
                                        FeatureStatusTracker.setHooked("GhostStories");
                                    }
                                    if (FeatureFlags.isGhostLive) {
                                        shouldDrop |= uri.getPath().contains("/heartbeat_and_get_viewer_count/");
                                        FeatureStatusTracker.setHooked("GhostLive");
                                    }
                                    // Remove Meta AI (#179): stop Meta AI from replying/loading.
                                    if (FeatureFlags.removeMetaAI) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/ig_meta_ai_side_chat_send_contextual_query/")
                                                || p.contains("/ig_meta_ai_side_chat_new_session/")
                                                || p.contains("/create_ig_meta_ai_side_chat/")
                                                || p.contains("/genai/response")
                                                // AI bots offered in the Messages search (IG 452)
                                                || p.contains("/direct_v2/search_gen_ai_bots/")
                                                || (uri.getHost() != null && uri.getHost().contains("aistudio.instagram.com"));
                                    }

                                    // Distraction Free
                                    if (FeatureFlags.disableStories) {
                                        shouldDrop |= uri.getPath().contains("/feed/reels_tray/")
                                                || uri.getPath().contains("feed/get_latest_reel_media/")
                                                || uri.getPath().contains("direct_v2/pending_inbox/?visual_message")
                                                || uri.getPath().contains("stories/hallpass/")
                                                || uri.getPath().contains("/api/v1/feed/reels_media_stream/");
                                    }
                                    if (FeatureFlags.disableFeed) {
                                        shouldDrop |= uri.getPath().endsWith("/feed/timeline/");
                                    }
                                    // disableReelsExceptDM: when ON, DM-opened reels (/api/v1/clips/items/)
                                    // are allowed and only the reels feed/discover is dropped; when OFF,
                                    // full Disable Reels drops every clips endpoint including clips/items/.
                                    if (FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) {
                                        shouldDrop |= uri.getPath().endsWith("/qp/batch_fetch/")
                                                || uri.getPath().contains("api/v1/clips")
                                                || uri.getPath().contains("clips")
                                                || uri.getPath().contains("mixed_media")
                                                || uri.getPath().contains("mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableReelsExceptDM) {
                                        if (uri.getPath().startsWith("/api/v1/direct_v2/")) {
                                            return;
                                        }
                                        shouldDrop |= (uri.getPath().startsWith("/api/v1/clips/") && uri.getQuery() != null
                                                && (uri.getQuery().contains("next_media_ids=")
                                                || uri.getQuery().contains("max_id=")))
                                                || uri.getPath().contains("/clips/discover/")
                                                || uri.getPath().contains("/mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableExplore) {
                                        shouldDrop |= uri.getPath().contains("/discover/topical_explore")
                                                || uri.getPath().contains("/discover/topical_explore_stream")
                                                || (host != null && host.contains("i.instagram.com") && uri.getPath().contains("/api/v1/fbsearch/top_serp/"));
                                    }
                                    // Explore cleanup: hide the Explore grid. The Search tab's grid is the
                                    // topical_explore fetch (and its stream variant); search, recent searches
                                    // and search results are other endpoints and stay. Fail-open.
                                    if (FeatureFlags.hideExploreGrid) {
                                        shouldDrop |= uri.getPath().contains("/discover/topical_explore");
                                    }
                                    // Explore cleanup: hide search suggestions. The "Suggested for you" null
                                    // state shown when the search bar is tapped before typing comes from
                                    // nullstate_dynamic_sections / accounts_recs; typed search and recent
                                    // searches use other endpoints and stay. Fail-open.
                                    if (FeatureFlags.hideSearchSuggestions) {
                                        shouldDrop |= uri.getPath().contains("/fbsearch/nullstate_dynamic_sections/")
                                                || uri.getPath().contains("/fbsearch/accounts_recs/");
                                    }
                                    // Suggested accounts under the DM inbox (#282): the inbox's
                                    // "empty state" unit carries them; the chat list is unaffected.
                                    if (FeatureFlags.hideDmSuggestions) {
                                        shouldDrop |= uri.getPath().contains("/dynamic_onboarding/get_direct_empty_state/");
                                    }
                                    if (FeatureFlags.disableComments) {
                                        shouldDrop |= uri.getPath().contains("/api/v1/media/") && uri.getPath().contains("comments/");
                                    }

                                    // Ads
                                    if (FeatureFlags.isAdBlockEnabled) {
                                        shouldDrop |= uri.getPath().contains("profile_ads/get_profile_ads/")
                                                || uri.getPath().contains("/async_ads/")
                                                || uri.getPath().contains("/feed/injected_reels_media/")
                                                || uri.getPath().equals("/api/v1/ads/graphql/");
                                    }

                                    // Analytics
                                    if (FeatureFlags.isAnalyticsBlocked) {
                                        if (ps.reso.instaeclipse.utils.core.Pace.on()) {
                                            // Lower ban risk: let normal telemetry keep flowing so the
                                            // account doesn't look headless; drop only the heavy event log.
                                            shouldDrop |= uri.getPath().contains("/logging_client_events");
                                        } else {
                                            shouldDrop |= (host != null && (host.contains("graph.instagram.com")
                                                    || host.contains("graph.facebook.com")))
                                                    || uri.getPath().contains("/logging_client_events");
                                        }
                                    }

                                    // Misc
                                    if (FeatureFlags.spoofLastSeen) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/push/setForegroundState/")
                                                || p.contains("/accounts/update_active_status")
                                                || p.contains("/notes/create_note")
                                                || p.contains("/accounts/set_presence_disabled")
                                                || p.contains("/update_active_status")
                                                || p.contains("/banyan/banyan/")
                                                || p.endsWith("/last_active/")
                                                || p.contains("/presence/");
                                        FeatureStatusTracker.setHooked("SpoofLastSeen");
                                    }
                                    // Disable Repost is handled at the UI/action level in
                                    // mods.ui.DisableRepostHook, not here: IG applies the repost
                                    // optimistically client-side, so dropping the request doesn't stop it.
                                    if (FeatureFlags.disableDiscoverPeople) {
                                        shouldDrop |= uri.getPath().contains("/discover/ayml/");
                                        shouldDrop |= uri.getPath().contains("discover/chaining/");
                                        FeatureStatusTracker.setHooked("DisableDiscoverPeople");
                                    }

                                    if (shouldDrop) {
                                        neutralizeUrlFields(requestObj, uri);
                                    }

                                    // Follow status
                                    if (FeatureFlags.showFollowerToast || FeatureFlags.profileFollowsYouLabel) {
                                        FeatureStatusTracker.setHooked("FollowerToast");
                                        FollowStatusHook.handleRequest(uri, param.args);
                                    }
                                }
                              } catch (Throwable ignored) {
                                  // A malformed/opaque request must never crash IG's network dispatch.
                              }
                            }
                        };
                XposedHelpers.findAndHookMethod("com.instagram.api.tigon.TigonServiceLayer", classLoader, "startRequest",
                        random_param_1, random_param_2, random_param_3, dropHook);
                hookChainEntry(classLoader, random_param_1, random_param_2, random_param_3, dropHook);
                // These act on every matching request, so they are in place once the hook is.
                for (String key : new String[]{"BlockAnalytics", "DisableStoriesSection", "DisableFeedSection",
                        "DisableReelsSection", "DisableExploreSection", "DisableCommentsSection", "HideDmSuggestions",
                        "HideExploreGrid", "HideSearchSuggestions"})
                    FeatureStatusTracker.setHooked(key);
            } else {
                ModuleLog.line("(InstaEclipse | Interceptor): Could not resolve required classes or fields.");
            }

        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | Interceptor): ❌ " + e.getMessage());
        }
    }

    /** Your followers and following lists, opened from your profile counts, go to the copied profile (read only). */
    private static URI moveRequest(Object request, URI uri, String movedPath, String what) throws Exception {
        URI next = new URI(uri.getScheme(), uri.getAuthority(), movedPath, uri.getQuery(), uri.getFragment());
        for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (f.getType() != URI.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (uri.equals(f.get(request))) f.set(request, next);
            }
        }
        ModuleLog.line("(IE|ProfileSpoof) read redirected: " + what);
        return next;
    }

    /** Profile queries carry your id in the request body: send the copied account's instead. */
    private static void swapBody(Object request, String self, String target) {
        try {
            for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()
                            || f.getType() == String.class || f.getType() == URI.class) continue;
                    f.setAccessible(true);
                    Object holder = f.get(request);
                    if (holder == null || holder.getClass().getName().startsWith("java.")) continue;
                    for (Field bf : ps.reso.instaeclipse.utils.core.SafeReflect.fields(holder.getClass())) {
                        if (bf.getType() != byte[].class || java.lang.reflect.Modifier.isStatic(bf.getModifiers())) continue;
                        bf.setAccessible(true);
                        byte[] body = (byte[]) bf.get(holder);
                        if (body == null) continue;
                        String text = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
                        if (!text.contains(self)) continue;
                        // Reads only: a mutation always goes out as sent.
                        if (text.toLowerCase(java.util.Locale.ROOT).contains("mutation")) continue;
                        bf.set(holder, text.replace(self, target).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                        ModuleLog.line("(IE|ProfileSpoof) read redirected: graphql body");
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileSpoof) body swap failed: " + t.getMessage());
        }
    }

    private static URI redirectToCopiedProfile(Object request, String field, URI uri) {
        try {
            String target = FeatureFlags.spoofTargetId;
            String self = ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.selfId();
            if (!FeatureFlags.profileSpoofer || target == null || target.isEmpty() || self == null || self.equals(target)) return uri;
            String path = uri.getPath();
            if (path == null) return uri;
            boolean armed = ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.listRedirectArmed();
            if (ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.infoRedirectArmed() && path.endsWith("/users/" + self + "/info/")) {
                return moveRequest(request, uri, path.replace("/" + self + "/", "/" + target + "/"), "info");
            }
            boolean profile = ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.infoRedirectArmed();
            if (profile && path.endsWith("/users/" + self + "/info_stream/")) {
                return moveRequest(request, uri, path.replace("/" + self + "/", "/" + target + "/"), "info_stream");
            }
            if (profile && path.endsWith("/wwwgraphql/ig/query/")) swapBody(request, self, target);
            String[] reads = armed
                    ? new String[]{"/friendships/" + self + "/followers/", "/friendships/" + self + "/following/",
                            "/usertags/" + self + "/feed/", "/feed/user/" + self + "/", "/feed/user/" + self + "/story/"}
                    : new String[]{"/usertags/" + self + "/feed/", "/feed/user/" + self + "/", "/feed/user/" + self + "/story/"};
            for (String read : reads) {
                if (!path.endsWith(read)) continue;
                return moveRequest(request, uri, path.replace("/" + self + "/", "/" + target + "/"), read);
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileSpoof) redirect failed: " + t.getMessage());
        }
        return uri;
    }
}
