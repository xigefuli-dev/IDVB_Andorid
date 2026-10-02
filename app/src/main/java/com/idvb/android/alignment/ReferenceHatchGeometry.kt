package com.idvb.android.alignment

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Restore the framed diagonal hatch erased by the route profile's hole filling.
 * Only immutable reference pixels are inputs. An isolated diagonal, route, numeral or
 * watermark is insufficient: both measured horizontal borders, vertical border support
 * and four distinct parallel stripe bands must describe the same rectangle. */
internal object ReferenceHatchGeometry {
    data class Result(val edges: ByteArray, val candidates: List<Double>, val rectangles: List<Double>)
    private data class Run(val left: Int, val right: Int, val y: Int)
    val thresholds = mapOf("topHatKernel" to 5.0, "minimumTopHatContrast" to 6.0,
        "diagonalKernel" to 9.0, "groupDilation" to 7.0, "horizontalKernel" to 13.0,
        "minimumComponentArea" to 100.0, "maximumComponentSpan" to 160.0,
        "minimumFrameSpan" to 12.0, "maximumFrameSpan" to 120.0,
        "borderEndpointTolerance" to 3.0, "verticalProbeRadius" to 2.0,
        "verticalSupportDilation" to 3.0, "minimumVerticalSupport" to .75,
        "verticalLineKernel" to 13.0, "minimumContinuousVerticalSupport" to .75,
        "minimumDiagonalDensity" to .08, "minimumStripePixels" to 8.0,
        "minimumParallelBands" to 4.0, "duplicateFrameTolerance" to 4.0,
        "frameLineThickness" to 2.0, "minimumAspectRatio" to .4, "maximumAspectRatio" to 2.5)
    val colorRanges = mapOf("roomHsvMin" to listOf(0.0, 18.0, 50.0),
        "roomHsvMax" to listOf(25.0, 165.0, 200.0),
        "roomWrapHsvMin" to listOf(170.0, 18.0, 50.0),
        "roomWrapHsvMax" to listOf(179.0, 165.0, 200.0),
        "corridorHsvMin" to listOf(95.0, 14.0, 50.0),
        "corridorHsvMax" to listOf(130.0, 105.0, 200.0))

