package com.idvb.android.idvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageProbeTest {

    @Test
    fun `PNG 尺寸解析`() {
        assertEquals(1 to 1, ImageProbe.dimensions(TestIdvmPackage.PNG_1X1))
    }

    @Test
    fun `JPEG 尺寸从 SOF0 解析`() {
        // 手工构造：SOI + SOF0（高=5 宽=6）+ EOI
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),          // SOI
            0xFF.toByte(), 0xC0.toByte(),          // SOF0
            0x00, 0x11,                            // 段长 17
            0x08,                                  // 精度
            0x00, 0x05, 0x00, 0x06,                // 高=5 宽=6
            0x03,                                  // 分量数
            0x01, 0x11, 0x00,                      // 分量 1
            0x02, 0x11, 0x00,                      // 分量 2
            0x03, 0x11, 0x00,                      // 分量 3
            0xFF.toByte(), 0xD9.toByte(),          // EOI
        )
        assertEquals(6 to 5, ImageProbe.dimensions(jpeg))
    }

    @Test
    fun `非图片字节返回 null`() {
        assertNull(ImageProbe.dimensions("hello world".encodeToByteArray()))
    }
}
