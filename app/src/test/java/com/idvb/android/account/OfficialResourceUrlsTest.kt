package com.idvb.android.account

import org.junit.Assert.*
import org.junit.Test

class OfficialResourceUrlsTest {
    @Test fun oldFeedsAndDownloadsRelocateWithoutChangingResourceIdentity() {
        assertEquals("${OnlineEndpoints.ORIGIN}/api/maps/subscriptions/id/feed.json",
            OfficialResourceUrls.relocate("https://community.idvb.xgflee.com/api/maps/subscriptions/id/feed.json"))
        assertEquals("${OnlineEndpoints.ORIGIN}/downloads/maps/a.idvm",
            OfficialResourceUrls.relocate("https://download.xgflee.com/maps/a.idvm"))
        assertEquals("${OnlineEndpoints.ORIGIN}/downloads/maps/a.idvm",
            OfficialResourceUrls.relocate("${OnlineEndpoints.ORIGIN}/downloads/maps/a.idvm"))
    }
    @Test fun untrustedOrAmbiguousAddressesAreRejected() {
        listOf("http://156.239.51.171/maps/a", "https://evil.test/api/maps/a",
            "https://user@156.239.51.171/api/maps/a", "https://156.239.51.171:4433/api/maps/a",
            "https://download.xgflee.com/maps/../private", "https://download.xgflee.com/maps/%2e%2e/private",
            "https://community.idvb.xgflee.com/api/maps/a?token=secret").forEach {
            assertTrue(it, runCatching { OfficialResourceUrls.relocate(it) }.isFailure)
        }
    }
}
