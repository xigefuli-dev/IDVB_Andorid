package com.idvb.android.data

import java.net.URI
import java.util.UUID

internal data class CommunityReference(val id: String, val publisher: String) {
    val canonical get() = "idvb-sub://v2?publication=$id&publisher=$publisher"

    companion object {
        fun parse(text: String): CommunityReference {
            require(text.length <= 8192) { "订阅链接过长" }
            val uri = URI(text)
            require(uri.scheme == "idvb-sub" && uri.rawAuthority == "v2" &&
                uri.rawPath.isNullOrEmpty() && uri.rawFragment == null) { "订阅链接格式无效" }
            val pairs = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            require(pairs.size == 2 && pairs.all { it.size == 2 } &&
                pairs.map { it[0] }.toSet() == setOf("publication", "publisher")) { "订阅参数无效" }
            val fields = pairs.associate { it[0] to it[1] }
            val id = fields.getValue("publication").lowercase()
            require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id) &&
                UUID.fromString(id) != UUID(0, 0)) { "发布 ID 无效" }
            val publisher = fields.getValue("publisher").uppercase()
            require(Regex("[0-9A-F]{64}").matches(publisher)) { "发布者密钥标识无效" }
            return CommunityReference(id, publisher)
        }
    }
}
