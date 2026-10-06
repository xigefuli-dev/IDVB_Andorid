package com.idvb.android.alignment

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Static framed slats in the source map also appear as live semantic contours.
 * Require four measured borders and repeated vertical stripes; never use a live residual. */
internal object ReferenceStripedFrameGeometry {
    data class Result(val edges: ByteArray, val candidates: List<Double>, val rectangles: List<Double>,
        val components: List<Double>, val horizontalRunPairs: List<Double>)
    private data class Run(val left: Int, val right: Int, val y: Int)
    val colorRanges = mapOf("roomHsvMin" to listOf(0.0, 18.0, 50.0), "roomHsvMax" to listOf(25.0, 165.0, 200.0),
        "wrapRoomHsvMin" to listOf(170.0, 18.0, 50.0), "wrapRoomHsvMax" to listOf(179.0, 165.0, 200.0))
    val thresholds = mapOf("topHatKernel" to 5.0, "minimumContrast" to 6.0,
        "verticalKernel" to 7.0, "horizontalKernel" to 7.0,
        "groupWidth" to 5.0, "groupHeight" to 3.0, "minimumArea" to 80.0,
        "minimumWidth" to 12.0, "minimumHeight" to 8.0, "maximumSpan" to 100.0,
        "borderSearchPadding" to 6.0, "borderEndpointTolerance" to 2.0,
        "minimumHorizontalRunOverlap" to .75,
        "sideProbeRadius" to 2.0, "minimumSideSupport" to .75, "minimumHorizontalSupport" to .75,
        "directionalEdgeMinimumContrast" to 6.0, "directionalEdgeMinimumContinuity" to .75,
        "sideFlankDistance" to 6.0, "minimumSideContrast" to 12.0,
        "minimumStripeSupport" to .70, "minimumStripeBands" to 4.0,
        "minimumStripeDensity" to .10, "maximumStripeDensity" to .80,
        "duplicateTolerance" to 3.0, "frameLineThickness" to 2.0)

