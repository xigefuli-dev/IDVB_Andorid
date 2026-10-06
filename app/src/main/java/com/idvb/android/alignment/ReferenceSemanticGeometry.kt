package com.idvb.android.alignment

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Source appearance boundaries can explain live contours without inventing a wall.
 * Use the same room/corridor color domains as live extraction, then retain only source
 * photometric support. This includes measured concave hatch and stair boundaries. */
internal object ReferenceSemanticGeometry {
    data class Result(val edges: ByteArray, val photometricEdges: ByteArray, val contours: List<Double>, val points: List<Double>,
        val candidatePixels: Int, val supportedPixels: Int)
    val thresholds = mapOf("minimumValue" to 50.0, "openingKernel" to 5.0,
        "closingKernel" to 3.0, "minimumSourceArc" to 12.0, "minimumSourceHoleArea" to 100.0,
        "approximationEpsilon" to .55, "lineThickness" to 2.0,
        "sourceCannyLow" to 25.0, "sourceCannyHigh" to 65.0, "sourceSupportDilation" to 3.0)
    val colorRanges = mapOf("roomHsvMin" to listOf(0.0, 18.0, 50.0),
        "roomHsvMax" to listOf(25.0, 165.0, 200.0),
        "wrapRoomHsvMin" to listOf(170.0, 18.0, 50.0),
        "wrapRoomHsvMax" to listOf(179.0, 165.0, 200.0),
        "corridorHsvMin" to listOf(95.0, 14.0, 50.0),
        "corridorHsvMax" to listOf(130.0, 105.0, 200.0))

    fun extract(bgr: Mat, gray: Mat): Result {
        val hsv = Mat(); val room = Mat(); val wrap = Mat(); val corridor = Mat()
        val hierarchy = Mat(); val photo = Mat(); val support = Mat(); val appearanceDomain = Mat()
        val output = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val records = ArrayList<Double>(); val points = ArrayList<Double>()
        try {
            AlignmentCancellation.checkpoint("vpsg.reference.semantic.before")
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv, Scalar(0.0, 18.0, 50.0), Scalar(25.0, 165.0, 200.0), room)
            Core.inRange(hsv, Scalar(170.0, 18.0, 50.0), Scalar(179.0, 165.0, 200.0), wrap)
            Core.bitwise_or(room, wrap, room)
            Core.inRange(hsv, Scalar(95.0, 14.0, 50.0), Scalar(130.0, 105.0, 200.0), corridor)
            Core.bitwise_or(room, corridor, appearanceDomain)
            Imgproc.dilate(appearanceDomain, appearanceDomain, k3)
            for ((domain, mask) in listOf(room, corridor).withIndex()) {
                AlignmentCancellation.checkpoint("vpsg.reference.semantic.domain")
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k5)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k3)
                val contours = ArrayList<MatOfPoint>()
                try {
                    Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
                    for ((index, contour) in contours.withIndex()) {
                        AlignmentCancellation.checkpoint("vpsg.reference.semantic.contour")
                        val curve = MatOfPoint2f(*contour.toArray()); val poly = MatOfPoint2f()
                        try {
                            val arc = Imgproc.arcLength(curve, true)
                            val area = Imgproc.contourArea(contour)
                            val parent = hierarchy.get(0, index)[3].toInt()
                            val accepted = arc >= 12.0 && (parent < 0 || area >= 100.0)
                            records.addAll(listOf(domain.toDouble(), index.toDouble(), parent.toDouble(),
                                arc, area, if (accepted) 1.0 else 0.0))
                            if (!accepted) continue
                            Imgproc.approxPolyDP(curve, poly, .55, true)
                            val vertices = poly.toArray()
                            points.addAll(listOf(domain.toDouble(), index.toDouble(), vertices.size.toDouble()))
                            for (vertex in vertices) points.addAll(listOf(vertex.x, vertex.y))
                            val outline = MatOfPoint(*vertices)
                            try { Imgproc.drawContours(output, listOf(outline), -1, Scalar.all(255.0), 2, Imgproc.LINE_8) }
                            finally { outline.release() }
                        } finally { curve.release(); poly.release() }
                    }
                } finally { contours.forEach(MatOfPoint::release) }
            }
            val candidates = Core.countNonZero(output)
            AlignmentCancellation.checkpoint("vpsg.reference.semantic.photo.before")
            Imgproc.Canny(gray, photo, 25.0, 65.0, 3, true)
            Imgproc.dilate(photo, support, k3)
            Core.bitwise_and(output, support, output)
            // Interior corridor seams and decorations need no color-class transition.
            // These measured source pixels can explain forward appearance, but are never
            // mandatory absent-wall evidence or scale/translation proposals.
            Core.bitwise_and(photo, appearanceDomain, photo)
            Core.bitwise_or(output, photo, output)
            AlignmentCancellation.checkpoint("vpsg.reference.semantic.photo.after")
            return Result(ByteArray(gray.rows() * gray.cols()).also { output.get(0, 0, it) },
                ByteArray(gray.rows() * gray.cols()).also { photo.get(0, 0, it) }, records, points, candidates, Core.countNonZero(output))
        } finally {
            listOf(hsv, room, wrap, corridor, hierarchy, photo, support, appearanceDomain, output, k5, k3).forEach(Mat::release)
        }
    }
}
