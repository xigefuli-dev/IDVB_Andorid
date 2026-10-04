package com.idvb.android.recognize.vpsg

import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.AlignmentCancellation
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** All observed edges remain inside the ROI. Distances in the known domain and its
 * bilinear halo equal the full image transform; unknown exterior has no verifier consumer. */
internal object VpsgObservationDistance {
    fun create(edges: Mat, known: Mat, log: AlignmentLogSink = AlignmentLogSink.NONE): Mat {
        val union = Mat(); val inverted = Mat(); val local = Mat()
        val result = Mat(edges.rows(), edges.cols(), CvType.CV_32FC1, Scalar.all(50.0))
        try {
            Core.bitwise_or(edges, known, union)
            val bounds = Imgproc.boundingRect(union)
            if (bounds.width == 0 || bounds.height == 0) return result
            val x = (bounds.x - 2).coerceAtLeast(0); val y = (bounds.y - 2).coerceAtLeast(0)
            val right = (bounds.x + bounds.width + 2).coerceAtMost(edges.cols())
            val bottom = (bounds.y + bounds.height + 2).coerceAtMost(edges.rows())
            val roi = Rect(x, y, right - x, bottom - y)
            val input = edges.submat(roi); val output = result.submat(roi)
            try {
                Core.bitwise_not(input, inverted)
                AlignmentCancellation.checkpoint("vpsg.observation.distance-transform.before")
                Imgproc.distanceTransform(inverted, local, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
                AlignmentCancellation.checkpoint("vpsg.observation.distance-transform.after")
                local.copyTo(output)
            } finally { input.release(); output.release() }
            log.emit(AlignmentLogEvent("vpsg.observation.distance-domain", measurements = mapOf("unknownExteriorValue" to 50.0),
                series = mapOf("roiXYWH" to listOf(x.toDouble(), y.toDouble(), roi.width.toDouble(), roi.height.toDouble())),
                thresholds = mapOf("bilinearHaloPixels" to 2.0), labels = mapOf("policy" to "all-observed-edges-and-known-domain; exact-l2-precise; unknown-exterior-not-tested")))
            return result
        } catch (failure: Throwable) { result.release(); throw failure }
        finally { union.release(); inverted.release(); local.release() }
    }
}
