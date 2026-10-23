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
        assertEquals("https://idvb.dev/api/maps/subscriptions/id/feed.json",
            OfficialResourceUrls.relocate("https://idvb.dev/api/maps/subscriptions/id/feed.json"))
        assertEquals("https://idvb.dev/downloads/maps/a.idvm",
            OfficialResourceUrls.relocate("https://156.239.51.171/downloads/maps/a.idvm"))
    }
    @Test fun untrustedOrAmbiguousAddressesAreRejected() {
        listOf("http://156.239.51.171/maps/a", "https://evil.test/api/maps/a",
            "https://user@156.239.51.171/api/maps/a", "https://156.239.51.171:4433/api/maps/a",
            "https://download.xgflee.com/maps/../private", "https://download.xgflee.com/maps/%2e%2e/private",
            "https://community.idvb.xgflee.com/api/maps/a?token=secret",
            "http://idvb.dev/downloads/maps/a.idvm", "https://idvb.dev.evil.test/downloads/maps/a.idvm",
            "https://user@idvb.dev/downloads/maps/a.idvm", "https://idvb.dev:4433/downloads/maps/a.idvm",
            "https://idvb.dev/downloads/maps/%2e%2e/private",
            "https://idvb.dev/api/maps/a?token=secret", "https://idvb.dev/api/maps/a#fragment").forEach {
            assertTrue(it, runCatching { OfficialResourceUrls.relocate(it) }.isFailure)
        }
    }
}
