package com.idvb.android.data

import com.idvb.android.account.OnlineEndpoints
import java.net.HttpURLConnection
import java.net.URL

internal class CommunityObjectAuthorization private constructor(
    private val publication: String,
    private val header: String,
) {
    fun apply(connection: HttpURLConnection) {
        val url = connection.url
        val origin = URL(OnlineEndpoints.ORIGIN)
        require(url.protocol == "https" && url.host.equals(origin.host, true) && url.port == -1 &&
            url.userInfo == null && url.query == null && url.ref == null &&
            url.path.startsWith("/api/maps/subscriptions/$publication/") &&
            !url.path.contains("..") && !url.path.contains('%'))
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Authorization", header)
    }

    companion object {
        fun grant(publication: String, grantId: String): CommunityObjectAuthorization {
            require(Regex("g_[A-Za-z0-9_-]{8,64}").matches(grantId))
            return CommunityObjectAuthorization(publication, "IDVB-Grant $grantId")
        }

        fun account(publication: String, token: String): CommunityObjectAuthorization {
            require(Regex("[A-Za-z0-9_-]{43}").matches(token))
            return CommunityObjectAuthorization(publication, "Bearer $token")
        }
    }
}
