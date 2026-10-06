package com.idvb.android.alignment

import com.idvb.android.recognize.cv.OpenCvRuntime
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ReferenceSemanticGeometryInstrumentedTest {
    @Test fun dimCorridorInteriorSeamDoesNotRequireAColorClassTransition() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(180, 220, CvType.CV_8UC3, Scalar(80.0, 65.0, 60.0))
        val gray = Mat()
        try {
            Imgproc.line(source, Point(100.0, 30.0), Point(100.0, 150.0), Scalar(115.0, 90.0, 85.0), 2)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            val result = ReferenceSemanticGeometry.extract(source, gray)
            assertTrue("A measured seam inside the same dim corridor domain must explain live structure",
                (97..103).any { result.edges[100 * 220 + it].toInt() and 255 > 128 })
        } finally { gray.release(); source.release() }
    }

    @Test fun measuredConcaveHatchBoundaryIsRetainedWithoutClosingItsNotch() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(180, 220, CvType.CV_8UC3, Scalar(60.0, 73.0, 89.0))
        val mask = Mat.zeros(180, 220, CvType.CV_8UC1)
        val outline = MatOfPoint(Point(75.0, 60.0), Point(96.0, 60.0), Point(96.0, 91.0),
            Point(116.0, 91.0), Point(116.0, 107.0), Point(75.0, 107.0))
        val gray = Mat()
        try {
            Imgproc.fillPoly(mask, listOf(outline), Scalar.all(255.0))
            source.setTo(Scalar(46.0, 57.0, 69.0), mask)
            Imgproc.polylines(source, listOf(outline), true, Scalar(73.0, 89.0, 110.0), 1)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            val result = ReferenceSemanticGeometry.extract(source, gray)
            fun near(x: Int, y: Int) = (y - 3..y + 3).any { row ->
                (x - 3..x + 3).any { col -> result.edges[row * 220 + col].toInt() and 255 > 128 }
            }
            assertTrue("Measured lower boundary must explain live contours", near(85, 107))
            assertTrue("Measured concave step must survive", near(96, 76))
            assertFalse("Do not close the notch with an invented rectangle side", near(116, 76))
            assertFalse("Do not divide the real L into invented rectangles", near(96, 101))
            assertTrue(result.supportedPixels > 0)
        } finally { gray.release(); outline.release(); mask.release(); source.release() }
    }

    @Test fun colorClassificationAloneCannotInventAReferenceBoundary() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(100, 150, CvType.CV_8UC3, Scalar(66.0, 80.0, 97.0))
        val right = source.submat(Rect(75, 0, 75, 100)); val gray = Mat()
        try {
            right.setTo(Scalar(102.0, 89.0, 87.0))
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            val result = ReferenceSemanticGeometry.extract(source, gray)
            assertTrue("Exercise a real semantic proposal", result.candidatePixels > 0)
            assertEquals("No measured photometric edge means no added reference line", 0, result.supportedPixels)
        } finally { gray.release(); right.release(); source.release() }
    }
}
