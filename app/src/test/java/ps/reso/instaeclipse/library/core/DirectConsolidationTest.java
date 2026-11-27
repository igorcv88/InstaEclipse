package ps.reso.instaeclipse.library.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

/**
 * Rows shaped like Instagram 454's {@code direct.db} {@code messages} table (keys confirmed in
 * {@code X.01hN} / {@code X.01gX}; exact nesting still needs a device row dump).
 */
public final class DirectConsolidationTest {
    private static final String ME = "1001";
    private static final String PEER = "2002";
    private static final String THREAD = "340282366841710300949128000000001";

    private static String cdn(String name) {
        return "https://scontent-gru1-1.cdninstagram.com/v/t51/" + name + ".jpg?oh=sig&oe=ABC";
    }

    private static JSONObject imageMedia(String name) throws Exception {
        JSONArray candidates = new JSONArray()
                .put(new JSONObject().put("url", cdn(name + "_small")).put("width", 320).put("height", 320))
                .put(new JSONObject().put("url", cdn(name)).put("width", 1080).put("height", 1350));
        return new JSONObject().put("media_type", 1).put("image_versions2", new JSONObject().put("candidates", candidates));
    }

    private static String row(String id, String sender, String type, JSONObject body) throws Exception {
        JSONObject message = new JSONObject().put("item_id", id).put("item_type", type).put("user_id", sender)
                .put("timestamp", "1700000000000000");
        if ("media".equals(type)) message.put("media", body);
        else message.put("visual_media", body);
        return message.toString();
    }

    /** Fake CDN: serves bytes per URL, counts every request, and can fail or expire on demand. */
    private static final class FakeCdn implements DirectConsolidation.Fetcher {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<String> failing = new ArrayList<>();

