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
        val revealed: Mat? = null, val wallEdges: Mat? = null) : AutoCloseable {
        val visibilityBytes: ByteArray by lazy {
            val domain = revealed ?: valid
            ByteArray(domain.cols() * domain.rows()).also { domain.get(0, 0, it) }
        }
        val nativeVisibility by lazy { VpsgNativeKernel.direct(visibilityBytes) }
        val visibilityBounds by lazy { Imgproc.boundingRect(revealed ?: valid) }
        override fun close() {
            edges.release()
            valid.release()
            proposal.release()
            revealed?.release()
            wallEdges?.release()
        }
    }

    fun extract(bgr: Mat, log: AlignmentLogSink = AlignmentLogSink.NONE,
        visibilityScopedReverse: Boolean = false,
        captureRevealedDomain: Boolean = visibilityScopedReverse,
        optimized: Boolean = true): Observation {
        require(!bgr.empty() && bgr.channels() == 3)
        val hsv = Mat()
        val gray = Mat()
        val gradientX = Mat(); val gradientY = Mat()
        val exclusion = Mat.zeros(bgr.size(), org.opencv.core.CvType.CV_8UC1)
        val room = Mat()
        val room2 = Mat()
        val corridor = Mat()
        val markerRed = Mat(); val markerYellow = Mat()
        val candidates = Mat.zeros(bgr.size(), org.opencv.core.CvType.CV_8UC1)
        val wallCandidates = Mat()
        val wallEdges = if (visibilityScopedReverse) Mat() else null
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
                "contourThickness" to 2.0, "sobelScale" to 1.0, "sobelDelta" to 0.0,
                "sobelBorderType" to Core.BORDER_REPLICATE.toDouble()), series = mapOf(
                "sobelDxDy" to listOf(1.0, 0.0, 0.0, 1.0),
                "roomHsvMin" to listOf(0.0, 18.0, 82.0), "roomHsvMax" to listOf(25.0, 165.0, 200.0),
                "room2HsvMin" to listOf(170.0, 18.0, 82.0), "room2HsvMax" to listOf(179.0, 165.0, 200.0),
                "corridorHsvMin" to listOf(95.0, 14.0, 82.0), "corridorHsvMax" to listOf(130.0, 105.0, 200.0))))
            log.measure("vpsg.extract.hsv") {
                if (visibilityScopedReverse && optimized && VpsgNativeKernel.available) {
                    val counts = VpsgNativeKernel.hsvConsumers(bgr, hsv)
                    log.emit(AlignmentLogEvent("vpsg.extract.hsv-domain", thresholds = mapOf("globalMinimumValue" to 82.0,
                        "greenMinimumValue" to 45.0, "hsvIntegerShift" to 12.0),
                        measurements = mapOf("convertedPixels" to counts[0].toDouble(), "omittedPixels" to counts[1].toDouble(),
                            "lowValueGreenCandidates" to counts[2].toDouble()),
                        series = mapOf(
                            "greenDetectorXYWH" to listOf(0.0, (bgr.rows() * .68).toInt().toDouble(),
                            (bgr.cols() * .28).toInt().toDouble(), (bgr.rows() - (bgr.rows() * .68).toInt()).toDouble())),
                        labels = mapOf("policy" to "exact-opencv-integer-consumer-pixels-v3; all-consumers-covered; omitted-pixels-cannot-pass-any-hsv-gate",
                            "greenNecessaryCondition" to "G>R; channelRange*5>=V; original-H35..95-S55..255-gate-unchanged",
                            "converter" to "packaged-opencv-4.12; arm-carotene-block-reciprocals-and-scalar-tail; other-abis-integer-tables", "backend" to VpsgNativeKernel.backend)))
                } else if (visibilityScopedReverse && optimized) {
                    // Every global HSV consumer requires V >= 82. The only lower-V
                    // consumer is the bounded green UI detector (V >= 45). Convert
                    // those exact source regions; the remainder cannot enter any mask.
                    val dark = Mat()
                    try {
                        Core.inRange(bgr, Scalar.all(0.0), Scalar.all(81.0), dark)
                        Core.bitwise_not(dark, dark)
                        val bounds = Imgproc.boundingRect(dark)
                        hsv.create(bgr.rows(), bgr.cols(), org.opencv.core.CvType.CV_8UC3)
                        hsv.setTo(Scalar.all(0.0))
                        val greenY = (bgr.rows() * .68).toInt()
                        val green = Rect(0, greenY, (bgr.cols() * .28).toInt(), bgr.rows() - greenY)
                        for (roi in listOf(bounds, green)) if (roi.width > 0 && roi.height > 0) {
                            val input = bgr.submat(roi); val output = hsv.submat(roi)
                            try { Imgproc.cvtColor(input, output, Imgproc.COLOR_BGR2HSV) }
                            finally { input.release(); output.release() }
                        }
                        log.emit(AlignmentLogEvent("vpsg.extract.hsv-domain", thresholds = mapOf("globalMinimumValue" to 82.0),
                            series = mapOf("brightBoundsXYWH" to listOf(bounds.x.toDouble(), bounds.y.toDouble(), bounds.width.toDouble(), bounds.height.toDouble()),
                                "greenDetectorXYWH" to listOf(green.x.toDouble(), green.y.toDouble(), green.width.toDouble(), green.height.toDouble())),
                            labels = mapOf("policy" to "exact-opencv-conversion-on-all-consumer-domains; remaining-value-below-every-global-threshold")))
                    } finally { dark.release() }
                } else Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            }
            log.measure("vpsg.extract.dynamic-exclusion") { dynamicExclusion(hsv, exclusion, log) }
            log.measure("vpsg.extract.color-masks") {
            if (optimized && visibilityScopedReverse && VpsgNativeKernel.available) {
                VpsgNativeKernel.semanticMasks(hsv, exclusion, room, corridor, markerRed, markerYellow)
                log.emit(AlignmentLogEvent("vpsg.extract.color-mask-policy", "native-fused-inclusive-predicates",
                    labels = mapOf("policy" to "unchanged-room-wrap-room-corridor-hsv-ranges-and-nonzero-exclusion; original-red-yellow-seeds-prefilled-in-same-pass-and-applied-after-contours; exact-byte-differential-oracle-is-opencv")))
            } else {
            Core.inRange(hsv, Scalar(0.0, 18.0, 82.0), Scalar(25.0, 165.0, 200.0), room)
            Core.inRange(hsv, Scalar(170.0, 18.0, 82.0), Scalar(179.0, 165.0, 200.0), room2)
            Core.bitwise_or(room, room2, room)
            Core.inRange(hsv, Scalar(95.0, 14.0, 82.0), Scalar(130.0, 105.0, 200.0), corridor)
            room.setTo(Scalar.all(0.0), exclusion)
            corridor.setTo(Scalar.all(0.0), exclusion)
            }
            }
            log.measure("vpsg.extract.structural-contours") {
            for (mask in listOf(room, corridor)) {
                val bounds = if (optimized && visibilityScopedReverse) Imgproc.boundingRect(mask) else Rect(0, 0, mask.cols(), mask.rows())
                if (bounds.width == 0 || bounds.height == 0) continue
                val left = (bounds.x - 6).coerceAtLeast(0); val top = (bounds.y - 6).coerceAtLeast(0)
                val right = (bounds.x + bounds.width + 6).coerceAtMost(mask.cols())
                val bottom = (bounds.y + bounds.height + 6).coerceAtMost(mask.rows())
                val roi = Rect(left, top, right - left, bottom - top)
                log.emit(AlignmentLogEvent("vpsg.extract.semantic-roi", series = mapOf("xywh" to listOf(
                    left.toDouble(), top.toDouble(), roi.width.toDouble(), roi.height.toDouble())),
                    thresholds = mapOf("zeroHaloPixels" to 6.0), labels = mapOf("policy" to "lossless-nonzero-bounds-plus-morphology-halo")))
                val input = mask.submat(roi); val output = candidates.submat(roi)
                try {
                    Imgproc.morphologyEx(input, input, Imgproc.MORPH_OPEN, k5)
                    Imgproc.morphologyEx(input, input, Imgproc.MORPH_CLOSE, k3)
                    drawContours(input, output)
                } finally { input.release(); output.release() }
            }
            // Detect markers after drawing semantic contours: cutting their footprint out of
            // the color fill first creates new artificial boundaries around nearby player icons.
            if (visibilityScopedReverse) log.measure("vpsg.extract.marker-exclusion") {
                excludeRedMarkers(hsv, exclusion, log, markerRed.takeUnless { it.empty() })
                excludePlayerMarkers(hsv, exclusion, log, markerYellow.takeUnless { it.empty() })
            }
            Imgproc.dilate(exclusion, dilatedExclusion, k5)
            if (visibilityScopedReverse) VpsgMaskEvidence.attach(log, "occlusion-mask.gray8", dilatedExclusion)
            candidates.setTo(Scalar.all(0.0), dilatedExclusion)
            }

            log.measure("vpsg.extract.strong-photometric") {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            if (visibilityScopedReverse && optimized) {
                log.measure("vpsg.extract.shared-gradients") {
                    Imgproc.Sobel(gray, gradientX, org.opencv.core.CvType.CV_16S, 1, 0, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                    Imgproc.Sobel(gray, gradientY, org.opencv.core.CvType.CV_16S, 0, 1, 3, 1.0, 0.0, Core.BORDER_REPLICATE)
                }
                Imgproc.Canny(gradientX, gradientY, strong, 80.0, 180.0, true)
            } else Imgproc.Canny(gray, strong, 80.0, 180.0, 3, true)
            Imgproc.dilate(strong, support, k5)
            // Desktop uses strong photometric support for scale/translation proposals;
            // weak observed edges remain available for the final full-resolution verification.
            Core.bitwise_and(candidates, support, proposal)
            }
            if (visibilityScopedReverse) log.measure("vpsg.extract.striped-frames") {
                candidates.copyTo(wallCandidates)
                // Neutral slat borders can remain inside the semantic color fill. Recover
                // only independently measured closed frames; the resolved-wall gate below
                // still decides which of these edges are observed rather than unknown.
                val domain = Mat(); val frames = Mat.zeros(bgr.size(), org.opencv.core.CvType.CV_8UC1)
                try {
                    room.copyTo(domain)
                    val bounds = Imgproc.boundingRect(domain)
                    if (bounds.width > 0 && bounds.height > 0) {
                        val x = (bounds.x - 8).coerceAtLeast(0); val y = (bounds.y - 8).coerceAtLeast(0)
                        val right = (bounds.x + bounds.width + 8).coerceAtMost(bgr.cols())
                        val bottom = (bounds.y + bounds.height + 8).coerceAtMost(bgr.rows())
                        val roi = Rect(x, y, right - x, bottom - y)
                        val input = bgr.submat(roi); val luminance = gray.submat(roi); val colors = hsv.submat(roi)
                        val target = frames.submat(roi)
                        try {
                            val geometry = com.idvb.android.alignment.ReferenceStripedFrameGeometry.extract(input, luminance, colors, 82.0)
                            target.put(0, 0, geometry.edges)
                            log.emit(AlignmentLogEvent("vpsg.extract.striped-evidence", "measured-room-framed-vertical-stripes-v5",
                                thresholds = com.idvb.android.alignment.ReferenceStripedFrameGeometry.thresholds + ("minimumValue" to 82.0),
                                series = mapOf("roiXYWH" to listOf(x.toDouble(), y.toDouble(), roi.width.toDouble(), roi.height.toDouble()),
                                    "candidateRoiLTRBLeftRightTopBottomSupportBandsDensityAccepted" to geometry.candidates,
                                    "componentRoiXYWHAreaEligible" to geometry.components,
                                    "acceptedRoiLTRB" to geometry.rectangles),
                                labels = mapOf("artifact" to "live-striped-frames.gray8", "policy" to "observed-room-grates-only; corridor-stair-decoration-excluded; grouping-joins-horizontal-and-vertical-borders; four-borders-max-fixed-flank-or-directional-top-hat-continuity-and-four-stripe-bands; original-resolved-wall-and-occlusion-gates-applied; proposals-unchanged")))
                        } finally { input.release(); luminance.release(); colors.release(); target.release() }
                    }
                    frames.setTo(Scalar.all(0.0), dilatedExclusion)
                    Core.bitwise_or(candidates, frames, candidates)
                    VpsgMaskEvidence.attach(log, "live-striped-frames.gray8", frames)
                } finally { domain.release(); frames.release() }
            }
            val smoothed = Mat()
            val bright = Mat()
            val weak = Mat()
            try {
                // Selected-floor verification replaces support with the resolved-wall
                // mask below; this weak mask has no consumer on that path.
                if (!visibilityScopedReverse) log.measure("vpsg.extract.weak-photometric") {
                Imgproc.GaussianBlur(gray, smoothed, Size(5.0, 5.0), 1.5)
                Core.subtract(gray, smoothed, bright)
                Imgproc.threshold(bright, bright, 4.0, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.Canny(gray, weak, 25.0, 65.0, 3, true)
                Imgproc.dilate(bright, bright, k3)
                Core.bitwise_and(weak, bright, weak)
                Imgproc.dilate(weak, weak, k3)
                Core.bitwise_or(support, weak, support)
                }
                else log.emit(AlignmentLogEvent("vpsg.extract.weak-photometric-policy", "unused-work-eliminated",
                    labels = mapOf("consumer" to "resolved-wall-confidence-replaces-support", "proposal" to "unchanged-strong-photometric")))
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
                    if (optimized) Imgproc.Canny(gradientX, gradientY, strong, 120.0, 240.0, true)
                    else Imgproc.Canny(gray, strong, 120.0, 240.0, 3, true)
                    Imgproc.dilate(strong, support, k3)
                    VpsgMaskEvidence.attach(log, "resolved-wall-support.gray8", support)
                    log.emit(AlignmentLogEvent("vpsg.extract.wall-confidence-policy",
                        thresholds = mapOf("cannyLow" to 120.0, "cannyHigh" to 240.0, "supportDilation" to 3.0),
                        labels = mapOf("observationPolicy" to RESOLVED_WALL_POLICY,
                            "weakBoundary" to "unknown-not-conflict", "artifact" to "resolved-wall-support.gray8")))
                }
                log.measure("vpsg.extract.valid-mask") {
                Core.bitwise_and(candidates, support, observed)
                wallEdges?.let { Core.bitwise_and(wallCandidates, support, it) }
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
                return Observation(observed, valid, proposal, revealed, wallEdges)
            } catch (error: Throwable) {
                observed.release(); valid.release(); throw error
            }
        } finally {
            hsv.release(); gray.release(); gradientX.release(); gradientY.release(); exclusion.release(); room.release(); room2.release()
            corridor.release(); candidates.release(); strong.release(); support.release()
            markerRed.release(); markerYellow.release()
            wallCandidates.release()
            uncertain.release(); invalid.release(); dilatedExclusion.release()
            k3.release(); k5.release(); k11.release()
            if (!transferred) { proposal.release(); revealed?.release(); wallEdges?.release() }
        }
    }

    /** Small saturated red enemy markers can fall inside the wraparound room hue range.
     * Exclude their footprint as occluded evidence, not as either walls or known empty floor.
     * Large connected color fields remain available as real room structure.
     */
    private fun excludeRedMarkers(hsv: Mat, exclusion: Mat, log: AlignmentLogSink, prepared: Mat? = null) {
        val low = prepared ?: Mat(); val high = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            if (prepared == null) {
            Core.inRange(hsv, Scalar(0.0, 65.0, 95.0), Scalar(5.0, 255.0, 255.0), low)
            Core.inRange(hsv, Scalar(160.0, 65.0, 95.0), Scalar(179.0, 255.0, 255.0), high)
            Core.bitwise_or(low, high, low)
            }
            AlignmentCancellation.checkpoint("vpsg.extract.marker-components.before")
            val bounds = Imgproc.boundingRect(low)
            val count = if (bounds.width == 0 || bounds.height == 0) 1 else {
                val roi = low.submat(bounds)
                try { Imgproc.connectedComponentsWithStats(roi, labels, stats, centroids) } finally { roi.release() }
            }
            AlignmentCancellation.checkpoint("vpsg.extract.marker-components.after")
            val components = ArrayList<Double>()
            val componentStats = if (count > 1) IntArray(count * Imgproc.CC_STAT_MAX).also { stats.get(0, 0, it) } else IntArray(0)
            for (i in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.extract.marker-component")
                val x = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_LEFT] + bounds.x
                val y = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_TOP] + bounds.y
                val w = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_WIDTH]
                val h = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_HEIGHT]
                val area = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_AREA]
                val marker = area >= 4 && w <= 32 && h <= 32
                components.addAll(listOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), area.toDouble(), if (marker) 1.0 else 0.0))
                if (marker) Imgproc.rectangle(exclusion, Point((x - 2).toDouble(), (y - 2).toDouble()),
                    Point((x + w + 1).toDouble(), (y + h + 1).toDouble()), Scalar.all(255.0), -1)
            }
            log.emit(AlignmentLogEvent("vpsg.extract.marker-evidence", measurements = mapOf(
                "seedPixels" to Core.countNonZero(low).toDouble(), "components" to (count - 1).toDouble()),
                thresholds = mapOf("minimumComponentArea" to 4.0, "maximumComponentWidth" to 32.0,
                    "maximumComponentHeight" to 32.0, "componentPadding" to 2.0, "occlusionDilation" to 5.0),
                series = mapOf("componentRoiXYWH" to listOf(bounds.x.toDouble(), bounds.y.toDouble(), bounds.width.toDouble(), bounds.height.toDouble()),
                    "lowHsvMin" to listOf(0.0, 65.0, 95.0), "lowHsvMax" to listOf(5.0, 255.0, 255.0),
                    "highHsvMin" to listOf(160.0, 65.0, 95.0), "highHsvMax" to listOf(179.0, 255.0, 255.0),
                    "componentXYWHAreaExcluded" to components),
                labels = mapOf("artifact" to "occlusion-mask.gray8", "policy" to "small-red-markers-are-occluded-not-structure")))
        } finally { if (prepared == null) low.release(); high.release(); labels.release(); stats.release(); centroids.release() }
    }

    private fun excludePlayerMarkers(hsv: Mat, exclusion: Mat, log: AlignmentLogSink, prepared: Mat? = null) {
        val yellow = prepared ?: Mat(); val labels = Mat(); val stats = Mat(); val centers = Mat()
        try {
            if (prepared == null) Core.inRange(hsv, Scalar(18.0, 100.0, 160.0), Scalar(38.0, 255.0, 255.0), yellow)
            val bounds = Imgproc.boundingRect(yellow)
            val count = if (bounds.width == 0 || bounds.height == 0) 1 else {
                val roi = yellow.submat(bounds)
                try { Imgproc.connectedComponentsWithStats(roi, labels, stats, centers) } finally { roi.release() }
            }
            val components = ArrayList<Double>()
            val componentStats = if (count > 1) IntArray(count * Imgproc.CC_STAT_MAX).also { stats.get(0, 0, it) } else IntArray(0)
            for (i in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.extract.player-component")
                val x = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_LEFT] + bounds.x
                val y = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_TOP] + bounds.y
                val w = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_WIDTH]
                val h = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_HEIGHT]
                val area = componentStats[i * Imgproc.CC_STAT_MAX + Imgproc.CC_STAT_AREA]
                // Upright numbered player glyph; diagonal yellow door ticks stay structural.
                val marker = area >= 8 && w in 4..24 && h in 7..32 && h >= w
                components.addAll(listOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), area.toDouble(), if (marker) 1.0 else 0.0))
                if (marker) Imgproc.rectangle(exclusion, Point((x - 4).toDouble(), (y - 3).toDouble()),
                    Point((x + w + 3).toDouble(), (y + h + 6).toDouble()), Scalar.all(255.0), -1)
            }
            log.emit(AlignmentLogEvent("vpsg.extract.player-evidence",
                thresholds = mapOf("minimumArea" to 8.0, "minimumWidth" to 4.0, "maximumWidth" to 24.0,
                    "minimumHeight" to 7.0, "maximumHeight" to 32.0),
                series = mapOf("componentRoiXYWH" to listOf(bounds.x.toDouble(), bounds.y.toDouble(), bounds.width.toDouble(), bounds.height.toDouble()),
                    "hsvMin" to listOf(18.0, 100.0, 160.0), "hsvMax" to listOf(38.0, 255.0, 255.0),
                    "paddingLTRB" to listOf(4.0, 3.0, 4.0, 7.0), "componentXYWHAreaExcluded" to components),
                labels = mapOf("policy" to "upright-yellow-player-occlusion", "artifact" to "occlusion-mask.gray8")))
        } finally { if (prepared == null) yellow.release(); labels.release(); stats.release(); centers.release() }
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
