package com.idvb.android.idvm

import java.util.UUID

/**
 * IDVM 80 字节二进制头（IDVM_FORMAT.md §3）。
 * 整数均为 little-endian；packageId 用 RFC 4122 网络字节序（大端），
 * 不能用 .NET Guid.ToByteArray() 的混合字节序。
 */
object IdvmHeader {

    const val SIZE = 80
    const val MAGIC = "IDVM"
    const val FORMAT_MAJOR = 1
    const val FORMAT_MINOR = 0
    const val MAX_SUPPORTED_MINOR = 4
    private val SUPPORTED_MINORS = 0..MAX_SUPPORTED_MINOR

    /** 头部大小、flags、保留字段、manifest 摘要的固定布局 */
    private const val OFFSET_MAGIC = 0
    private const val OFFSET_MAJOR = 4
    private const val OFFSET_MINOR = 6
    private const val OFFSET_HEADER_SIZE = 8
    private const val OFFSET_FLAGS = 10
    private const val OFFSET_PACKAGE_ID = 12
    private const val OFFSET_CREATED_AT = 28
    private const val OFFSET_MANIFEST_SHA256 = 36
    private const val OFFSET_RESERVED = 68

    data class Header(
        val formatMajor: Int,
        val formatMinor: Int,
        val packageId: UUID,
        /** Unix 毫秒 UTC */
        val createdAt: Long,
        /** manifest.json 原始字节的 SHA-256，32 字节 */
        val manifestSha256: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Header &&
                formatMajor == other.formatMajor &&
                formatMinor == other.formatMinor &&
                packageId == other.packageId &&
                createdAt == other.createdAt &&
                manifestSha256.contentEquals(other.manifestSha256)

        override fun hashCode(): Int =
            ((formatMajor * 31 + formatMinor) * 31 + packageId.hashCode()) * 31 + createdAt.hashCode()
    }

    /** 解析 80 字节头部，任何不符合 v1 规范的情况都抛出 [IllegalArgumentException] */
    fun parse(bytes: ByteArray): Header {
        require(bytes.size >= SIZE) { "header 长度不足 $SIZE 字节，实际 ${bytes.size}" }

        val magic = String(bytes, OFFSET_MAGIC, 4, Charsets.US_ASCII)
        require(magic == MAGIC) { "非 IDVM 魔数：'$magic'" }

        val major = u16le(bytes, OFFSET_MAJOR)
        val minor = u16le(bytes, OFFSET_MINOR)
        require(major == FORMAT_MAJOR) { "IDVM major 版本不兼容：$major，仅支持 $FORMAT_MAJOR" }
        require(minor in SUPPORTED_MINORS) { "IDVM minor 版本不支持：$minor" }

        val headerSize = u16le(bytes, OFFSET_HEADER_SIZE)
        require(headerSize == SIZE) { "headerSize 必须为 $SIZE，实际 $headerSize" }

        val flags = u16le(bytes, OFFSET_FLAGS)
        require(flags == 0) { "flags 必须为 0，实际 $flags" }

        for (i in OFFSET_RESERVED until SIZE) {
            require(bytes[i] == 0.toByte()) { "header 保留字段必须全零（偏移 $i）" }
        }

        val packageId = UUID(u64be(bytes, OFFSET_PACKAGE_ID), u64be(bytes, OFFSET_PACKAGE_ID + 8))
        val createdAt = u64le(bytes, OFFSET_CREATED_AT)
        val manifestSha256 = bytes.copyOfRange(OFFSET_MANIFEST_SHA256, OFFSET_RESERVED)

        return Header(major, minor, packageId, createdAt, manifestSha256)
    }

    /** 序列化头部（测试/工具用） */
    fun write(header: Header): ByteArray {
        val out = ByteArray(SIZE)
        System.arraycopy(MAGIC.toByteArray(Charsets.US_ASCII), 0, out, OFFSET_MAGIC, 4)
        putU16le(out, OFFSET_MAJOR, header.formatMajor)
        putU16le(out, OFFSET_MINOR, header.formatMinor)
        putU16le(out, OFFSET_HEADER_SIZE, SIZE)
        putU16le(out, OFFSET_FLAGS, 0)
        putU64be(out, OFFSET_PACKAGE_ID, header.packageId.mostSignificantBits)
        putU64be(out, OFFSET_PACKAGE_ID + 8, header.packageId.leastSignificantBits)
        putU64le(out, OFFSET_CREATED_AT, header.createdAt)
        require(header.manifestSha256.size == 32) { "manifest SHA-256 必须是 32 字节" }
        System.arraycopy(header.manifestSha256, 0, out, OFFSET_MANIFEST_SHA256, 32)
        return out
    }

    private fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u64le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64be(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun putU16le(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU64le(b: ByteArray, off: Int, value: Long) {
        for (i in 0..7) b[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putU64be(b: ByteArray, off: Int, value: Long) {
        for (i in 0..7) b[off + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
}
