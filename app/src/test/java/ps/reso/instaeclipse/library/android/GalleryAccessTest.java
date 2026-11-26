package ps.reso.instaeclipse.library.android;

import org.junit.Test;

import static org.junit.Assert.*;

public final class GalleryAccessTest {
    @Test public void grantCarriesExactScopeAndCanOnlyBeRedeemedOnce() {
        String token = GalleryAccess.issue(new GalleryAccess.Grant("com.instagram.android", "123", "456", 42L));
        assertNull(GalleryAccess.redeem("unknown", 43L));
        GalleryAccess.Grant grant = GalleryAccess.redeem(token, 43L);
        assertEquals("com.instagram.android", grant.packageName);
        assertEquals("123", grant.accountKey);
        assertEquals("456", grant.threadKey);
        assertNull(GalleryAccess.redeem(token, 43L));
    }

    @Test public void expirationAndClockRollbackFailClosed() {
        GalleryAccess.Grant grant = new GalleryAccess.Grant("com.instagram.android", "123", "456", 100L);
        assertNull(GalleryAccess.redeem(GalleryAccess.issue(grant), 100L + GalleryAccess.TTL_MS));
        assertNull(GalleryAccess.redeem(GalleryAccess.issue(grant), 99L));
    }
}
