package com.idvb.android.alignment

import java.io.OutputStream
import java.nio.ByteBuffer

/** Owned immutable evidence. Direct mask copies avoid large Java heap allocations in
 * the calculation, and stream into the same raw ZIP entries without changing the format. */
sealed class AlignmentArtifact {
    abstract val size: Int
    abstract fun writeTo(output: OutputStream, scratch: ByteArray)
    abstract fun toByteArray(): ByteArray

    class Bytes(private val bytes: ByteArray) : AlignmentArtifact() {
        override val size get() = bytes.size
        override fun writeTo(output: OutputStream, scratch: ByteArray) = output.write(bytes)
        override fun toByteArray() = bytes
    }
    class Direct(buffer: ByteBuffer) : AlignmentArtifact() {
        private val data = buffer.slice().asReadOnlyBuffer()
        override val size = data.remaining()
        override fun writeTo(output: OutputStream, scratch: ByteArray) {
            val input = data.duplicate()
            while (input.hasRemaining()) {
                val count = minOf(input.remaining(), scratch.size)
                input.get(scratch, 0, count); output.write(scratch, 0, count)
            }
        }
        override fun toByteArray() = ByteArray(size).also { data.duplicate().get(it) }
    }
}
