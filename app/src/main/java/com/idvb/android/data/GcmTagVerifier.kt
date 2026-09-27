package com.idvb.android.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Streaming GHASH for the 96-bit-nonce GCM container. Holds only one partial block. */
internal class GcmTagVerifier(key: ByteArray, private val nonce: ByteArray, aad: ByteArray) {
    private val highTable = LongArray(16 * 256)
    private val lowTable = LongArray(16 * 256)
    private val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private var high = 0L
    private var low = 0L
    private val partial = ByteArray(16)
    private var partialSize = 0
    private val aadSize = aad.size.toLong()
    private var cipherSize = 0L

    init {
        require(nonce.size == 12) { "GCM nonce 长度无效" }
        val h = ecb.doFinal(ByteArray(16))
        val hHi = ByteBuffer.wrap(h, 0, 8).long
        val hLo = ByteBuffer.wrap(h, 8, 8).long
        for (position in 0 until 16) for (value in 1..255) {
            var vHi = hHi
            var vLo = hLo
            var outHi = 0L
            var outLo = 0L
            for (bit in 0 until 128) {
                if (bit / 8 == position && (value and (0x80 ushr (bit % 8))) != 0) {
                    outHi = outHi xor vHi
                    outLo = outLo xor vLo
                }
                val carry = vLo and 1L
                vLo = (vLo ushr 1) or (vHi shl 63)
                vHi = (vHi ushr 1) xor if (carry != 0L) 0xe100000000000000UL.toLong() else 0L
            }
            highTable[position * 256 + value] = outHi
            lowTable[position * 256 + value] = outLo
        }
        absorb(aad, aad.size)
        flushPartial()
    }

    fun update(ciphertext: ByteArray, count: Int) {
        cipherSize += count
        absorb(ciphertext, count)
    }

    fun verify(expectedTag: ByteArray): Boolean {
        flushPartial()
        val lengths = ByteBuffer.allocate(16).putLong(aadSize * 8).putLong(cipherSize * 8).array()
        multiply(lengths)
        val mask = ecb.doFinal(nonce + byteArrayOf(0, 0, 0, 1))
        val state = ByteBuffer.allocate(16).putLong(high).putLong(low).array()
        for (i in 0 until 16) state[i] = (state[i].toInt() xor mask[i].toInt()).toByte()
        return MessageDigest.isEqual(state, expectedTag)
    }

    private fun absorb(bytes: ByteArray, count: Int) {
        var index = 0
        while (index < count) {
            val n = minOf(16 - partialSize, count - index)
            bytes.copyInto(partial, partialSize, index, index + n)
            partialSize += n
            index += n
            if (partialSize == 16) flushPartial()
        }
    }

    private fun flushPartial() {
        if (partialSize == 0) return
        multiply(partial)
        partial.fill(0)
        partialSize = 0
    }

    private fun multiply(block: ByteArray) {
        var xHi = high
        var xLo = low
        for (i in 0 until 8) xHi = xHi xor ((block[i].toLong() and 255) shl (56 - i * 8))
        for (i in 8 until 16) xLo = xLo xor ((block[i].toLong() and 255) shl (56 - (i - 8) * 8))
        var resultHi = 0L
        var resultLo = 0L
        for (i in 0 until 8) {
            val slot = i * 256 + ((xHi ushr (56 - i * 8)).toInt() and 255)
            resultHi = resultHi xor highTable[slot]
            resultLo = resultLo xor lowTable[slot]
        }
        for (i in 8 until 16) {
            val slot = i * 256 + ((xLo ushr (56 - (i - 8) * 8)).toInt() and 255)
            resultHi = resultHi xor highTable[slot]
            resultLo = resultLo xor lowTable[slot]
        }
        high = resultHi
        low = resultLo
    }
}
