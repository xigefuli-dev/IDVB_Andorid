package com.idvb.android.idvm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class IdvmHeaderTest {

    private fun validHeaderBytes() = IdvmHeader.write(
        IdvmHeader.Header(
            formatMajor = 1,
            formatMinor = 0,
            packageId = UUID.fromString("6f9f8b9a-5f6c-4ef1-9d9e-2a5f4f5a1c10"),
            createdAt = 1_724_109_200_000L,
            manifestSha256 = ByteArray(32) { i -> i.toByte() },
        )
    )

    @Test
    fun `解析合法 header 并往返一致`() {
        val bytes = validHeaderBytes()
        val header = IdvmHeader.parse(bytes)
        assertEquals(1, header.formatMajor)
        assertEquals(0, header.formatMinor)
        assertEquals(UUID.fromString("6f9f8b9a-5f6c-4ef1-9d9e-2a5f4f5a1c10"), header.packageId)
        assertEquals(1_724_109_200_000L, header.createdAt)
        assertArrayEquals(ByteArray(32) { i -> i.toByte() }, header.manifestSha256)
        assertArrayEquals(bytes, IdvmHeader.write(header))
    }

    @Test
    fun `packageId 使用 RFC 4122 网络字节序大端编码`() {
        // 从已知 UUID 手工拼大端字节，验证不采用 .NET 混合字节序
        val uuid = UUID.fromString("6f9f8b9a-5f6c-4ef1-9d9e-2a5f4f5a1c10")
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val bytes = validHeaderBytes()
        // 大端：msb 占 bytes[12..20)，lsb 占 bytes[20..28)
        assertEquals(((msb ushr 56) and 0xFF).toByte(), bytes[12])  // msb 最高位字节
        assertEquals(((msb ushr 8) and 0xFF).toByte(), bytes[18])   // msb 次低位字节
        assertEquals((msb and 0xFF).toByte(), bytes[19])            // msb 最低位字节
        assertEquals(((lsb ushr 56) and 0xFF).toByte(), bytes[20])  // lsb 最高位字节
        assertEquals((lsb and 0xFF).toByte(), bytes[27])            // lsb 最低位字节
    }

    @Test
    fun `魔数错误被拒绝`() {
        val bytes = validHeaderBytes()
        bytes[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { IdvmHeader.parse(bytes) }
    }

    @Test
    fun `headerSize 非 80 被拒绝`() {
        val bytes = validHeaderBytes()
        bytes[8] = 79 // u16le 低位
        assertThrows(IllegalArgumentException::class.java) { IdvmHeader.parse(bytes) }
    }

    @Test
    fun `flags 非零被拒绝`() {
        val bytes = validHeaderBytes()
        bytes[10] = 1
        assertThrows(IllegalArgumentException::class.java) { IdvmHeader.parse(bytes) }
    }

    @Test
    fun `保留字段非零被拒绝`() {
        val bytes = validHeaderBytes()
        bytes[68] = 1
        assertThrows(IllegalArgumentException::class.java) { IdvmHeader.parse(bytes) }
    }

    @Test
    fun `major 不兼容被拒绝`() {
        val bytes = validHeaderBytes()
        bytes[4] = 2
        assertThrows(IllegalArgumentException::class.java) { IdvmHeader.parse(bytes) }
    }
}
