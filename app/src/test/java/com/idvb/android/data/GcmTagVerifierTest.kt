package com.idvb.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class GcmTagVerifierTest {
    @Test fun matchesJceForChunkedCiphertextAndRejectsTampering() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it + 2).toByte() }
        val aad = "IDVB-IDVM-SECURE-2\ntest\nHASH".toByteArray()
        for (size in listOf(0, 1, 15, 16, 17, 65535, 65536, 65537)) {
            val plaintext = ByteArray(size) { (it * 13).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            val ciphertext = encrypted.copyOfRange(0, size)
            val tag = encrypted.copyOfRange(size, encrypted.size)
            val verifier = GcmTagVerifier(key, nonce, aad)
            var offset = 0
            while (offset < size) {
                val count = minOf(731, size - offset)
                verifier.update(ciphertext.copyOfRange(offset, offset + count), count)
                offset += count
            }
            assertTrue("size=$size", verifier.verify(tag))
            if (size > 0) {
                ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
                val tampered = GcmTagVerifier(key, nonce, aad)
                tampered.update(ciphertext, size)
                assertFalse(tampered.verify(tag))
            }
        }
    }
}
