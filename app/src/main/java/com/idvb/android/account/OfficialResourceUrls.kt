package com.idvb.android.account

import java.net.URI

/** Explicit host migration; signatures and embedded publisher keys are never rewritten. */
internal object OfficialResourceUrls {
    fun relocate(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            !uri.rawPath.contains('%') && !uri.path.contains("..")) { "官方资源地址无效" }
        val path = when (uri.host?.lowercase()) {
            "community.idvb.xgflee.com" -> {
                require(uri.path.startsWith("/api/maps/"))
                uri.path
            }
            "download.xgflee.com" -> {
                require(uri.path.startsWith("/maps/"))
                "/downloads${uri.path}"
            }
            "idvb.dev", "156.239.51.171" -> uri.path
            else -> error("只允许官方 HTTPS 地图资源")
        }
        return OnlineEndpoints.ORIGIN + path
    }
}
