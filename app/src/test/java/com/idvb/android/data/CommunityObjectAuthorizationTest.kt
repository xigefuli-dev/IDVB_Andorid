package com.idvb.android.data

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class CommunityObjectAuthorizationTest {
    private val publication = "3f2a9c1e-7b44-4d0a-9e61-2c5d8b7f0a13"
    private val grantId = "g_1234567890abcdef"

    private fun connection(address: String, expected: String) = object : HttpURLConnection(URL(address)) {
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy() = false
        override fun getResponseCode() = if (getRequestProperty("Authorization") == expected) 200 else 401
    }

    @Test fun feedAndEncryptedPackageBothReceiveTheVerifiedGrant() {
        val authorization = CommunityObjectAuthorization.grant(publication, grantId)
        for (filename in listOf("feed.json", "maps-TEST.idvm.secure")) {
            val request = connection("https://idvb.dev/api/maps/subscriptions/$publication/$filename", "IDVB-Grant $grantId")
            assertEquals(401, request.responseCode)
            authorization.apply(request)
            assertEquals(200, request.responseCode)
            assertFalse(request.instanceFollowRedirects)
            assertFalse(request.useCaches)
        }
    }

    @Test fun legacySubscriptionUsesTheAccountForBothProtectedObjects() {
        val token = "a".repeat(43)
        val authorization = CommunityObjectAuthorization.account(publication, token)
        for (filename in listOf("feed.json", "maps-TEST.idvm.secure")) {
            val request = connection("https://idvb.dev/api/maps/subscriptions/$publication/$filename", "Bearer $token")
            authorization.apply(request)
            assertEquals(200, request.responseCode)
        }
    }

    @Test fun authorizationCannotLeakToAnotherHostOrPublication() {
        val authorization = CommunityObjectAuthorization.grant(publication, grantId)
        val valid = "https://idvb.dev/api/maps/subscriptions/$publication/feed.json"
        for (url in listOf(valid.replace("idvb.dev", "other.invalid"), valid.replace("https:", "http:"),
            valid.replace(publication, "00000000-0000-0000-0000-000000000001"),
            valid.replace("feed.json", "../feed.json"), "$valid?redirect=other", "$valid#fragment",
            valid.replace("idvb.dev", "idvb.dev:444"), valid.replace("feed.json", "%2e%2e/feed.json"))) {
            val request = connection(url, "IDVB-Grant $grantId")
            assertThrows(IllegalArgumentException::class.java) { authorization.apply(request) }
            assertNull(request.getRequestProperty("Authorization"))
        }
    }
}
