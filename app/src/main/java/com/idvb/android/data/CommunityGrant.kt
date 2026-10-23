package com.idvb.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Pinned public trust from the desktop online-services release; no grant/key is persisted. */
internal data class VerifiedCommunityGrant(val contentKey: ByteArray, val grantId: String)

internal object CommunityGrant {
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECE/JlhjRXovSSAetMstdBh7c4I4lCNFsq3lmO4NFA3gKCHT4c0eSXzfqlVUghhF5Mq/+svwkwudU+Kb9YTcSFQ=="
    fun verify(envelopeText: String, publication: String, publisher: String, requestId: String,
        now: Long = System.currentTimeMillis() / 1000): VerifiedCommunityGrant {
        val envelope = Json.parseToJsonElement(envelopeText).jsonObject
        fun field(name: String) = envelope.getValue(name).jsonPrimitive.content
        val kid = field("kid")
        require(field("typ") == "idvb-key-grant+v1" && kid == "subscription-grant-2026-10") { "地图授权来源不受信任" }
        val payloadText = field("payload")
        val signature = Base64.getUrlDecoder().decode(field("signature"))
        require(signature.size == 64) { "授权签名长度无效" }
        fun integer(offset: Int): ByteArray {
            val trimmed = signature.copyOfRange(offset, offset + 32).dropWhile { it == 0.toByte() }.toByteArray()
            val value = if (trimmed.isEmpty()) byteArrayOf(0) else if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val der = integer(0) + integer(32)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))))
        verifier.update("idvb-key-grant+v1.$kid.$payloadText".toByteArray(Charsets.UTF_8))
        require(verifier.verify(byteArrayOf(0x30, der.size.toByte()) + der)) { "地图授权签名无效" }
        val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payloadText), Charsets.UTF_8)).jsonObject
        fun value(name: String) = payload.getValue(name).jsonPrimitive.content
        val issued = value("issuedAt").toLong()
        val refresh = value("refreshAfter").toLong()
        val binding = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(requestId.toByteArray(Charsets.UTF_8)))
        require(value("typ") == "idvb-key-grant+v1" && value("aud") == "idvb-desktop" &&
            value("iss") == "idvb-security" && value("publicationId") == publication &&
            value("publisherKeyId").equals(publisher, true) && value("keyEpoch") == "0" &&
            value("sub").matches(Regex("u_[0-9a-f]{32}")) &&
            value("grantId").matches(Regex("g_[A-Za-z0-9_-]{8,64}")) &&
            issued >= 0 && issued <= now + 300 && refresh > issued && refresh <= issued + 86400 &&
            refresh > now && value("requestBinding") == binding && value("cnf") == "null") { "地图授权与当前请求不一致或已过期" }
        val contentKey = Base64.getUrlDecoder().decode(value("contentKey"))
        require(contentKey.size == 32) { "地图授权密钥无效" }
        return VerifiedCommunityGrant(contentKey, value("grantId"))
    }
}
