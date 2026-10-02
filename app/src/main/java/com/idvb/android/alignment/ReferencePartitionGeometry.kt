package com.idvb.android.alignment

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Recover a measured thin T partition suppressed by the walkable-color contour profile.
 * The source supplies every output pixel. The original prebuilt supplies the containing
 * floor and an anchor for the parent wall; an unanchored ridge or a corner is insufficient. */
internal object ReferencePartitionGeometry {
    data class Result(val edges: ByteArray, val candidates: List<Double>, val partitions: List<Double>)
    private data class Segment(val vertical: Boolean, val x: Int, val y: Int, val width: Int,
        val height: Int, val positions: IntArray, val missing: Int, val anchor: Double,
        val domainSupport: Double, val flankSupport: Double)
    val thresholds = mapOf("topHatKernel" to 5.0, "minimumTopHatContrast" to 12.0,
        "axisKernel" to 25.0, "minimumSpan" to 25.0, "maximumSpan" to 180.0,
        "minimumThickness" to 2.0, "maximumThickness" to 5.0,
        "minimumDomainSupport" to .98, "flankProbeDistance" to 6.0,
        "minimumFlankSupport" to .9, "minimumMissingPixels" to 12.0,
        "existingWallDistance" to 3.0, "maximumParentAnchorDistance" to 4.0,
        "maximumJunctionDistance" to 5.0, "minimumParentInteriorMargin" to 8.0,
        "outputDilation" to 3.0)
    val colorRanges = mapOf("corridorHsvMin" to listOf(95.0, 14.0, 50.0),
        "corridorHsvMax" to listOf(130.0, 105.0, 200.0))

