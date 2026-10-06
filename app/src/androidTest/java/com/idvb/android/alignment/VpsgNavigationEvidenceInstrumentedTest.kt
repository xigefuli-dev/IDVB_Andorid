package com.idvb.android.alignment

import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class VpsgNavigationEvidenceInstrumentedTest {
    @Test fun compoundChestAnnotationsAreOccludedAtDifferentCaptureExtentsWhileDoorsAndWallsRemain() {
        OpenCvRuntime.requireAvailable()
        for (factor in listOf(.5, 1.0, 1.5, 2.0)) {
            val size = (875 * factor).toInt()
            val source = Mat(size, size, CvType.CV_8UC3, Scalar(60.0, 73.0, 89.0))
            fun p(x: Int, y: Int) = Point(x * factor, y * factor)
            try {
                val color = Scalar(140.0, 200.0, 210.0)
                // Compound outlined frame plus center and dividers, not a filled floor region.
                Imgproc.rectangle(source, p(300, 300), p(324, 316), color, maxOf(1, (2 * factor).toInt()))
                Imgproc.line(source, p(308, 300), p(308, 316), color, maxOf(1, (2 * factor).toInt()))
                Imgproc.circle(source, p(316, 308), (4 * factor).toInt(), color, -1)
                Imgproc.line(source, p(400, 400), p(410, 408), color, maxOf(1, (2 * factor).toInt()))
                val trace = AlignmentTrace(captureArtifacts = true)
                VpsgLiveExtractor.extract(source, trace, visibilityScopedReverse = true).use {
                    val mask = trace.artifactSnapshot().getValue("occlusion-mask.gray8")
                    assertTrue("Chest excluded at extent $size", mask[(308 * factor).toInt() * size + (316 * factor).toInt()].toInt() and 255 > 128)
                    assertEquals("Sparse door tick retained at extent $size", 0,
                        mask[(404 * factor).toInt() * size + (405 * factor).toInt()].toInt() and 255)
                }
            } finally { source.release() }
        }
    }

    @Test fun paleNavigationChainIsOccludedWhileIsolatedDoorTickAndRealWallsRemainEvidence() {
        OpenCvRuntime.requireAvailable()
        val source = Mat.zeros(320, 320, CvType.CV_8UC3)
        val yellow = Scalar(140.0, 200.0, 210.0) // Pale arrows below the numbered-player saturation gate.
        try {
            Imgproc.rectangle(source, Point(30.0, 30.0), Point(290.0, 290.0), Scalar(80.0, 100.0, 160.0), -1)
            for (y in 60..188 step 16) {
                val arrow = MatOfPoint(Point(137.0, y.toDouble()), Point(141.0, y + 3.0),
                    Point(145.0, y.toDouble()), Point(141.0, y + 8.0))
                try { Imgproc.fillPoly(source, listOf(arrow), yellow) } finally { arrow.release() }
            }
            Imgproc.line(source, Point(40.0, 230.0), Point(50.0, 238.0), yellow, 2)
            val trace = AlignmentTrace(captureArtifacts = true)
            VpsgLiveExtractor.extract(source, trace, visibilityScopedReverse = true).use { observation ->
                val evidence = trace.snapshot().single { it.stage == "vpsg.extract.navigation-evidence" }
                assertTrue(evidence.series.getValue("chainXYWHCountExcluded").chunked(6).any { it[4] >= 4 && it[5] == 1.0 })
                val mask = trace.artifactSnapshot().getValue("occlusion-mask.gray8")
                assertTrue("Navigation is occluded", mask[64 * 320 + 141].toInt() and 255 > 128)
                assertEquals("An isolated door tick is not a navigation chain", 0, mask[234 * 320 + 45].toInt() and 255)
                assertEquals("A real wall remains outside the dynamic mask", 0, mask[100 * 320 + 30].toInt() and 255)
                assertTrue("The wall still supports alignment", observation.edges.get(100, 30)[0] > 128)
            }
        } finally { source.release() }
    }
}
