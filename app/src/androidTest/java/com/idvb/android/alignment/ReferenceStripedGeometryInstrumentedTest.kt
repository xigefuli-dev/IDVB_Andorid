package com.idvb.android.alignment

import com.idvb.android.recognize.cv.OpenCvRuntime
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ReferenceStripedGeometryInstrumentedTest {
    private fun frame(borders: Boolean, stripes: Boolean): ReferenceStripedFrameGeometry.Result {
        OpenCvRuntime.requireAvailable()
        val source = Mat(100, 150, CvType.CV_8UC3, Scalar(66.0, 80.0, 97.0))
        val gray = Mat()
        try {
            if (stripes) for (x in 48..78 step 5) Imgproc.line(source, Point(x.toDouble(), 36.0),
                Point(x.toDouble(), 58.0), Scalar(102.0, 119.0, 143.0), 1)
            if (borders) Imgproc.rectangle(source, Point(45.0, 35.0), Point(80.0, 59.0), Scalar(102.0, 119.0, 143.0), 1)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            return ReferenceStripedFrameGeometry.extract(source, gray)
        } finally { source.release(); gray.release() }
    }
    @Test fun requiresClosedContrastFrameAndRepeatedSlats() {
        val accepted = frame(true, true)
        assertTrue(accepted.rectangles.toString(), accepted.rectangles.isNotEmpty())
        assertEquals(4, accepted.rectangles.size)
        assertEquals(45.0, accepted.rectangles[0], 2.0)
        assertEquals(35.0, accepted.rectangles[1], 2.0)
        assertEquals(80.0, accepted.rectangles[2], 2.0)
        assertEquals(59.0, accepted.rectangles[3], 2.0)
        for ((borders, stripes) in listOf(false to true, true to false, false to false)) {
            val result = frame(borders, stripes)
            assertTrue(result.rectangles.toString(), result.rectangles.isEmpty())
            assertFalse(result.edges.any { (it.toInt() and 255) > 0 })
        }
    }
    @Test fun corridorStairTexturesDoNotInventClosedRoomFrames() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(100, 150, CvType.CV_8UC3, Scalar(102.0, 89.0, 87.0))
        val gray = Mat()
        try {
            for (x in 48..78 step 5) Imgproc.line(source, Point(x.toDouble(), 36.0),
                Point(x.toDouble(), 58.0), Scalar(172.0, 157.0, 154.0), 1)
            Imgproc.rectangle(source, Point(45.0, 35.0), Point(80.0, 59.0), Scalar(172.0, 157.0, 154.0), 1)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            val result = ReferenceStripedFrameGeometry.extract(source, gray)
            assertTrue(result.rectangles.toString(), result.rectangles.isEmpty())
            assertFalse(result.edges.any { (it.toInt() and 255) > 0 })
        } finally { source.release(); gray.release() }
    }
}