    fun extract(bgr: Mat, gray: Mat, originalPrebuilt: Mat): Result {
        val hsv = Mat(); val semantic = Mat(); val high = Mat(); val binary = Mat()
        val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat()
        val domain = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val contourInput = originalPrebuilt.clone(); val inverse = Mat(); val distance = Mat()
        val axis = Mat(); val labels = Mat(); val stats = Mat(); val centers = Mat()
        val output = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val kh = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 1.0))
        val kv = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 25.0))
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            AlignmentCancellation.checkpoint("vpsg.reference.partition-start")
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(95.0, 14.0, 50.0), Scalar(130.0, 105.0, 200.0), semantic)
            Imgproc.morphologyEx(gray, high, Imgproc.MORPH_TOPHAT, k5)
            Core.inRange(high, Scalar.all(12.0), Scalar.all(255.0), binary)
            Core.bitwise_and(binary, semantic, binary)
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.drawContours(domain, contours, -1, Scalar.all(255.0), Imgproc.FILLED)
            Core.bitwise_not(originalPrebuilt, inverse)
            Imgproc.distanceTransform(inverse, distance, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
            val width = gray.cols(); val height = gray.rows(); val size = width * height
            val semanticPixels = ByteArray(size).also { semantic.get(0, 0, it) }
            val domainPixels = ByteArray(size).also { domain.get(0, 0, it) }
            val distances = FloatArray(size).also { distance.get(0, 0, it) }
            val segments = ArrayList<Segment>(); val candidates = ArrayList<Double>()
            fun hit(data: ByteArray, position: Int) = (data[position].toInt() and 255) > 128
            for (vertical in listOf(false, true)) {
                AlignmentCancellation.checkpoint("vpsg.reference.partition-axis.before")
                Imgproc.morphologyEx(binary, axis, Imgproc.MORPH_OPEN, if (vertical) kv else kh)
                val count = Imgproc.connectedComponentsWithStats(axis, labels, stats, centers)
                AlignmentCancellation.checkpoint("vpsg.reference.partition-axis.after")
                val components = IntArray(size).also { labels.get(0, 0, it) }
                for (component in 1 until count) {
                    AlignmentCancellation.checkpoint("vpsg.reference.partition-component")
                    val x = stats.get(component, Imgproc.CC_STAT_LEFT)[0].toInt()
                    val y = stats.get(component, Imgproc.CC_STAT_TOP)[0].toInt()
                    val w = stats.get(component, Imgproc.CC_STAT_WIDTH)[0].toInt()
                    val h = stats.get(component, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                    val span = if (vertical) h else w; val thickness = if (vertical) w else h
                    if (span !in 25..180 || thickness !in 2..5) continue
                    val positions = ArrayList<Int>(); var inside = 0; var flank = 0; var missing = 0
                    var anchor = Double.POSITIVE_INFINITY
                    for (row in y until y + h) for (column in x until x + w) {
                        val position = row * width + column
                        if (components[position] != component) continue
                        positions += position
                        if (hit(domainPixels, position)) inside++
                        val left = if (vertical) row * width + maxOf(0, column - 6)
                            else maxOf(0, row - 6) * width + column
                        val right = if (vertical) row * width + minOf(width - 1, column + 6)
                            else minOf(height - 1, row + 6) * width + column
                        if (hit(semanticPixels, left) && hit(semanticPixels, right)) flank++
                        if (distances[position] > 3f) missing++
                        anchor = minOf(anchor, distances[position].toDouble())
                    }
                    val domainSupport = inside.toDouble() / positions.size
                    val flankSupport = flank.toDouble() / positions.size
                    val include = domainSupport >= .98 && flankSupport >= .9
                    candidates.addAll(listOf(if (vertical) 1.0 else 0.0, x.toDouble(), y.toDouble(),
                        w.toDouble(), h.toDouble(), domainSupport, flankSupport, missing.toDouble(), anchor,
                        if (include) 1.0 else 0.0))
                    if (include) segments += Segment(vertical, x, y, w, h, positions.toIntArray(), missing,
                        anchor, domainSupport, flankSupport)
                }
            }
            val recovered = ByteArray(size); val partitions = ArrayList<Double>()
            for (branch in segments) {
                AlignmentCancellation.checkpoint("vpsg.reference.partition-junction")
                if (branch.missing < 12) continue
                val vertical = branch.vertical
                val center = if (vertical) branch.x + (branch.width - 1) / 2.0
                    else branch.y + (branch.height - 1) / 2.0
                val start = if (vertical) branch.y else branch.x
                val end = if (vertical) branch.y + branch.height - 1 else branch.x + branch.width - 1
                for (parent in segments) {
                    if (parent.vertical == vertical || parent.anchor > 4.0) continue
                    val cross = if (vertical) parent.y + (parent.height - 1) / 2.0
                        else parent.x + (parent.width - 1) / 2.0
                    val low = if (vertical) parent.x else parent.y
                    val highEnd = if (vertical) parent.x + parent.width - 1 else parent.y + parent.height - 1
                    if (minOf(abs(start - cross), abs(end - cross)) > 5.0 ||
                        center < low + 8.0 || center > highEnd - 8.0) continue
                    branch.positions.forEach { recovered[it] = 255.toByte() }
                    parent.positions.forEach { recovered[it] = 255.toByte() }
                    partitions.addAll(listOf(branch.x.toDouble(), branch.y.toDouble(), branch.width.toDouble(),
                        branch.height.toDouble(), parent.x.toDouble(), parent.y.toDouble(), parent.width.toDouble(),
                        parent.height.toDouble(), branch.missing.toDouble(), parent.anchor,
                        branch.flankSupport, branch.domainSupport))
                }
            }
            output.put(0, 0, recovered)
            Imgproc.dilate(output, output, k3)
            return Result(ByteArray(size).also { output.get(0, 0, it) }, candidates, partitions)
        } finally {
            listOf(hsv, semantic, high, binary, hierarchy, domain, contourInput, inverse, distance,
                axis, labels, stats, centers, output, k5, kh, kv, k3).forEach(Mat::release)
            contours.forEach(MatOfPoint::release)
        }
    }
}
