package com.idvb.android.alignment

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Lossless RGBA PNG from row-major, big-endian ARGB. Memory is one scanline plus
 * a fixed IDAT buffer; no Bitmap reconstruction or full-frame encoded byte array. */
internal object ArgbPngStream {
    fun write(input: InputStream, width: Int, height: Int, output: OutputStream) {
        require(width > 0 && height > 0)
        val out = DataOutputStream(output)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray, length: Int = bytes.size) {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(name); update(bytes, 0, length) }
            out.writeInt(length); out.write(name); out.write(bytes, 0, length); out.writeInt(crc.value.toInt())
        }
        val header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8.toByte()).put(6.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).array()
        chunk("IHDR", header)
        val buffer = ByteArray(64 * 1024)
        var used = 0
        val idat = object : OutputStream() {
            override fun write(b: Int) { buffer[used++] = b.toByte(); if (used == buffer.size) flush() }
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                var at = off; var remaining = len
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size - used)
                    bytes.copyInto(buffer, used, at, at + count)
                    used += count; at += count; remaining -= count
                    if (used == buffer.size) flush()
                }
            }
            override fun flush() { if (used > 0) { chunk("IDAT", buffer, used); used = 0 } }
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val compressed = DeflaterOutputStream(idat, deflater, 8192)
            val row = ByteArray(Math.addExact(Math.multiplyExact(width, 4), 1))
            val source = DataInputStream(input)
            repeat(height) {
                row[0] = 0 // PNG filter None; preserve pixels, including alpha.
                source.readFully(row, 1, row.size - 1)
                for (pixel in 1 until row.size step 4) {
                    val alpha = row[pixel]
                    row[pixel] = row[pixel + 1]; row[pixel + 1] = row[pixel + 2]
                    row[pixel + 2] = row[pixel + 3]; row[pixel + 3] = alpha
                }
                compressed.write(row)
            }
            check(source.read() == -1) { "Unexpected frame spool bytes" }
            compressed.finish(); idat.flush()
            chunk("IEND", byteArrayOf())
        } finally { deflater.end() }
    }
}
