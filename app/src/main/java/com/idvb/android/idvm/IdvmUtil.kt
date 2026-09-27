package com.idvb.android.idvm

import java.io.InputStream
import java.security.MessageDigest

/** 安全限制（IDVM_FORMAT.md §11） */
object IdvmLimits {
    const val MAX_ENTRIES = 4096
    const val MAX_SINGLE_FILE_BYTES = 256L * 1024 * 1024 // 256 MiB
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024       // 512 MiB
    const val MAX_JSON_BYTES = 8L * 1024 * 1024          // 8 MiB
}

object IdvmUtil {

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex 长度必须为偶数" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** 读取流中最多 [maxBytes] 字节（超限抛异常），用于限制 JSON 等负载大小 */
    fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) error("负载超过 $maxBytes 字节上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 解析 .NET 风格 ISO 时间戳（可能带 7 位小数秒）为 Unix 毫秒。
     * Java 的 Instant/OffsetDateTime 只接受 0/3/6/9 位小数，这里先归一化到 3 位。
     */
    fun parseIsoInstantMs(value: String): Long {
        val m = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(\.\d+)?([+-]\d{2}:\d{2}|Z)""")
            .matchEntire(value.trim())
            ?: error("createdAt 时间戳格式无效：$value")
        val frac = m.groupValues[3].let { if (it.isEmpty()) "" else "." + it.substring(1).take(3) }
        val normalized = m.groupValues[1] + "T" + m.groupValues[2] + frac + m.groupValues[4]
        return java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    }

    /** 校验 UUID 是否满足 RFC 4122 版本/变体（版本 1..5，变体位正确） */
    fun isRfc4122(uuid: java.util.UUID): Boolean {
        val version = ((uuid.mostSignificantBits ushr 12) and 0xF).toInt()
        val variant = ((uuid.leastSignificantBits ushr 62) and 0x3).toInt()
        return version in 1..5 && variant == 0b10
    }
}

/**
 * 纯 JVM 的图片尺寸探测（PNG/JPEG），避免依赖 Android BitmapFactory，
 * 使 IDVM 导入逻辑可以在 JVM 单元测试中完整往返。
 */
object ImageProbe {

    /** Read only image headers; map images can be hundreds of megabytes. */
    fun dimensions(input: InputStream): Pair<Int, Int>? {
        val first = input.read()
        val second = input.read()
        if (first == 0x89 && second == 0x50) {
            val header = byteArrayOf(first.toByte(), second.toByte()) + ByteArray(22)
            var offset = 2
            while (offset < header.size) {
                val n = input.read(header, offset, header.size - offset)
                if (n <= 0) return null
                offset += n
            }
            return dimensions(header)
        }
        if (first != 0xff || second != 0xd8) return null
        while (true) {
            var prefix = input.read()
            if (prefix < 0) return null
            if (prefix != 0xff) continue
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker < 0 || marker == 0xd9 || marker == 0xda) return null
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            val length = (hi shl 8) or lo
            if (length < 2) return null
            if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                if (length < 7 || input.read() < 0) return null
                val hHi = input.read(); val hLo = input.read()
                val wHi = input.read(); val wLo = input.read()
                if (hHi < 0 || hLo < 0 || wHi < 0 || wLo < 0) return null
                val h = (hHi shl 8) or hLo
                val w = (wHi shl 8) or wLo
                return if (h > 0 && w > 0) w to h else null
            }
            var remaining = (length - 2).toLong()
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else return null
            }
        }
    }

    /** @return Pair(width, height)，无法识别时返回 null */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size >= 8) {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.copyOfRange(0, 8).contentEquals(png)) {
                // PNG：IHDR 数据在偏移 16（宽，大端 u32）与 20（高）
                if (bytes.size >= 24) {
                    val w = be32(bytes, 16)
                    val h = be32(bytes, 20)
                    return w to h
                }
            }
            val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
            if (bytes[0] == jpg[0] && bytes[1] == jpg[1]) {
                return jpegDimensions(bytes)
            }
        }
        return null
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var off = 2
        while (off + 4 <= bytes.size) {
            if (bytes[off].toInt() and 0xFF != 0xFF) { off++; continue }
            val marker = bytes[off + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9 || (marker in 0xD0..0xD7)) {
                off += 2
                continue
            }
            if (off + 4 > bytes.size) return null
            val len = be16(bytes, off + 2)
            if (len < 2 || off + 2 + len > bytes.size) return null
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                // SOF 段：高=len-2 起始（段内第 3、4 字节，即 len-1, len）
                val h = be16(bytes, off + 5)
                val w = be16(bytes, off + 7)
                return w to h
            }
            off += 2 + len
        }
        return null
    }

    private fun be16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
