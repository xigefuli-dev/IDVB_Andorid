package com.idvb.android.recognize.vpsg

import com.idvb.android.alignment.AlignmentCancellation
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.min

/** Native binary enumeration, followed by sparse managed work. The ordering and sample
 * indices match the scalar row-major implementation exactly, including its two policies. */
internal class VpsgObservationIndex(binary: Mat) {
    val width = binary.cols()
    val height = binary.rows()
    val coordinates: IntArray
    val count: Int get() = coordinates.size / 2
    init {
        val points = Mat()
        try {
            AlignmentCancellation.checkpoint("vpsg.observation.enumerate.before")
            Core.findNonZero(binary, points)
            AlignmentCancellation.checkpoint("vpsg.observation.enumerate.after")
            coordinates = IntArray(points.rows() * 2)
            if (coordinates.isNotEmpty()) points.get(0, 0, coordinates)
        } finally { points.release() }
    }

    fun sample(limit: Int, integerStride: Boolean = false): List<VpsgFastSolver.Point> {
        if (count == 0) return emptyList()
        val size = min(limit, count)
        val stride = maxOf(1, (count + limit - 1) / limit)
        val samples = if (integerStride) min(limit, (count + stride - 1) / stride) else size
        return List(samples) { i ->
            val at = (if (integerStride) i * stride else (i * (count.toDouble() / size)).toInt()) * 2
            VpsgFastSolver.Point(coordinates[at], coordinates[at + 1])
        }
    }

    fun projections(): Pair<DoubleArray, DoubleArray> {
        val x = DoubleArray(width); val y = DoubleArray(height)
        for (i in 0 until count) {
            if (i and 255 == 0) AlignmentCancellation.checkpoint("vpsg.observation.sparse-projection")
            x[coordinates[i * 2]]++; y[coordinates[i * 2 + 1]]++
        }
        return x to y
    }
}
