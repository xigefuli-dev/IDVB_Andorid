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
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.measure
import com.idvb.android.alignment.AlignmentCancellation

/** Desktop Vpsg3FastLiveExtractor 的结构色域、轮廓和动态遮挡路径。 */
internal object VpsgLiveExtractor {
    const val RESOLVED_WALL_POLICY = "resolved-walls-canny120-240-dilate3-v1"
    data class Observation(val edges: Mat, val valid: Mat, val proposal: Mat,
        val revealed: Mat? = null) : AutoCloseable {
        override fun close() {
            edges.release()
            valid.release()
            proposal.release()
            revealed?.release()
        }
    }

    fun extract(bgr: Mat, log: AlignmentLogSink = AlignmentLogSink.NONE,
        visibilityScopedReverse: Boolean = false,
        captureRevealedDomain: Boolean = visibilityScopedReverse): Observation {
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
        val revealed = if (captureRevealedDomain) Mat() else null
        var transferred = false
        val uncertain = Mat()
        val invalid = Mat()
        val dilatedExclusion = Mat()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k11 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
        try {
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.extract.configuration", thresholds = mapOf(
                "strongCannyLow" to 80.0, "strongCannyHigh" to 180.0, "weakCannyLow" to 25.0,
                "weakCannyHigh" to 65.0, "cannyAperture" to 3.0, "gaussianSigma" to 1.5,
                "gaussianKernel" to 5.0, "brightThreshold" to 4.0, "uncertainDilation" to 11.0,
                "contourMinArc" to 30.0, "holeMinArea" to 900.0, "contourEpsilon" to .55,
                "contourThickness" to 2.0), series = mapOf(
                "roomHsvMin" to listOf(0.0, 18.0, 82.0), "roomHsvMax" to listOf(25.0, 165.0, 200.0),
                "room2HsvMin" to listOf(170.0, 18.0, 82.0), "room2HsvMax" to listOf(179.0, 165.0, 200.0),
                "corridorHsvMin" to listOf(95.0, 14.0, 82.0), "corridorHsvMax" to listOf(130.0, 105.0, 200.0))))
            log.measure("vpsg.extract.hsv") { Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV) }
            log.measure("vpsg.extract.dynamic-exclusion") { dynamicExclusion(hsv, exclusion, log) }
            log.measure("vpsg.extract.color-masks") {
            Core.inRange(hsv, Scalar(0.0, 18.0, 82.0), Scalar(25.0, 165.0, 200.0), room)
            Core.inRange(hsv, Scalar(170.0, 18.0, 82.0), Scalar(179.0, 165.0, 200.0), room2)
            Core.bitwise_or(room, room2, room)
            Core.inRange(hsv, Scalar(95.0, 14.0, 82.0), Scalar(130.0, 105.0, 200.0), corridor)
            room.setTo(Scalar.all(0.0), exclusion)
            corridor.setTo(Scalar.all(0.0), exclusion)
            }
            log.measure("vpsg.extract.structural-contours") {
            for (mask in listOf(room, corridor)) {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k5)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k3)
                drawContours(mask, candidates)
            }
            // Detect markers after drawing semantic contours: cutting their footprint out of
            // the color fill first creates new artificial boundaries around nearby player icons.
            if (visibilityScopedReverse) log.measure("vpsg.extract.marker-exclusion") {
                excludeRedMarkers(hsv, exclusion, log)
                excludePlayerMarkers(hsv, exclusion, log)
            }
            Imgproc.dilate(exclusion, dilatedExclusion, k5)
            if (visibilityScopedReverse) log.attach("occlusion-mask.gray8") {
                ByteArray(dilatedExclusion.cols() * dilatedExclusion.rows()).also { dilatedExclusion.get(0, 0, it) }
            }
            candidates.setTo(Scalar.all(0.0), dilatedExclusion)
            }

            log.measure("vpsg.extract.strong-photometric") {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, strong, 80.0, 180.0, 3, true)
            Imgproc.dilate(strong, support, k5)
            // Desktop uses strong photometric support for scale/translation proposals;
            // weak observed edges remain available for the final full-resolution verification.
            Core.bitwise_and(candidates, support, proposal)
            }
            val smoothed = Mat()
            val bright = Mat()
            val weak = Mat()
            try {
                log.measure("vpsg.extract.weak-photometric") {
                Imgproc.GaussianBlur(gray, smoothed, Size(5.0, 5.0), 1.5)
                Core.subtract(gray, smoothed, bright)
                Imgproc.threshold(bright, bright, 4.0, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.Canny(gray, weak, 25.0, 65.0, 3, true)
                Imgproc.dilate(bright, bright, k3)
                Core.bitwise_and(weak, bright, weak)
                Imgproc.dilate(weak, weak, k3)
                Core.bitwise_or(support, weak, support)
                }
            } finally {
                smoothed.release(); bright.release(); weak.release()
            }
            val observed = Mat()
            val valid = Mat()
            try {
                if (visibilityScopedReverse) log.measure("vpsg.extract.wall-confidence") {
                    // A semantic color boundary is also produced by the soft reveal/fog edge.
                    // Its weak Canny response is not evidence of a missing reference wall.
                    // Keep the original proposal, but classify only resolved photometric edges
                    // as observed structure; the rest stays unknown in both verification directions.
                    Imgproc.Canny(gray, strong, 120.0, 240.0, 3, true)
                    Imgproc.dilate(strong, support, k3)
                    log.attach("resolved-wall-support.gray8") {
                        ByteArray(support.cols() * support.rows()).also { support.get(0, 0, it) }
                    }
                    log.emit(AlignmentLogEvent("vpsg.extract.wall-confidence-policy",
                        thresholds = mapOf("cannyLow" to 120.0, "cannyHigh" to 240.0, "supportDilation" to 3.0),
                        labels = mapOf("observationPolicy" to RESOLVED_WALL_POLICY,
                            "weakBoundary" to "unknown-not-conflict", "artifact" to "resolved-wall-support.gray8")))
                }
                log.measure("vpsg.extract.valid-mask") {
                Core.bitwise_and(candidates, support, observed)
                Core.bitwise_not(support, uncertain)
                Core.bitwise_and(candidates, uncertain, uncertain)
                Imgproc.dilate(uncertain, uncertain, k11)
                Core.bitwise_or(uncertain, dilatedExclusion, invalid)
                Core.bitwise_not(invalid, valid)
                valid.setTo(Scalar.all(255.0), observed)
                }
                if (revealed != null) log.measure("vpsg.extract.revealed-domain") {
                    // The PC valid mask only neutralizes uncertain observed edges. Its complement
                    // does not prove that an entire unexplored room is visible. Reverse verification
                    // needs independently observed room/corridor interiors, including their wall rim.
                    Core.bitwise_or(room, corridor, revealed)
                    Imgproc.dilate(revealed, revealed, k5)
                    Core.bitwise_and(revealed, valid, revealed)
                    log.emit(AlignmentLogEvent("vpsg.extract.revealed-evidence", measurements = mapOf(
                        "knownPixels" to Core.countNonZero(revealed).toDouble(),
                        "unknownPixels" to (bgr.rows() * bgr.cols() - Core.countNonZero(revealed)).toDouble()),
                        thresholds = mapOf("wallRimDilation" to 5.0), labels = mapOf(
                            "domain" to "semantic-room-or-corridor-interiors-and-wall-rim intersect valid-mask",
                            "unknownPolicy" to "excluded-from-reverse-numerator-and-denominator")))
                }
                if (log.enabled) log.emit(AlignmentLogEvent("vpsg.extract.result", measurements = mapOf(
                    "width" to bgr.cols().toDouble(), "height" to bgr.rows().toDouble(),
                    "observedPixels" to Core.countNonZero(observed).toDouble(),
                    "validPixels" to Core.countNonZero(valid).toDouble(),
                    "proposalPixels" to Core.countNonZero(proposal).toDouble(),
                    "excludedPixels" to Core.countNonZero(exclusion).toDouble()),
                    labels = mapOf("maskLayout" to "row-major unsigned 8-bit; one byte per pixel")))
                transferred = true
                return Observation(observed, valid, proposal, revealed)
            } catch (error: Throwable) {
                observed.release(); valid.release(); throw error
            }
        } finally {
            hsv.release(); gray.release(); exclusion.release(); room.release(); room2.release()
            corridor.release(); candidates.release(); strong.release(); support.release()
            uncertain.release(); invalid.release(); dilatedExclusion.release()
            k3.release(); k5.release(); k11.release()
            if (!transferred) { proposal.release(); revealed?.release() }
        }
    }

    /** Small saturated red enemy markers can fall inside the wraparound room hue range.
     * Exclude their footprint as occluded evidence, not as either walls or known empty floor.
     * Large connected color fields remain available as real room structure.
     */
    private fun excludeRedMarkers(hsv: Mat, exclusion: Mat, log: AlignmentLogSink) {
        val low = Mat(); val high = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            Core.inRange(hsv, Scalar(0.0, 65.0, 95.0), Scalar(5.0, 255.0, 255.0), low)
            Core.inRange(hsv, Scalar(160.0, 65.0, 95.0), Scalar(179.0, 255.0, 255.0), high)
            Core.bitwise_or(low, high, low)
            AlignmentCancellation.checkpoint("vpsg.extract.marker-components.before")
            val count = Imgproc.connectedComponentsWithStats(low, labels, stats, centroids)
            AlignmentCancellation.checkpoint("vpsg.extract.marker-components.after")
            val components = ArrayList<Double>()
            for (i in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.extract.marker-component")
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                val marker = area >= 4 && w <= 32 && h <= 32
                components.addAll(listOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), area.toDouble(), if (marker) 1.0 else 0.0))
                if (marker) Imgproc.rectangle(exclusion, Point((x - 2).toDouble(), (y - 2).toDouble()),
                    Point((x + w + 1).toDouble(), (y + h + 1).toDouble()), Scalar.all(255.0), -1)
            }
            log.emit(AlignmentLogEvent("vpsg.extract.marker-evidence", measurements = mapOf(
                "seedPixels" to Core.countNonZero(low).toDouble(), "components" to (count - 1).toDouble()),
                thresholds = mapOf("minimumComponentArea" to 4.0, "maximumComponentWidth" to 32.0,
                    "maximumComponentHeight" to 32.0, "componentPadding" to 2.0, "occlusionDilation" to 5.0),
                series = mapOf("lowHsvMin" to listOf(0.0, 65.0, 95.0), "lowHsvMax" to listOf(5.0, 255.0, 255.0),
                    "highHsvMin" to listOf(160.0, 65.0, 95.0), "highHsvMax" to listOf(179.0, 255.0, 255.0),
                    "componentXYWHAreaExcluded" to components),
                labels = mapOf("artifact" to "occlusion-mask.gray8", "policy" to "small-red-markers-are-occluded-not-structure")))
        } finally { low.release(); high.release(); labels.release(); stats.release(); centroids.release() }
    }

    private fun excludePlayerMarkers(hsv: Mat, exclusion: Mat, log: AlignmentLogSink) {
        val yellow = Mat(); val labels = Mat(); val stats = Mat(); val centers = Mat()
        try {
            Core.inRange(hsv, Scalar(18.0, 100.0, 160.0), Scalar(38.0, 255.0, 255.0), yellow)
            val count = Imgproc.connectedComponentsWithStats(yellow, labels, stats, centers)
            val components = ArrayList<Double>()
            for (i in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.extract.player-component")
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
                // Upright numbered player glyph; diagonal yellow door ticks stay structural.
                val marker = area >= 8 && w in 4..24 && h in 7..32 && h >= w
                components.addAll(listOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), area.toDouble(), if (marker) 1.0 else 0.0))
                if (marker) Imgproc.rectangle(exclusion, Point((x - 4).toDouble(), (y - 3).toDouble()),
                    Point((x + w + 3).toDouble(), (y + h + 6).toDouble()), Scalar.all(255.0), -1)
            }
            log.emit(AlignmentLogEvent("vpsg.extract.player-evidence",
                thresholds = mapOf("minimumArea" to 8.0, "minimumWidth" to 4.0, "maximumWidth" to 24.0,
                    "minimumHeight" to 7.0, "maximumHeight" to 32.0),
                series = mapOf("hsvMin" to listOf(18.0, 100.0, 160.0), "hsvMax" to listOf(38.0, 255.0, 255.0),
                    "paddingLTRB" to listOf(4.0, 3.0, 4.0, 7.0), "componentXYWHAreaExcluded" to components),
                labels = mapOf("policy" to "upright-yellow-player-occlusion", "artifact" to "occlusion-mask.gray8")))
        } finally { yellow.release(); labels.release(); stats.release(); centers.release() }
    }

    private fun dynamicExclusion(hsv: Mat, exclusion: Mat, log: AlignmentLogSink) {
        val width = hsv.cols()
        val height = hsv.rows()
        val green = Rect(0, (height * .68).toInt(), (width * .28).toInt(), height - (height * .68).toInt())
        if (green.width > 0 && green.height > 0) {
            val roi = hsv.submat(green)
            val seed = Mat()
            try {
                Core.inRange(roi, Scalar(35.0, 55.0, 45.0), Scalar(95.0, 255.0, 255.0), seed)
                val count = Core.countNonZero(seed)
                log.emit(AlignmentLogEvent("vpsg.extract.exclude-green", measurements = mapOf("seedPixels" to count.toDouble()),
                    thresholds = mapOf("minimumPixelsExclusive" to 40.0),
                    series = mapOf("roiRelative" to listOf(0.0, .68, .28, 1.0),
                        "exclusionRelative" to listOf(0.0, .72, .24, 1.0),
                        "hsvMin" to listOf(35.0, 55.0, 45.0), "hsvMax" to listOf(95.0, 255.0, 255.0)),
                    labels = mapOf("applied" to (count > 40).toString())))
                if (count > 40) {
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
                val count = Core.countNonZero(seed)
                log.emit(AlignmentLogEvent("vpsg.extract.exclude-top", measurements = mapOf("seedPixels" to count.toDouble()),
                    thresholds = mapOf("minimumPixelsExclusive" to 100.0),
                    series = mapOf("roiRelativeXYWH" to listOf(.10, .03, .70, .12),
                        "hsvMin" to listOf(0.0, 0.0, 120.0), "hsvMax" to listOf(180.0, 60.0, 255.0)),
                    labels = mapOf("applied" to (count > 100).toString())))
                if (count > 100) Imgproc.rectangle(exclusion, top, Scalar.all(255.0), -1)
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
