package com.idvb.android.alignment

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer

/** Short, uncompressed private-file handoff. The archive queue never owns a Bitmap;
 * the serial writer encodes PNG rows directly from this file. */
internal class AlignmentFrameSpool private constructor(
    private val file: File, private val width: Int, private val height: Int,
) : AutoCloseable {
    fun writePng(output: java.io.OutputStream) {
        file.inputStream().buffered().use { ArgbPngStream.write(it, width, height, output) }
    }
    override fun close() { file.delete() }

    companion object {
        /** Fast lossless PNG while the caller owns the bitmap. One row of pixels,
         * explicit BEST_SPEED compression, and no retained full-frame copy. */
        fun writePng(bitmap: Bitmap, output: java.io.OutputStream) {
            val pixels = IntArray(bitmap.width)
            val row = ByteBuffer.allocate(Math.multiplyExact(bitmap.width, 4))
            var y = 0
            var at = row.capacity()
            val input = object : java.io.InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (at == row.capacity()) {
                        if (y == bitmap.height) return -1
                        AlignmentCancellation.checkpoint("diagnostics.png.row")
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, y++, bitmap.width, 1)
                        row.clear(); row.asIntBuffer().put(pixels); at = 0
                    }
                    val count = minOf(length, row.capacity() - at)
                    row.array().copyInto(bytes, offset, at, at + count)
                    at += count
                    return count
                }
            }
            ArgbPngStream.write(input, bitmap.width, bitmap.height, output)
        }

        /** Big-endian ARGB rows, the input format of [ArgbPngStream]; an owned copy of a borrowed bitmap. */
        fun argbBytes(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val pixels = IntArray(width)
            val bytes = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(width, bitmap.height), 4))
            val rows = bytes.asIntBuffer()
            repeat(bitmap.height) { y ->
                AlignmentCancellation.checkpoint("diagnostics.png.copy-row")
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                rows.put(pixels)
            }
            return bytes.array()
        }

        /** Copies pixels during this call; a file-backed sink encodes the lossless PNG off the caller's thread. */
        fun attachPng(log: AlignmentLogSink, name: String, bitmap: Bitmap) {
            val width = bitmap.width; val height = bitmap.height
            log.attachEncoded(name, { argbBytes(bitmap) }) { argb, output ->
                ArgbPngStream.write(argb.inputStream(), width, height, output)
            }
        }

        /** Borrows input; callers always release their owned frame even if this fails. */
        fun write(directory: File, bitmap: Bitmap): AlignmentFrameSpool {
            check(directory.mkdirs() || directory.isDirectory)
            val file = File(directory, "captured.argb")
            try {
                val width = bitmap.width; val height = bitmap.height
                val pixels = IntArray(width)
                val row = ByteBuffer.allocate(Math.multiplyExact(width, 4))
                AlignmentJournal.publish(file) { output ->
                    repeat(height) { y ->
                        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                        row.clear(); row.asIntBuffer().put(pixels); output.write(row.array())
                    }
                }
                AlignmentJournal.publish(File(directory, "capture-size.txt")) { it.write("$width $height\nARGB32 big-endian".toByteArray()) }
                return AlignmentFrameSpool(file, width, height)
            } catch (error: Throwable) { throw error }
        }
    }
}
