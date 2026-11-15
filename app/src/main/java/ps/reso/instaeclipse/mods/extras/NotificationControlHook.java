package ps.reso.instaeclipse.mods.extras;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Group / mute Instagram's notifications. Ported from HushGram's NotificationGroups ReVanced patch
 * to an Xposed hook on {@link NotificationManager}.
 *
 * <p>Every {@code NotificationManager.notify} and {@code cancel} Instagram makes is intercepted.
 * While {@link FeatureFlags#groupNotifications} is on, each notification is re-posted in one group
 * (or a group per channel with {@link FeatureFlags#groupNotificationsByType}), and a quiet summary
 * showing the count is posted once a group holds two or more. A notification whose channel id is in
 * {@link FeatureFlags#mutedNotificationChannels} is dropped before it reaches the shade (this works
 * whether grouping is on or off), reaching the promo / betting pushes Instagram sends on channels
 * its own notification settings don't list.
 *
 * <p>Fail-open: with both switches off and nothing muted the hook returns immediately and Instagram
 * posts exactly as it built it. Anything thrown while regrouping falls back to the original post, so
 * a notification is never lost by accident. Needs API 26+ (channels / {@code recoverBuilder}); below
 * that the hook is not installed and Instagram is left untouched.
 *
 * <p>Re-entrancy: the stand-in posts the summary and re-posts children through the same manager,
 * which would re-enter this very hook. A thread-local guard lets those internal calls pass straight
 * through to the real framework method, so there is no recursion.
 */
public final class NotificationControlHook {

    /** The group every notification joins with one group. */
    static final String ONE_GROUP = "instaeclipse_notifications";
    /** The start of each channel's group key with a group per type. */
    static final String TYPE_GROUP = "instaeclipse_notifications_";
    /** The group of a notification with no channel, with a group per type. */
    static final String NO_CHANNEL = "other";
    /** The tag our summaries are posted under, so they never meet Instagram's own ids. */
    static final String SUMMARY_TAG = "instaeclipse_notification_group";
    /** Our own channels, which are never offered or muted. */
    public static final String OWN_CHANNELS = "instaeclipse_";
    /** What a notification channel id looks like in the muted list: no commas or spaces. */
    static final String CHANNEL_ID = "[^,\\s]{1,128}";
    private static final Pattern CHANNEL = Pattern.compile(CHANNEL_ID);

    /** A group gets its summary once it has this many notifications. */
    static final int SUMMARY_FROM = 2;
    /** Maximum accepted identities not yet returned by Android's active/enqueued snapshot. */
    static final int MAX_PENDING_POSTS = 256;
    static final String POST_GENERATION = "instaeclipse_notification_generation";

    private static final String PROCESS = UUID.randomUUID().toString();
    private static long nextGeneration;
    private static final Map<Name, Pending> pending = new LinkedHashMap<>();
    /** Overflow or an unmarked owned post leaves summaries in place for the rest of this process. */
    private static boolean uncertain;

    private static final Set<String> mutedLogged = new HashSet<>();
    private static volatile boolean logged;

    /** Our own re-posts / summaries re-enter the hook; this lets them through to the framework. */
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    // ───────────────────────── install ─────────────────────────

    public void install(ClassLoader classLoader) {
        // Channels, getChannelId(), recoverBuilder() and getActiveNotifications() all need API 26+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        XposedHelpers.findAndHookMethod(NotificationManager.class, "notify",
                int.class, Notification.class, new NotifyHook(false));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "notify",
                String.class, int.class, Notification.class, new NotifyHook(true));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel",
                int.class, new CancelHook(false));
        XposedHelpers.findAndHookMethod(NotificationManager.class, "cancel",
                String.class, int.class, new CancelHook(true));

        FeatureStatusTracker.setHooked("groupNotifications");
        ModuleLog.line("(InstaEclipse | NotificationControl): ✅ hooked");
    }

    /** True when there is nothing to do, so the original call should run untouched (fail-open). */
    private static boolean idle() {
        return !FeatureFlags.groupNotifications && muted().isEmpty();
    }

    private final class NotifyHook extends XC_MethodHook {
        private final boolean tagged;
        NotifyHook(boolean tagged) { this.tagged = tagged; }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
            if (Boolean.TRUE.equals(inside.get())) return; // our own post: let the framework run it
            try {
                if (idle() || !(p.thisObject instanceof NotificationManager)) return;
                NotificationManager manager = (NotificationManager) p.thisObject;
                String tag = tagged ? (String) p.args[0] : null;
                int id = tagged ? (int) p.args[1] : (int) p.args[0];
                Notification notification = (Notification) p.args[tagged ? 2 : 1];
                inside.set(Boolean.TRUE);
                try {
                    post(manager, tag, id, notification);
                } finally {
                    inside.set(Boolean.FALSE);
                }
                // We've posted (or deliberately dropped) it ourselves; skip Instagram's call.
                p.setResult(null);
            } catch (Throwable ignored) {
                inside.set(Boolean.FALSE);
                // Fail open: on any failure let Instagram's original notify run unchanged.
            }
        }
    }

    private final class CancelHook extends XC_MethodHook {
        private final boolean tagged;
        CancelHook(boolean tagged) { this.tagged = tagged; }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
            if (Boolean.TRUE.equals(inside.get())) return;
            try {
                if (!FeatureFlags.groupNotifications || !(p.thisObject instanceof NotificationManager)) return;
                NotificationManager manager = (NotificationManager) p.thisObject;
                String tag = tagged ? (String) p.args[0] : null;
                int id = tagged ? (int) p.args[1] : (int) p.args[0];
                inside.set(Boolean.TRUE);
                try {
                    withdraw(manager, tag, id);
                } finally {
                    inside.set(Boolean.FALSE);
                }
                p.setResult(null);
            } catch (Throwable ignored) {
                inside.set(Boolean.FALSE);
            }
        }
    }

    // ───────────────────────── posting ─────────────────────────

    static synchronized void post(NotificationManager manager, String tag, int id, Notification notification) {
        try {
            String channel = notification == null ? null : notification.getChannelId();
            if (channel != null && muted().contains(channel)) {
                if (mutedLogged.add(channel)) {
                    ModuleLog.line("(InstaEclipse | NotificationControl): muted " + channel
                            + ", kept a notification out of the shade");
                }
                return; // dropped: never shown, never counted toward a group
            }
        } catch (Throwable ignored) {
        }
        String group = null;
        Notification posted = notification;
        String generation = null;
        boolean grouping = false;
        try {
            grouping = FeatureFlags.groupNotifications;
            if (grouping && notification != null && !isOngoing(notification)) {
                if (isSummary(notification)) {
                    // Every child is in our groups now, so Instagram's summary would stand alone.
                    return;
                }
                group = groupFor(notification, FeatureFlags.groupNotificationsByType);
                generation = PROCESS + ":" + ++nextGeneration;
                posted = regrouped(notification, group, generation);
            }
        } catch (Throwable ignored) {
            group = null;
            posted = notification;
            generation = null;
        }
        manager.notify(tag, id, posted);
        remember(tag, id, posted, generation);
        tidy(manager, grouping, group == null ? null : posted, group);
    }

    static synchronized void withdraw(NotificationManager manager, String tag, int id) {
        manager.cancel(tag, id);
        pending.remove(new Name(tag, id));
        boolean grouping = false;
        try {
            grouping = FeatureFlags.groupNotifications;
        } catch (Throwable ignored) {
        }
        tidy(manager, grouping, null, null);
    }

    private static void tidy(NotificationManager manager, boolean grouping,
                             Notification posted, String group) {
        try {
            if (grouping) {
                recount(manager, posted, group);
            } else {
                clearSummaries(manager);
            }
        } catch (Throwable ignored) {
        }
    }

    static String groupFor(Notification notification, boolean byType) {
        if (!byType) return ONE_GROUP;
        String channel = notification.getChannelId();
        return TYPE_GROUP + (channel == null || channel.isEmpty() ? NO_CHANNEL : channel);
    }

    static boolean isSummary(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    static boolean isOngoing(Notification notification) {
        return (notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
    }

    private static Notification regrouped(Notification notification, String group, String generation) {
        Context context = AndroidAppHelper.currentApplication();
        Notification owned = notification.clone();
        owned.extras = notification.extras == null ? new Bundle() : new Bundle(notification.extras);
        owned.extras.putString(POST_GENERATION, generation);
        Notification copy = Notification.Builder.recoverBuilder(context, owned).setGroup(group).build();
        if (!logged) {
            logged = true;
            ModuleLog.line("(InstaEclipse | NotificationControl): posted a notification in " + group);
        }
        return copy;
    }

    static synchronized void recount(NotificationManager manager, Notification posted, String group) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Sample> newest = new HashMap<>();
        Map<String, Long> newestAt = new HashMap<>();
        Map<String, Notification> summaries = new HashMap<>();
        Set<String> occupied = new HashSet<>();
        for (StatusBarNotification shown : observed(manager)) {
            Notification active = shown.getNotification();
            if (SUMMARY_TAG.equals(shown.getTag())) {
                if (active.getGroup() != null) summaries.put(active.getGroup(), active);
                continue;
            }
            String in = active.getGroup();
            if (in != null && !isSummary(active)) occupied.add(in);
            Name name = new Name(shown.getTag(), shown.getId());
            if (pending.containsKey(name)) continue;
            if (in == null || !in.startsWith(ONE_GROUP) || isSummary(active)) continue;
            counts.merge(in, 1, Integer::sum);
            if (shown.getPostTime() >= newestAt.getOrDefault(in, Long.MIN_VALUE)) {
                newestAt.put(in, shown.getPostTime());
                newest.put(in, new Sample(active));
            }
        }
        for (Map.Entry<Name, Pending> entry : pending.entrySet()) {
            Pending post = entry.getValue();
            counts.merge(post.group, 1, Integer::sum);
            if (post.at >= newestAt.getOrDefault(post.group, Long.MIN_VALUE)) {
                newestAt.put(post.group, post.at);
                newest.put(post.group, post.sample);
            }
        }
        if (posted != null && group != null) {
            counts.merge(group, 1, Integer::sum);
            newest.put(group, new Sample(posted));
        }
        Set<String> groups = new HashSet<>(summaries.keySet());
        if (group != null) groups.add(group);
        for (String each : groups) {
            int count = counts.getOrDefault(each, 0);
            Notification summary = summaries.get(each);
            if (count == 0) {
                if (summary != null && !occupied.contains(each) && !protectedGroup(each)) {
                    manager.cancel(SUMMARY_TAG, each.hashCode());
                }
            } else if ((summary != null || count >= SUMMARY_FROM) && (summary == null || summary.number != count)) {
                summarize(manager, each, newest.get(each), count);
            }
        }
    }

    private static void summarize(NotificationManager manager, String group, Sample newest, int count) {
        Context context = AndroidAppHelper.currentApplication();
        Notification.Builder summary = new Notification.Builder(context, newest.channel);
        if (newest.icon != null) {
            summary.setSmallIcon(newest.icon);
        } else {
            summary.setSmallIcon(context.getApplicationInfo().icon);
        }
        summary.setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                .setContentText(quantity(count))
                .setNumber(count)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        manager.notify(SUMMARY_TAG, group.hashCode(), summary.build());
    }

    static synchronized int clearSummaries(NotificationManager manager) {
        StatusBarNotification[] active = observed(manager);
        Map<String, Integer> counts = new HashMap<>();
        Set<String> occupied = new HashSet<>();
        for (StatusBarNotification shown : active) {
            if (SUMMARY_TAG.equals(shown.getTag())) continue;
            Notification notification = shown.getNotification();
            String group = notification.getGroup();
            if (group == null || isSummary(notification)) continue;
            occupied.add(group);
            if (pending.containsKey(new Name(shown.getTag(), shown.getId()))) continue;
            counts.merge(group, 1, Integer::sum);
        }
        for (Pending post : pending.values()) counts.merge(post.group, 1, Integer::sum);
        int gone = 0;
        for (StatusBarNotification shown : active) {
            if (!SUMMARY_TAG.equals(shown.getTag())) continue;
            Notification summary = shown.getNotification();
            String group = summary.getGroup();
            int count = counts.getOrDefault(group, 0);
            if (count > 0 || occupied.contains(group) || protectedGroup(group)) {
                if (count > 0 && summary.number != count) {
                    Notification updated = Notification.Builder.recoverBuilder(AndroidAppHelper.currentApplication(), summary)
                            .setContentText(quantity(count))
                            .setNumber(count).build();
                    manager.notify(SUMMARY_TAG, shown.getId(), updated);
                }
                continue;
            }
            manager.cancel(SUMMARY_TAG, shown.getId());
            gone++;
        }
        return gone;
    }

    /**
     * Called when grouping is switched off in settings: remove empty summaries off the main thread.
     * Groups that still have children keep their summary, without touching any native notification.
     */
    public static void switchedOff(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        new Thread(() -> {
            if (Boolean.TRUE.equals(inside.get())) return;
            inside.set(Boolean.TRUE);
            try {
                int gone = clearSummaries(manager);
                if (gone > 0) ModuleLog.line("(InstaEclipse | NotificationControl): took down " + gone + " summaries");
            } catch (Throwable ignored) {
            } finally {
                inside.set(Boolean.FALSE);
            }
        }, "InstaEclipse-NotifCleanup").start();
    }

    private static void remember(String tag, int id, Notification notification, String generation) {
        Name name = new Name(tag, id);
        pending.remove(name);
        if (notification == null || isSummary(notification)) return;
        String group = notification.getGroup();
        if (group == null || !group.startsWith(ONE_GROUP)) return;
        if (generation == null || pending.size() >= MAX_PENDING_POSTS) {
            uncertain = true;
            return;
        }
        pending.put(name, new Pending(generation, notification));
    }

    private static StatusBarNotification[] observed(NotificationManager manager) {
        StatusBarNotification[] active = manager.getActiveNotifications();
        for (StatusBarNotification shown : active) {
            Notification notification = shown.getNotification();
            String generation = notification.extras == null ? null : notification.extras.getString(POST_GENERATION);
            Name name = new Name(shown.getTag(), shown.getId());
            Pending post = pending.get(name);
            if (post != null && post.generation.equals(generation)
                    && post.group.equals(notification.getGroup())) pending.remove(name);
        }
        return active;
    }

    private static boolean protectedGroup(String group) {
        if (uncertain) return true;
        for (Pending post : pending.values()) if (post.group.equals(group)) return true;
        return false;
    }

    // ───────────────────────── muted channels ─────────────────────────

    private static Set<String> muted() {
        try {
            return mutedChannels(FeatureFlags.mutedNotificationChannels);
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    /**
     * The channel ids in a saved muted list, in order. Blank or malformed entries and our own
     * channels are dropped, so a hand-edited backup can't mute what it shouldn't.
     */
    public static Set<String> mutedChannels(String saved) {
        Set<String> ids = new TreeSet<>();
        if (saved == null) return ids;
        for (String part : saved.split(",")) {
            String id = part.trim();
            if (CHANNEL.matcher(id).matches() && !id.startsWith(OWN_CHANNELS)) ids.add(id);
        }
        return ids;
    }

    /** [ids] as the setting saves them: in order, commas between. */
    public static String joinChannels(Set<String> ids) {
        return String.join(",", new TreeSet<>(ids));
    }

    /**
     * Instagram's notification channels on this phone as {id, label}, by label, for the mute list.
     * A label two channels share gets its id after it. Muted ids whose channel is gone come last,
     * labelled by id, so they can still be unmuted.
     */
    public static List<String[]> offeredChannels(Context context, Set<String> muted) {
        List<String[]> offered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return offered;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            for (NotificationChannel channel : manager.getNotificationChannels()) {
                String id = channel.getId();
                if (id == null || id.startsWith(OWN_CHANNELS) || !CHANNEL.matcher(id).matches() || !seen.add(id)) continue;
                CharSequence name = channel.getName();
                String label = name == null ? "" : name.toString().trim();
                offered.add(new String[]{id, label.isEmpty() ? id : label});
            }
        }
        Map<String, Integer> labels = new HashMap<>();
        for (String[] each : offered) labels.merge(each[1], 1, Integer::sum);
        for (String[] each : offered) {
            if (labels.get(each[1]) > 1) each[1] = each[1] + " (" + each[0] + ")";
        }
        offered.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a[1], b[1]));
        for (String id : new TreeSet<>(muted)) {
            if (seen.add(id)) offered.add(new String[]{id, id});
        }
        return offered;
    }

    private static String quantity(int count) {
        return count == 1 ? count + " notification" : count + " notifications";
    }

    // ───────────────────────── value types ─────────────────────────

    private static final class Name {
        final String tag;
        final int id;

        Name(String tag, int id) {
            this.tag = tag;
            this.id = id;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Name)) return false;
            Name name = (Name) other;
            return id == name.id && Objects.equals(tag, name.tag);
        }

        @Override public int hashCode() { return 31 * Objects.hashCode(tag) + id; }
    }

    private static final class Sample {
        final String channel;
        final Icon icon;

        Sample(Notification notification) {
            channel = notification.getChannelId();
            icon = notification.getSmallIcon();
        }
    }

    private static final class Pending {
        final String generation;
        final String group;
        final Sample sample;
        final long at;

        Pending(String generation, Notification notification) {
            this.generation = generation;
            group = notification.getGroup();
            sample = new Sample(notification);
            at = System.currentTimeMillis();
        }
    }
}
