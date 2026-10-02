package com.idvb.android.alignment

import com.idvb.android.recognize.cv.OpenCvRuntime
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ReferencePartitionGeometryInstrumentedTest {
    private fun extract(mode: String): ReferencePartitionGeometry.Result {
        OpenCvRuntime.requireAvailable()
        val source = Mat.zeros(210, 230, CvType.CV_8UC3)
        val floor = source.submat(Rect(20, 20, 181, 171))
        val prebuilt = Mat.zeros(210, 230, CvType.CV_8UC1)
        val gray = Mat()
        try {
            floor.setTo(Scalar(79.0, 65.0, 61.0))
            val wall = Scalar(110.0, 95.0, 90.0)
            Imgproc.rectangle(prebuilt, Point(20.0, 20.0), Point(200.0, 190.0), Scalar.all(255.0), 2)
            Imgproc.rectangle(source, Point(20.0, 20.0), Point(200.0, 190.0), wall, 2)
            val color = when (mode) {
                "route" -> Scalar(80.0, 220.0, 90.0)
                "watermark" -> Scalar.all(230.0)
                "numeral" -> Scalar(0.0, 210.0, 230.0)
                else -> wall
            }
            val end = if (mode == "unanchored") 175.0 else 200.0
            val branchX = if (mode == "corner") 55.0 else 115.0
            Imgproc.line(source, Point(55.0, 75.0), Point(end, 75.0), color, 2)
            Imgproc.line(source, Point(branchX, 75.0), Point(branchX, 122.0), color, 2)
            if (mode == "unknown") {
                val outside = source.submat(Rect(121, 78, 24, 47))
                try { outside.setTo(Scalar.all(0.0)) } finally { outside.release() }
            }
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            return ReferencePartitionGeometry.extract(source, gray, prebuilt)
        } finally { floor.release(); source.release(); prebuilt.release(); gray.release() }
    }

    @Test fun anchoredPartitionKeepsItsMeasuredFreestandingEnd() {
        val result = extract("partition")
        assertEquals(result.partitions.toString(), 12, result.partitions.size)
        fun edge(x: Int, y: Int) = result.edges[y * 230 + x].toInt() and 255 > 128
        assertTrue(edge(115, 100))
        assertFalse("An open partition cannot become a closed hole", edge(130, 122))
        assertFalse("No extrapolation below the measured endpoint", edge(115, 135))
    }
    @Test fun unanchoredCornerAndUnknownFlanksDoNotAddWalls() {
        for (mode in listOf("unanchored", "corner", "unknown")) {
            val result = extract(mode)
            assertTrue("$mode: ${result.partitions}", result.partitions.isEmpty())
            assertFalse(result.edges.any { it.toInt() and 255 > 128 })
        }
    }
    @Test fun routeNumeralsAndWatermarksCannotBecomePartitions() {
        for (mode in listOf("route", "numeral", "watermark")) {
            val result = extract(mode)
            assertTrue("$mode: ${result.partitions}", result.partitions.isEmpty())
            assertFalse(result.edges.any { it.toInt() and 255 > 128 })
        }
    }
}