    fun extract(bgr: Mat, gray: Mat, preparedHsv: Mat? = null, minimumValue: Double = 50.0): Result {
        val hsv = Mat(); val semantic = Mat(); val wrap = Mat()
        val high = Mat(); val binary = Mat(); val vertical = Mat(); val horizontal = Mat()
        val grouped = Mat(); val labels = Mat(); val stats = Mat(); val centers = Mat()
        val output = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val kv = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 7.0))
        val kh = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 1.0))
        val kg = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 3.0))
        try {
            if (preparedHsv == null) Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val colors = preparedHsv ?: hsv
            Core.inRange(colors, Scalar(0.0, 18.0, minimumValue), Scalar(25.0, 165.0, 200.0), semantic)
            Core.inRange(colors, Scalar(170.0, 18.0, minimumValue), Scalar(179.0, 165.0, 200.0), wrap)
            // Room grates have closed rectangular frames. Corridor stair textures can
            // form a local rectangle inside an L shape; they are not closed walls.
            Core.bitwise_or(semantic, wrap, semantic)
            Imgproc.morphologyEx(gray, high, Imgproc.MORPH_TOPHAT, k5)
            Core.inRange(high, Scalar.all(6.0), Scalar.all(255.0), binary)
            Core.bitwise_and(binary, semantic, binary)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, kv)
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, kh)
            Core.bitwise_or(vertical, horizontal, grouped)
            Imgproc.dilate(grouped, grouped, kg)
            AlignmentCancellation.checkpoint("vpsg.reference.striped-components.before")
            val count = Imgproc.connectedComponentsWithStats(grouped, labels, stats, centers)
            AlignmentCancellation.checkpoint("vpsg.reference.striped-components.after")
            val width = gray.cols(); val height = gray.rows()
            fun pixels(mat: Mat) = ByteArray(width * height).also { mat.get(0, 0, it) }
            val hd = pixels(horizontal); val vd = pixels(vertical)
            val gd = pixels(gray)
            fun hit(data: ByteArray, x: Int, y: Int) = (data[y * width + x].toInt() and 255) > 128
            val componentStats = IntArray(count * Imgproc.CC_STAT_MAX).also { stats.get(0, 0, it) }
            val componentValues = DoubleArray(maxOf(0, count - 1) * 6)
            val evidence = ArrayList<Double>(); val rectangles = ArrayList<IntArray>()
            val horizontalRunPairs = ArrayList<Double>()
            for (component in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.reference.striped-component")
                val at = component * Imgproc.CC_STAT_MAX
                val x = componentStats[at + Imgproc.CC_STAT_LEFT]
                val y = componentStats[at + Imgproc.CC_STAT_TOP]
                val w = componentStats[at + Imgproc.CC_STAT_WIDTH]
                val h = componentStats[at + Imgproc.CC_STAT_HEIGHT]
                val area = componentStats[at + Imgproc.CC_STAT_AREA]
                val eligible = w in 12..100 && h in 8..100 && area >= 80
                val evidenceAt = (component - 1) * 6
                componentValues[evidenceAt] = x.toDouble(); componentValues[evidenceAt + 1] = y.toDouble()
                componentValues[evidenceAt + 2] = w.toDouble(); componentValues[evidenceAt + 3] = h.toDouble()
                componentValues[evidenceAt + 4] = area.toDouble(); componentValues[evidenceAt + 5] = if (eligible) 1.0 else 0.0
                if (!eligible) continue
                val runs = ArrayList<Run>()
                for (row in maxOf(0, y - 6) until minOf(height, y + h + 6)) {
                    var column = maxOf(0, x - 6)
                    val end = minOf(width, x + w + 6)
                    while (column < end) {
                        if (!hit(hd, column, row)) { column++; continue }
                        val left = column
                        while (column < end && hit(hd, column, row)) column++
                        if (column - left >= 12) runs += Run(left, column - 1, row)
                    }
                }
                for (top in runs) for (bottom in runs) {
                    AlignmentCancellation.checkpoint("vpsg.reference.striped-frame")
                    val span = bottom.y - top.y
                    if (span !in 8..100 || top.right - top.left !in 12..100) continue
                    val endpointsAgree = abs(top.left - bottom.left) <= 2 && abs(top.right - bottom.right) <= 2
                    val unionLeft = minOf(top.left, bottom.left); val unionRight = maxOf(top.right, bottom.right)
                    val overlap = (minOf(top.right, bottom.right) - maxOf(top.left, bottom.left) + 1)
                        .coerceAtLeast(0).toDouble() / (unionRight - unionLeft + 1)
                    // A dim end can shorten one horizontal top-hat run without removing
                    // the frame. Propose its measured union only when both runs cover
                    // the same border domain; all four full-span support gates below
                    // still have to pass. Never extrapolate beyond a measured endpoint.
                    val proposed = unionRight - unionLeft in 12..100 && (endpointsAgree || overlap >= .75)
                    horizontalRunPairs.addAll(listOf(top.left.toDouble(), top.right.toDouble(), top.y.toDouble(),
                        bottom.left.toDouble(), bottom.right.toDouble(), bottom.y.toDouble(), overlap,
                        if (endpointsAgree) 1.0 else 0.0, if (proposed) 1.0 else 0.0))
                    if (!proposed) continue
                    val left = if (endpointsAgree) (top.left + bottom.left) / 2 else unionLeft
                    val right = if (endpointsAgree) (top.right + bottom.right) / 2 else unionRight
                    fun side(center: Int, direction: Int) = (maxOf(0, center - 2)..minOf(width - 1, center + 2)).maxOf { col ->
                        val flank = (center + direction * 6).coerceIn(0, width - 1)
                        (top.y..bottom.y).count { row ->
                            (gd[row * width + col].toInt() and 255) - (gd[row * width + flank].toInt() and 255) >= 12
                        }.toDouble() / (span + 1)
                    }
                    fun verticalContinuity(center: Int) =
                        (maxOf(0, center - 2)..minOf(width - 1, center + 2)).maxOf { col ->
                            (top.y..bottom.y).count { row -> hit(vd, col, row) }.toDouble() / (span + 1)
                        }
                    val leftSupport = maxOf(side(left, -1), verticalContinuity(left))
                    val rightSupport = maxOf(side(right, 1), verticalContinuity(right))
                    fun horizontalSide(center: Int, direction: Int) =
                        (maxOf(0, center - 2)..minOf(height - 1, center + 2)).maxOf { row ->
                            val flank = (center + direction * 6).coerceIn(0, height - 1)
                            (left..right).count { col ->
                                (gd[row * width + col].toInt() and 255) - (gd[flank * width + col].toInt() and 255) >= 12
                            }.toDouble() / (right - left + 1)
                        }
                    fun horizontalContinuity(center: Int) =
                        (maxOf(0, center - 2)..minOf(height - 1, center + 2)).maxOf { row ->
                            (left..right).count { col -> hit(hd, col, row) }.toDouble() / (right - left + 1)
                        }
                    // Adjacent source textures can make a fixed six-pixel flank brighter
                    // than the frame. A continuous directional top-hat edge is separate
                    // measured contrast evidence, with the same continuity threshold.
                    val topSupport = maxOf(horizontalSide(top.y, -1), horizontalContinuity(top.y))
                    val bottomSupport = maxOf(horizontalSide(bottom.y, 1), horizontalContinuity(bottom.y))
                    var bands = 0; var previous = false; var stripePixels = 0
                    for (column in left + 1 until right) {
                        val n = (top.y + 1 until bottom.y).count { hit(vd, column, it) }
                        stripePixels += n
                        val supported = n.toDouble() / (span - 1) >= .70
                        if (supported && !previous) bands++
                        previous = supported
                    }
                    val density = stripePixels.toDouble() / ((right - left - 1) * (span - 1))
                    val accepted = leftSupport >= .75 && rightSupport >= .75 && topSupport >= .75 &&
                        bottomSupport >= .75 && bands >= 4 && density in .10.. .80
                    evidence.addAll(listOf(left.toDouble(), top.y.toDouble(), right.toDouble(), bottom.y.toDouble(),
                        leftSupport, rightSupport, topSupport, bottomSupport, bands.toDouble(), density, if (accepted) 1.0 else 0.0))
                    if (!accepted) continue
                    val box = intArrayOf(left, top.y, right, bottom.y)
                    if (rectangles.any { existing -> box.indices.all { abs(box[it] - existing[it]) <= 3 } }) continue
                    rectangles += box
                    Imgproc.rectangle(output, Point(left.toDouble(), top.y.toDouble()),
                        Point(right.toDouble(), bottom.y.toDouble()), Scalar.all(255.0), 2)
                }
            }
            return Result(pixels(output), evidence, rectangles.flatMap { it.map(Int::toDouble) },
                componentValues.asList(), horizontalRunPairs)
        } finally {
            listOf(hsv, semantic, wrap, high, binary, vertical, horizontal, grouped,
                labels, stats, centers, output, k5, kv, kh, kg).forEach(Mat::release)
        }
    }
}
