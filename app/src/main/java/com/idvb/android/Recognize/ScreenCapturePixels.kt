package com.idvb.android.recognize

import java.nio.ByteBuffer

/** Packs only the requested RGBA rectangle, without copying row padding or changing the source. */
internal object ScreenCapturePixels {
    fun copy(source: ByteBuffer, rowStride: Int, left: Int, top: Int, width: Int, height: Int,
        reusable: ByteBuffer? = null): ByteBuffer {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
        val rowBytes = Math.multiplyExact(width, 4)
        require((left.toLong() + width) * 4 <= rowStride)
        val size = Math.multiplyExact(rowBytes, height)
        val packed = reusable?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size)
        packed.clear(); packed.limit(size)
        val input = source.duplicate()
        repeat(height) { row ->
            val offset = Math.toIntExact((top.toLong() + row) * rowStride + left.toLong() * 4)
            require(offset.toLong() + rowBytes <= source.limit())
            input.limit(offset + rowBytes); input.position(offset)
            packed.put(input)
        }
        packed.rewind()
        return packed
    }
}