        @Override public InputStream open(String url) throws IOException {
            assertTrue("non-CDN request " + url, DirectStoreRow.isCdnUrl(url));
            requested.add(url);
            if (failing.remove(url)) throw new IOException("reset");
            byte[] bytes = objects.get(url);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static List<DirectConsolidation.Offer> offers(DirectStoreRow... rows) {
        List<DirectConsolidation.Offer> out = new ArrayList<>();
        for (DirectStoreRow row : rows) {
            for (DirectStoreRow.Part part : row.parts) {
                out.add(new DirectConsolidation.Offer(row.accountId, row.threadId, part.identity,
                        part.best == null ? null : part.best.url));
            }
        }
        return out;
    }

    @Test public void parsesIdentityDirectionBestCandidateAndCarouselSlides() throws Exception {
        DirectStoreRow photo = DirectStoreRow.parse(ME, THREAD, "29001", "media", 1_700_000_123_456_000L,
                row("29001", PEER, "media", imageMedia("p")));
        assertNotNull(photo);
        assertFalse(photo.parts.get(0).identity.fromMe);
        assertEquals(1_700_000_123_456L, photo.timestampMs);
        assertEquals(cdn("p"), photo.parts.get(0).best.url);
        assertEquals("image", photo.parts.get(0).identity.mediaKind);

        JSONObject video = new JSONObject().put("media_type", 2).put("video_versions", new JSONArray()
                .put(new JSONObject().put("url", "https://instagram.fgru1-1.fna.fbcdn.net/v.mp4").put("width", 720).put("height", 1280)));
        DirectStoreRow mine = DirectStoreRow.parse(ME, THREAD, "29002", "media", 1, row("29002", ME, "media", video));
        assertTrue(mine.parts.get(0).identity.fromMe);
        assertEquals("video", mine.parts.get(0).identity.mediaKind);

        JSONObject carousel = new JSONObject().put("media_type", 8).put("carousel_media",
                new JSONArray().put(imageMedia("c0")).put(imageMedia("c1")));
        DirectStoreRow album = DirectStoreRow.parse(ME, THREAD, "29003", "media", 1, row("29003", PEER, "media", carousel));
        assertEquals(2, album.parts.size());
        assertEquals("29003:1", album.parts.get(1).identity.messageKey);
    }

    @Test public void refusesRowsWhoseJsonContradictsTheColumnsOrLacksASender() throws Exception {
        assertNull(DirectStoreRow.parse(ME, THREAD, "29001", "media", 1, row("29999", PEER, "media", imageMedia("x"))));
        assertNull(DirectStoreRow.parse(ME, THREAD, "29001", "media", 1, row("29001", PEER, "raven_media", imageMedia("x"))));
        assertNull(DirectStoreRow.parse(ME, THREAD, "29001", "media", 1,
                new JSONObject().put("item_id", "29001").put("media", imageMedia("x")).toString()));
        assertNull(DirectStoreRow.parse(ME, THREAD, "29001", "text", 1, row("29001", PEER, "media", imageMedia("x"))));
        assertNull(DirectStoreRow.parse(ME, "", "29001", "media", 1, row("29001", PEER, "media", imageMedia("x"))));
        assertNull(DirectStoreRow.parse(ME, THREAD, "29001", "media", 1, "{not json"));
    }

    @Test public void onlyMediaCdnHostsAreEverAccepted() {
        assertTrue(DirectStoreRow.isCdnUrl("https://scontent.cdninstagram.com/a.jpg"));
        assertTrue(DirectStoreRow.isCdnUrl("https://instagram.fgru1-1.fna.fbcdn.net/a.mp4"));
        assertFalse(DirectStoreRow.isCdnUrl("http://scontent.cdninstagram.com/a.jpg"));
        assertFalse(DirectStoreRow.isCdnUrl("https://i.instagram.com/api/v1/direct_v2/threads/1/items/2/seen/"));
        assertFalse(DirectStoreRow.isCdnUrl("https://evil.example/x.cdninstagram.com/a.jpg"));
        assertFalse(DirectStoreRow.isCdnUrl("https://cdninstagram.com.evil.example/a.jpg"));
        assertFalse(DirectStoreRow.isCdnUrl("https://user@scontent.cdninstagram.com/a.jpg"));
        assertFalse(DirectStoreRow.isCdnUrl(null));
    }

    @Test public void viewOnceItemsAreRecordedButNeverFetched() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            JSONObject once = new JSONObject().put("view_mode", "once").put("media", imageMedia("once"));
            JSONObject replay = new JSONObject().put("view_mode", "replayable").put("media", imageMedia("replay"));
            JSONObject unknownMode = new JSONObject().put("media", imageMedia("unknown"));
            DirectStoreRow a = DirectStoreRow.parse(ME, THREAD, "30001", "visual_media", 1, row("30001", PEER, "visual_media", once));
            DirectStoreRow b = DirectStoreRow.parse(ME, THREAD, "30002", "visual_media", 2, row("30002", PEER, "visual_media", replay));
            DirectStoreRow c = DirectStoreRow.parse(ME, THREAD, "30003", "visual_media", 3, row("30003", PEER, "visual_media", unknownMode));
            assertTrue(a.viewOnce && b.viewOnce && c.viewOnce);
            consolidation.offer(INSTAGRAM, ME, THREAD, offers(a, b, c), false);
            FakeCdn cdn = new FakeCdn();
            cdn.objects.put(cdn("once"), jpeg(1));
            cdn.objects.put(cdn("replay"), jpeg(2));
            cdn.objects.put(cdn("unknown"), jpeg(3));
            DirectConsolidation.RunReport report = consolidation.run(INSTAGRAM, cdn, 50, () -> false);
            assertEquals(0, report.attempted);
            assertTrue(cdn.requested.isEmpty());
            assertTrue(library.listAll(false).isEmpty());
            DirectCoverage.Report coverage = consolidation.coverage().report(INSTAGRAM, ME, null);
            assertEquals(3, coverage.unavailable);
            assertEquals(3, coverage.threads.get(0).viewOnce);
            Long account = library.findAccount(INSTAGRAM, ME);
            assertTrue(library.sql().query("SELECT 1 FROM direct_items WHERE account_id=? AND source_url IS NOT NULL",
                    account).isEmpty());
            assertEquals(3, library.sql().query("SELECT 1 FROM direct_items WHERE last_error=?",
                    DirectConsolidation.VIEW_ONCE_NOT_FETCHED).size());
        }
    }

