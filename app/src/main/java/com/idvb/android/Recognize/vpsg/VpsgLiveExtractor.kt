package com.idvb.android.recognize.vpsg

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Desktop Vpsg3FastLiveExtractor 的结构色域、轮廓和动态遮挡路径。 */
internal object VpsgLiveExtractor {
    data class Observation(val edges: Mat, val valid: Mat, val proposal: Mat) : AutoCloseable {
        override fun close() {
            edges.release()
            valid.release()
            proposal.release()
        }
    }

    fun extract(bgr: Mat): Observation {
        require(!bgr.empty() && bgr.channels() == 3)
        val hsv = Mat()
        val gray = Mat()
        val exclusion = Mat.zeros(bgr.size(), org.opencv.core.CvType.CV_8UC1)
        val room = Mat()
        val room2 = Mat()
        val corridor = Mat()
        val candidates = Mat.zeros(bgr.size(), org.opencv.core.CvType.CV_8UC1)
        val strong = Mat()
        val support = Mat()
        val proposal = Mat()
        var transferred = false
        val uncertain = Mat()
        val invalid = Mat()
        val dilatedExclusion = Mat()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k11 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            dynamicExclusion(hsv, exclusion)
            Core.inRange(hsv, Scalar(0.0, 18.0, 82.0), Scalar(25.0, 165.0, 200.0), room)
            Core.inRange(hsv, Scalar(170.0, 18.0, 82.0), Scalar(179.0, 165.0, 200.0), room2)
            Core.bitwise_or(room, room2, room)
            Core.inRange(hsv, Scalar(95.0, 14.0, 82.0), Scalar(130.0, 105.0, 200.0), corridor)
            room.setTo(Scalar.all(0.0), exclusion)
            corridor.setTo(Scalar.all(0.0), exclusion)
            for (mask in listOf(room, corridor)) {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k5)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k3)
                drawContours(mask, candidates)
            }
            Imgproc.dilate(exclusion, dilatedExclusion, k5)
            candidates.setTo(Scalar.all(0.0), dilatedExclusion)

            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, strong, 80.0, 180.0, 3, true)
            Imgproc.dilate(strong, support, k5)
            // Desktop uses strong photometric support for scale/translation proposals;
            // weak observed edges remain available for the final full-resolution verification.
            Core.bitwise_and(candidates, support, proposal)
            val smoothed = Mat()
            val bright = Mat()
            val weak = Mat()
            try {
                Imgproc.GaussianBlur(gray, smoothed, Size(5.0, 5.0), 1.5)
                Core.subtract(gray, smoothed, bright)
                Imgproc.threshold(bright, bright, 4.0, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.Canny(gray, weak, 25.0, 65.0, 3, true)
                Imgproc.dilate(bright, bright, k3)
                Core.bitwise_and(weak, bright, weak)
                Imgproc.dilate(weak, weak, k3)
                Core.bitwise_or(support, weak, support)
            } finally {
                smoothed.release(); bright.release(); weak.release()
            }
            val observed = Mat()
            val valid = Mat()
            try {
                Core.bitwise_and(candidates, support, observed)
                Core.bitwise_not(support, uncertain)
                Core.bitwise_and(candidates, uncertain, uncertain)
                Imgproc.dilate(uncertain, uncertain, k11)
                Core.bitwise_or(uncertain, dilatedExclusion, invalid)
                Core.bitwise_not(invalid, valid)
                valid.setTo(Scalar.all(255.0), observed)
                transferred = true
                return Observation(observed, valid, proposal)
            } catch (error: Throwable) {
                observed.release(); valid.release(); throw error
            }
        } finally {
            hsv.release(); gray.release(); exclusion.release(); room.release(); room2.release()
            corridor.release(); candidates.release(); strong.release(); support.release()
            uncertain.release(); invalid.release(); dilatedExclusion.release()
            k3.release(); k5.release(); k11.release()
            if (!transferred) proposal.release()
        }
    }

    private fun dynamicExclusion(hsv: Mat, exclusion: Mat) {
        val width = hsv.cols()
        val height = hsv.rows()
        val green = Rect(0, (height * .68).toInt(), (width * .28).toInt(), height - (height * .68).toInt())
        if (green.width > 0 && green.height > 0) {
            val roi = hsv.submat(green)
            val seed = Mat()
            try {
                Core.inRange(roi, Scalar(35.0, 55.0, 45.0), Scalar(95.0, 255.0, 255.0), seed)
                if (Core.countNonZero(seed) > 40) {
                    Imgproc.rectangle(exclusion, Point(0.0, height * .72),
                        Point(width * .24, height.toDouble()), Scalar.all(255.0), -1)
                }
            } finally { roi.release(); seed.release() }
        }
        val top = Rect((width * .10).toInt(), (height * .03).toInt(),
            (width * .70).toInt(), (height * .12).toInt())
        if (top.width > 0 && top.height > 0) {
            val roi = hsv.submat(top)
            val seed = Mat()
            try {
                Core.inRange(roi, Scalar(0.0, 0.0, 120.0), Scalar(180.0, 60.0, 255.0), seed)
                if (Core.countNonZero(seed) > 100) Imgproc.rectangle(exclusion, top, Scalar.all(255.0), -1)
            } finally { roi.release(); seed.release() }
        }
    }

    private fun drawContours(mask: Mat, target: Mat) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val copy = mask.clone()
        try {
            Imgproc.findContours(copy, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
            val approximated = ArrayList<MatOfPoint>()
            try {
                contours.forEachIndexed { index, contour ->
                    val curve = MatOfPoint2f(*contour.toArray())
                    val poly = MatOfPoint2f()
                    try {
                        if (Imgproc.arcLength(curve, true) < 30.0) return@forEachIndexed
                        val parent = if (hierarchy.empty()) -1 else hierarchy.get(0, index)[3].toInt()
                        if (parent != -1 && abs(Imgproc.contourArea(contour)) < 900.0) return@forEachIndexed
                        Imgproc.approxPolyDP(curve, poly, .55, true)
                        approximated += MatOfPoint(*poly.toArray())
                    } finally { curve.release(); poly.release() }
                }
                if (approximated.isNotEmpty()) {
                    Imgproc.drawContours(target, approximated, -1, Scalar.all(255.0), 2, Imgproc.LINE_8)
                }
            } finally { approximated.forEach(MatOfPoint::release) }
        } finally { contours.forEach(MatOfPoint::release); hierarchy.release(); copy.release() }
    }
}
