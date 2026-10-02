package com.idvb.android.recognize

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ScreenCapturePixelsTest {
    @Test fun croppedRgbaRowsExcludePaddingAndPreserveOriginalBufferCursor() {
        val bytes = ByteArray(60) { 0x7f }
        // Three rows of four pixels, with four padding bytes after each row.
        repeat(3) { y -> repeat(16) { x -> bytes[y * 20 + x] = (y * 16 + x).toByte() } }
        val source = ByteBuffer.wrap(bytes).apply { position(7) }
        val packed = ScreenCapturePixels.copy(source, 20, 1, 1, 2, 2)
        val result = ByteArray(packed.remaining()).also { packed.get(it) }
        assertArrayEquals(byteArrayOf(20,21,22,23,24,25,26,27,36,37,38,39,40,41,42,43), result)
        assertEquals(7, source.position())
        assertEquals(60, source.limit())
    }

    @Test fun reusableBufferIsLimitedToCurrentCropAndLastRowNeedsNoPadding() {
        val source = ByteBuffer.wrap(ByteArray(36) { it.toByte() })
        val reusable = ByteBuffer.allocateDirect(128)
        val packed = ScreenCapturePixels.copy(source, 20, 2, 1, 2, 1, reusable)
        assertSame(reusable, packed)
        assertEquals(8, packed.remaining())
        assertEquals(28.toByte(), packed.get())
    }

    @Test fun duplicatesStaleFramesAndInvalidClockNeverCountAsFreshObservations() {
        assertFalse(ProjectionFrameFreshness.usable(4, 4, 0, 80))
        assertFalse(ProjectionFrameFreshness.usable(3, 4, 0, 80))
        assertFalse(ProjectionFrameFreshness.usable(5, 4, 80_000_001, 80))
        assertFalse(ProjectionFrameFreshness.usable(5, 4, -1, 80))
        assertTrue(ProjectionFrameFreshness.usable(5, 4, 80_000_000, 80))
    }
}