    fun extract(bgr: Mat, gray: Mat): Result {
        val hsv = Mat(); val room = Mat(); val wrap = Mat(); val corridor = Mat()
        val high = Mat(); val binary = Mat(); val diagonal = Mat(); val opposite = Mat()
        val grouped = Mat(); val horizontal = Mat(); val vertical = Mat(); val near = Mat()
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val output = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val kh = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 1.0))
        val kv = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 13.0))
        val kd = Mat.zeros(9, 9, CvType.CV_8UC1)
        val ko = Mat.zeros(9, 9, CvType.CV_8UC1)
        try {
            kd.put(0, 0, ByteArray(81) { if (it / 9 == it % 9) 1 else 0 })
            ko.put(0, 0, ByteArray(81) { if (it / 9 + it % 9 == 8) 1 else 0 })
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(0.0, 18.0, 50.0), Scalar(25.0, 165.0, 200.0), room)
            Core.inRange(hsv, Scalar(170.0, 18.0, 50.0), Scalar(179.0, 165.0, 200.0), wrap)
            Core.inRange(hsv, Scalar(95.0, 14.0, 50.0), Scalar(130.0, 105.0, 200.0), corridor)
            Core.bitwise_or(room, wrap, room); Core.bitwise_or(room, corridor, room)
            Imgproc.morphologyEx(gray, high, Imgproc.MORPH_TOPHAT, k5)
            Core.inRange(high, Scalar.all(6.0), Scalar.all(255.0), binary)
            Core.bitwise_and(binary, room, binary)
            Imgproc.morphologyEx(binary, diagonal, Imgproc.MORPH_OPEN, kd)
            Imgproc.morphologyEx(binary, opposite, Imgproc.MORPH_OPEN, ko)
            Core.bitwise_or(diagonal, opposite, diagonal)
            Imgproc.dilate(diagonal, grouped, k7)
            AlignmentCancellation.checkpoint("vpsg.reference.hatch-components.before")
            val count = Imgproc.connectedComponentsWithStats(grouped, labels, stats, centroids)
            AlignmentCancellation.checkpoint("vpsg.reference.hatch-components.after")
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, kh)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, kv)
            Imgproc.dilate(binary, near, k3)
            val width = gray.cols(); val height = gray.rows()
            fun pixels(mat: Mat) = ByteArray(width * height).also { mat.get(0, 0, it) }
            val hd = pixels(horizontal); val vd = pixels(vertical); val nd = pixels(near); val dd = pixels(diagonal)
            fun hit(data: ByteArray, x: Int, y: Int) = (data[y * width + x].toInt() and 255) > 128
            val proposed = ArrayList<Double>(); val rectangles = ArrayList<IntArray>()
            for (component in 1 until count) {
                AlignmentCancellation.checkpoint("vpsg.reference.hatch-component")
                val x = stats.get(component, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(component, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(component, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(component, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(component, Imgproc.CC_STAT_AREA)[0].toInt()
                if (w !in 12..160 || h !in 12..160 || area < 100) continue
                val runs = ArrayList<Run>()
                for (row in y until y + h) {
                    AlignmentCancellation.checkpoint("vpsg.reference.hatch-row")
                    var column = x
                    while (column < x + w) {
                        if (!hit(hd, column, row)) { column++; continue }
                        val left = column
                        while (column < x + w && hit(hd, column, row)) column++
                        if (column - left >= 12) runs += Run(left, column - 1, row)
                    }
                }
                for (top in runs) for (bottom in runs) {
                    AlignmentCancellation.checkpoint("vpsg.reference.hatch-frame")
                    val span = bottom.y - top.y
                    val frameWidth = top.right - top.left
                    if (span !in 12..120 || frameWidth !in 12..120 ||
                        abs(top.left - bottom.left) > 3 || abs(top.right - bottom.right) > 3 ||
                        frameWidth.toDouble() / span !in .4..2.5) continue
                    fun side(center: Int): Pair<Int, Double> {
                        var bestX = center; var best = -1.0
                        for (column in max(0, center - 2)..minOf(width - 1, center + 2)) {
                            val support = (top.y..bottom.y).count { hit(nd, column, it) }.toDouble() / (span + 1)
                            if (support > best || support == best && abs(column - center) < abs(bestX - center)) {
                                best = support; bestX = column
                            }
                        }
                        return bestX to best
                    }
                    val (left, leftSupport) = side(((top.left + bottom.left) / 2.0).roundToInt())
                    val (right, rightSupport) = side(((top.right + bottom.right) / 2.0).roundToInt())
                    if (leftSupport < .75 || rightSupport < .75) continue
                    val sums = IntArray(frameWidth + span + 10); val differences = IntArray(sums.size)
                    var diagonalPixels = 0
                    for (row in top.y + 1 until bottom.y) for (column in left + 1 until right) {
                        if (!hit(dd, column, row)) continue
                        val localX = column - left; val localY = row - top.y
                        sums[localX + localY]++; differences[localX - localY + span + 3]++
                        diagonalPixels++
                    }
                    fun bands(counts: IntArray): Int {
                        var previous = -2; var bands = 0
                        for (i in counts.indices) if (counts[i] >= 8) {
                            if (i - previous > 1) bands++
                            previous = i
                        }
                        return bands
                    }
                    val repeats = max(bands(sums), bands(differences))
                    val density = diagonalPixels.toDouble() / ((right - left) * span)
                    // Diagonal stripe tips can satisfy a dilated column count without any
                    // vertical border. Require separately measured, continuous source lines.
                    fun continuousSide(center: Int): Double =
                        (max(0, center - 2)..minOf(width - 1, center + 2)).maxOf { column ->
                            (top.y..bottom.y).count { hit(vd, column, it) }.toDouble() / (span + 1)
                        }
                    val leftContinuous = continuousSide(left); val rightContinuous = continuousSide(right)
                    val accepted = density >= .08 && repeats >= 4 && leftContinuous >= .75 && rightContinuous >= .75
                    proposed.addAll(listOf(left.toDouble(), top.y.toDouble(), right.toDouble(), bottom.y.toDouble(),
                        leftSupport, rightSupport, density, repeats.toDouble(), leftContinuous, rightContinuous, if (accepted) 1.0 else 0.0))
                    if (!accepted) continue
                    val box = intArrayOf(left, top.y, right, bottom.y)
                    if (rectangles.any { existing -> box.indices.all { abs(box[it] - existing[it]) <= 4 } }) continue
                    rectangles += box
                    Imgproc.rectangle(output, Point(left.toDouble(), top.y.toDouble()),
                        Point(right.toDouble(), bottom.y.toDouble()), Scalar.all(255.0), 2)
                }
            }
            return Result(pixels(output), proposed, rectangles.flatMap { it.map(Int::toDouble) })
        } finally {
            listOf(hsv, room, wrap, corridor, high, binary, diagonal, opposite, grouped, horizontal,
                vertical, near, labels, stats, centroids, output, k5, k7, k3, kh, kv, kd, ko).forEach(Mat::release)
        }
    }
}
