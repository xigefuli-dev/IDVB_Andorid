package com.idvb.android.account

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AuthProofTest {
    @Test fun proofIsStableAcrossFieldOrderAndBindsOperationAndPassword() {
        val a = mapOf("password" to "secret", "email" to "test@qq.com")
        val b = mapOf("email" to "test@qq.com", "password" to "secret")
        assertArrayEquals(AuthProof.message("login", a, 100), AuthProof.message("login", b, 100))
        assertFalse(AuthProof.message("login", a, 100).contentEquals(AuthProof.message("login.confirm", b, 100)))
        assertFalse(AuthProof.message("login", a, 100).contentEquals(AuthProof.message("login", b + ("password" to "other"), 100)))
        assertTrue(runCatching { AuthProof.message("login", mapOf("email" to "a\u0000b"), 100) }.isFailure)
    }

    @Test fun androidDerSignatureVerifiesAsServerP1363() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(50) {
            val message = AuthProof.message("login", mapOf("email" to "测试@qq.com", "password" to "sample"), it.toLong())
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(message); sign() }
            val raw = AuthProof.p1363(der)
            assertEquals(64, raw.size)
            assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(keys.public); update(message); verify(raw)
            })
        }
    }

    @Test fun malformedSignatureIsRejected() {
        assertTrue(runCatching { AuthProof.p1363(byteArrayOf(0x30, 1, 0)) }.isFailure)
    }
}
