package com.idvb.android.recognize.vpsg

import com.idvb.android.recognize.cv.CvImages
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/** Immutable managed bitsets: evicting an index cannot invalidate an active scan's reference. */
internal object VpsgPreparedIndex {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private val cache = LinkedHashMap<String, VpsgFastSolver.Index>(32, .75f, true)
    private var bytes = 0L

    @Synchronized fun clear() { cache.clear(); bytes = 0 }

    @Synchronized fun load(file: File, generation: String): VpsgFastSolver.Index {
        // Include content generation, actual location, size and mtime. Reimports and changed packages
        // cannot reuse an old index just because the map/floor names are unchanged.
        val path = file.canonicalPath
        val key = "$path|$generation|${file.length()}|${file.lastModified()}"
        cache[key]?.let { return it }
        val gray = CvImages.loadGray(file)
        val binary = Mat()
        val k3 = Mat(); val k5 = Mat()
        try {
            require(!gray.empty()) { "Empty prebuilt line image" }
            Imgproc.threshold(gray, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)
            val width = binary.cols(); val height = binary.rows()
            require(width >= 100 && height >= 100)
            val pixels = ByteArray(width * height).also { binary.get(0, 0, it) }
            val projection = projection(pixels, width, height)
            val prior = VpsgFastSolver.Correlation(projection).peak()
            for ((size, output) in listOf(3 to k3, 5 to k5)) {
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size.toDouble(), size.toDouble()))
                try { Imgproc.dilate(binary, output, kernel) } finally { kernel.release() }
            }
            fun pack(mat: Mat): LongArray {
                val data = ByteArray(width * height).also { mat.get(0, 0, it) }
                val wordsPerRow = (width + 63) / 64
                val words = LongArray(wordsPerRow * height)
                for (y in 0 until height) for (x in 0 until width)
                    if ((data[y * width + x].toInt() and 255) > 128) {
                        val at = y * wordsPerRow + (x ushr 6)
                        words[at] = words[at] or (1L shl (x and 63))
                    }
                return words
            }
            val result = VpsgFastSolver.Index(width, height, pack(k3), pack(k5), prior, Core.countNonZero(binary))
            val obsolete = cache.keys.filter { it.startsWith("$path|") }
            for (old in obsolete) bytes -= cache.remove(old)!!.bytes
            if (result.bytes <= MAX_BYTES) {
                while (bytes + result.bytes > MAX_BYTES && cache.isNotEmpty()) {
                    val oldest = cache.entries.first()
                    bytes -= oldest.value.bytes; cache.remove(oldest.key)
                }
                cache[key] = result; bytes += result.bytes
            }
            return result
        } finally { gray.release(); binary.release(); k3.release(); k5.release() }
    }

    fun projection(pixels: ByteArray, width: Int, height: Int): DoubleArray {
        val projection = DoubleArray(width)
        for (y in 0 until height) for (x in 0 until width)
            if ((pixels[y * width + x].toInt() and 255) > 128) projection[x]++
        return projection
    }

    /** Desktop's uniform row-major sample, including subinteger spacing rather than integer stride. */
    fun sample(pixels: ByteArray, width: Int, limit: Int = 150): List<VpsgFastSolver.Point> {
        val total = pixels.count { (it.toInt() and 255) > 128 }
        if (total == 0) return emptyList()
        val count = minOf(limit, total)
        val step = total.toDouble() / count
        val result = ArrayList<VpsgFastSolver.Point>(count)
        var seen = 0
        for (i in pixels.indices) {
            if ((pixels[i].toInt() and 255) <= 128) continue
            if (result.size < count && seen == (result.size * step).toInt())
                result += VpsgFastSolver.Point(i % width, i / width)
            seen++
        }
        return result
    }
}
