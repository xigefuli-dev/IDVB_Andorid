package com.idvb.android.account

import java.security.MessageDigest
import java.util.Base64

/** Wire compatibility with the server's native installation-proof contract. */
internal object AuthProof {
    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun message(operation: String, fields: Map<String, String>, issuedAt: Long): ByteArray {
        require(operation in OnlineEndpoints.authPaths)
        require(fields.all { (key, value) -> '\u0000' !in key && '\u0000' !in value })
        val canonical = fields.toSortedMap().entries.joinToString("") { (key, value) -> "$key\u0000$value\u0000" }
        val digest = base64(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        return "idvb-desktop-auth-v1\naccounts.desktop.$operation\n$digest\n$issuedAt".toByteArray(Charsets.UTF_8)
    }

    /** Android Keystore returns DER ECDSA; the server expects fixed-width P1363 r || s. */
    fun p1363(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2)
        var offset = 2
        fun integer(): ByteArray {
            require(offset + 2 <= der.size && der[offset++] == 2.toByte())
            val size = der[offset++].toInt() and 255
            require(size in 1..33 && offset + size <= der.size)
            val value = der.copyOfRange(offset, offset + size)
            offset += size
            require(value[0].toInt() >= 0)
            val unsigned = if (size == 33) {
                require(value[0] == 0.toByte())
                value.copyOfRange(1, size)
            } else value
            return ByteArray(32 - unsigned.size) + unsigned
        }
        val result = integer() + integer()
        require(offset == der.size)
        return result
    }
}