    @Test public void conventionalItemsAreFetchedOnceAndReofferingIsIdempotent() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            FakeCdn cdn = new FakeCdn();
            List<DirectStoreRow> rows = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String id = "3100" + i;
                rows.add(DirectStoreRow.parse(ME, THREAD, id, "media", i * 1000L,
                        row(id, i % 2 == 0 ? PEER : ME, "media", imageMedia("m" + i))));
                cdn.objects.put(cdn("m" + i), jpeg(100 + i));
            }
            for (int round = 0; round < 3; round++) {
                consolidation.offer(INSTAGRAM, ME, THREAD, offers(rows.toArray(new DirectStoreRow[0])), false);
                fixture.clock.addAndGet(1000);
                consolidation.run(INSTAGRAM, cdn, 50, () -> false);
            }
            assertEquals(6, cdn.requested.size());
            assertEquals(6, library.listChat(INSTAGRAM, ME, THREAD).size());
            assertNotNull(library.lookup(INSTAGRAM, ME, THREAD, "31001", true));
            assertNotNull(library.lookup(INSTAGRAM, ME, THREAD, "31000", false));
            assertEquals("captured", library.lookup(INSTAGRAM, ME, THREAD, "31000", false).origin);
        }
    }

    @Test public void interruptionAndTransientFailureResumeWithoutLossOrDuplicates() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            FakeCdn cdn = new FakeCdn();
            List<DirectStoreRow> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String id = "3200" + i;
                rows.add(DirectStoreRow.parse(ME, THREAD, id, "media", i * 1000L, row(id, PEER, "media", imageMedia("r" + i))));
                cdn.objects.put(cdn("r" + i), jpeg(200 + i));
            }
            MediaLibrary first = fixture.open();
            new DirectConsolidation(first).offer(INSTAGRAM, ME, THREAD, offers(rows.toArray(new DirectStoreRow[0])), true);
            // Paused after three items.
            // The pause is checked between items and on every read; it trips once three are kept.
            DirectConsolidation.RunReport paused = new DirectConsolidation(first).run(INSTAGRAM, cdn, 50,
                    () -> first.listAll(false).size() >= 3);
            assertTrue(paused.cancelled);
            assertEquals(3, paused.imported);
            // A fresh process; the fifth object resets mid-run.
            cdn.failing.add(cdn("r4"));
            MediaLibrary second = fixture.open();
            DirectConsolidation.RunReport broken = new DirectConsolidation(second).run(INSTAGRAM, cdn, 50, () -> false);
            assertTrue(broken.stoppedOnFailure);
            assertEquals(1, broken.imported);
            assertFalse(broken.drained);
            DirectCoverage.Report mid = new DirectConsolidation(second).coverage().report(INSTAGRAM, ME, null);
            assertEquals(4, mid.processed);
            assertFalse(mid.complete());
            // Another process finishes; everything is stored exactly once.
            MediaLibrary third = fixture.open();
            DirectConsolidation.RunReport rest = new DirectConsolidation(third).run(INSTAGRAM, cdn, 50, () -> false);
            assertTrue(rest.drained);
            assertEquals(6, rest.imported);
            assertEquals(10, third.listChat(INSTAGRAM, ME, THREAD).size());
            assertTrue(new DirectConsolidation(third).coverage().report(INSTAGRAM, ME, null).complete());
            assertEquals(11, cdn.requested.size());
        }
    }

    @Test public void expiredUrlsAreUnavailableUntilAFreshUrlIsOffered() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            FakeCdn cdn = new FakeCdn();
            DirectStoreRow stale = DirectStoreRow.parse(ME, THREAD, "33001", "media", 1, row("33001", PEER, "media", imageMedia("old")));
            consolidation.offer(INSTAGRAM, ME, THREAD, offers(stale), true);
            DirectConsolidation.RunReport gone = consolidation.run(INSTAGRAM, cdn, 50, () -> false);
            assertEquals(1, gone.unavailable);
            assertTrue(gone.drained);
            assertFalse(consolidation.coverage().report(INSTAGRAM, ME, null).complete());
            // Running again does not hammer the CDN with the same expired URL.
            consolidation.run(INSTAGRAM, cdn, 50, () -> false);
            assertEquals(1, cdn.requested.size());
            // The client refreshed the message; its new URL is offered and works.
            fixture.clock.addAndGet(60_000);
            DirectStoreRow fresh = DirectStoreRow.parse(ME, THREAD, "33001", "media", 1, row("33001", PEER, "media", imageMedia("new")));
            cdn.objects.put(cdn("new"), jpeg(33001));
            consolidation.offer(INSTAGRAM, ME, THREAD, offers(fresh), true);
            assertEquals(1, consolidation.run(INSTAGRAM, cdn, 50, () -> false).imported);
            assertTrue(consolidation.coverage().report(INSTAGRAM, ME, null).complete());
        }
    }

    @Test public void rowsMissingFromALaterScanKeepTheirCopies() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            FakeCdn cdn = new FakeCdn();
            DirectStoreRow kept = DirectStoreRow.parse(ME, THREAD, "34001", "media", 1, row("34001", PEER, "media", imageMedia("k")));
            cdn.objects.put(cdn("k"), jpeg(34001));
            consolidation.offer(INSTAGRAM, ME, THREAD, offers(kept), false);
            consolidation.run(INSTAGRAM, cdn, 50, () -> false);
            // Instagram evicts or deletes its own cached row (the store deletes direct.db on errors).
            for (int i = 0; i < 3; i++) {
                consolidation.offer(INSTAGRAM, ME, THREAD, new ArrayList<>(), false);
                consolidation.run(INSTAGRAM, cdn, 50, () -> false);
                library.reconcile(true);
            }
            assertArrayEquals(jpeg(34001), java.nio.file.Files.readAllBytes(
                    library.fileOf(library.lookup(INSTAGRAM, ME, THREAD, "34001", false)).toPath()));
        }
    }

    @Test public void offersOutsideThePageScopeAreRejected() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            DirectConsolidation consolidation = new DirectConsolidation(fixture.open());
            DirectStoreRow other = DirectStoreRow.parse(ME, "999", "35001", "media", 1, row("35001", PEER, "media", imageMedia("o")));
            assertThrows(IllegalArgumentException.class,
                    () -> consolidation.offer(INSTAGRAM, ME, THREAD, offers(other), false));
            assertThrows(IllegalArgumentException.class,
                    () -> consolidation.offer(INSTAGRAM, "1002", "999", offers(other), false));
        }
    }

    @Test public void ipcPagesAreRefusedWholeWhenMalformed() {
        List<String> keys = java.util.Arrays.asList("41001", "41002");
        List<String> kinds = java.util.Arrays.asList("image", "video");
        List<String> urls = java.util.Arrays.asList(cdn("a"), "");
        List<String> flags = java.util.Arrays.asList("00", "11");
        long[] ts = {1, 2};
        List<DirectConsolidation.Offer> ok = DirectConsolidation.decode(ME, THREAD, keys, kinds, urls, flags, ts);
        assertEquals(2, ok.size());
        assertNull(ok.get(1).url);
        assertTrue(ok.get(1).identity.fromMe && ok.get(1).identity.viewOnce);
        assertNull(DirectConsolidation.decode(ME, THREAD, keys, kinds, urls, flags, new long[] {1}));
        assertNull(DirectConsolidation.decode(ME, THREAD, keys, kinds, urls, java.util.Arrays.asList("00", "2x"), ts));
        assertNull(DirectConsolidation.decode(ME, THREAD, keys, java.util.Arrays.asList("image", "audio"), urls, flags, ts));
        assertNull(DirectConsolidation.decode(ME, THREAD, java.util.Arrays.asList("41001", "41001"), kinds, urls,
                java.util.Arrays.asList("00", "01"), ts));
        assertNull(DirectConsolidation.decode(ME, THREAD, java.util.Arrays.asList("41001", "bad key"), kinds, urls, flags, ts));
        assertNull(DirectConsolidation.decode("", THREAD, keys, kinds, urls, flags, ts));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= DirectConsolidation.MAX_OFFER_PARTS; i++) many.add("5" + i);
        List<String> image = new ArrayList<>(), none = new ArrayList<>(), zero = new ArrayList<>();
        for (int i = 0; i < many.size(); i++) { image.add("image"); none.add(""); zero.add("00"); }
        assertNull(DirectConsolidation.decode(ME, THREAD, many, image, none, zero, new long[many.size()]));
    }

    @Test public void aVersionOneCatalogMigratesWithoutLosingItems() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            JdbcSql sql = fixture.connection();
            List<LibrarySchema.Step> v1 = new ArrayList<>(LibrarySchema.defaultSteps().subList(0, 1));
            new LibrarySchema(v1, 1).migrate(sql);
            sql.inTransaction(() -> {
                sql.insert("INSERT INTO accounts (package_name, account_key, created_at) VALUES (?,?,?)", INSTAGRAM, ME, 1);
                sql.insert("INSERT INTO blobs (sha256, size_bytes, mime_type, created_at, verified_at) VALUES (?,?,?,?,?)",
                        "a".repeat(64), 10, "image/jpeg", 1, 1);
                sql.insert("INSERT INTO items (uuid, account_id, thread_key, message_key, from_me, media_kind, mime_type, "
                        + "sha256, imported_at, origin) VALUES ('u1', 1, ?, '42001', 0, 'image', 'image/jpeg', ?, 1, 'normal')",
                        THREAD, "a".repeat(64));
                return null;
            });
            assertEquals(1, sql.userVersion());
            MediaLibrary upgraded = fixture.open();
            assertEquals(LibrarySchema.CURRENT_VERSION, sql.userVersion());
            LibraryItem item = upgraded.get("u1");
            assertNotNull(item);
            assertFalse(item.viewOnce);
            assertEquals("normal", item.origin);
            assertTrue(new DirectCoverage(upgraded).report(INSTAGRAM, ME, null).threads.isEmpty());
        }
    }
}
