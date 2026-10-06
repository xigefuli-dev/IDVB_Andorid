package com.idvb.android.recognize.gate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class GateTemplateDetectorInstrumentedTest {
    @Test fun requiredAssetLoadsAndBlankFrameContainsNoGate() {
        assertTrue(OpenCvRuntime.initialize())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frame = Mat(200, 320, CvType.CV_8UC1, Scalar(40.0))
        try {
            GateTemplateDetector.fromAssets(context).use { detector ->
                val result = detector.detect(frame, ScreenRect(0.0, 0.0, 320.0, 200.0))
                assertTrue("No gates is a valid observation with a healthy template", result.gates.isEmpty())
            }
        } finally { frame.release() }
    }

    @Test fun corruptOrEmptyTemplateIsAnAssetErrorRatherThanNoGates() {
        assertTrue(OpenCvRuntime.initialize())
        for (bytes in listOf(byteArrayOf(), "not a PNG".toByteArray())) {
            val result = runCatching { GateTemplateImages.decodeAsset(bytes) }
            result.getOrNull()?.release()
            assertTrue("Damaged templates must fail explicitly", result.isFailure)
            assertTrue(checkNotNull(result.exceptionOrNull()?.message).contains(GateTemplateImages.ASSET_PATH))
        }
    }

    @Test
    fun desktopAssetFindsTwoDimmedCopiesAtColdStartScale() {
        assertTrue(OpenCvRuntime.initialize())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = GateTemplateImages.loadAsset(context)
        val gateBgr = Mat()
        val scaled = Mat()
        val dimmed = Mat()
        val frameBgr = Mat(1066, 1706, CvType.CV_8UC3, Scalar(35.0, 42.0, 48.0))
        try {
            assertEquals(100, gate.cols())
            assertEquals(101, gate.rows())
            Imgproc.cvtColor(gate, gateBgr, Imgproc.COLOR_BGRA2BGR)
            Imgproc.resize(gateBgr, scaled, Size(28.0, 28.0), 0.0, 0.0, Imgproc.INTER_AREA)
            scaled.convertTo(dimmed, CvType.CV_8UC3, .82, 12.0)
            Imgproc.GaussianBlur(dimmed, dimmed, Size(3.0, 3.0), 0.0)
            frameBgr.submat(Rect(280, 230, 28, 28)).useMat { dimmed.copyTo(it) }
            frameBgr.submat(Rect(1120, 720, 28, 28)).useMat { dimmed.copyTo(it) }
            val frameGray = GateTemplateImages.createMatchImage(frameBgr)
            try {
                GateTemplateDetector.fromAssets(context).use { detector ->
                    val result = detector.detect(frameGray, ScreenRect(0.0, 0.0, 1706.0, 1066.0))
                    assertTrue(result.gates.size >= 2)
                    assertTrue(result.rawCandidates.size >= result.gates.size)
                }
            } finally {
                frameGray.release()
            }
        } finally {
            gate.release()
            gateBgr.release()
            scaled.release()
            dimmed.release()
            frameBgr.release()
        }
    }

    @Test
    fun lockedScaleFindsTwoDistinctTemplateCopies() {
        assertTrue(OpenCvRuntime.initialize())
        val template = Mat.zeros(24, 24, CvType.CV_8UC1)
        val frame = Mat.zeros(120, 220, CvType.CV_8UC1)
        try {
            template.submat(Rect(3, 3, 18, 5)).useMat { it.setTo(Scalar(210.0)) }
            template.submat(Rect(3, 8, 5, 13)).useMat { it.setTo(Scalar(150.0)) }
            template.submat(Rect(12, 10, 9, 11)).useMat { it.setTo(Scalar(245.0)) }
            frame.submat(Rect(30, 20, 24, 24)).useMat { template.copyTo(it) }
            frame.submat(Rect(150, 70, 24, 24)).useMat { template.copyTo(it) }

            GateTemplateDetector.fromSource(template).use { detector ->
                val result = detector.detect(
                    frame,
                    ScreenRect(0.0, 0.0, 220.0, 120.0),
                    scoreThreshold = .9,
                    searchContext = GateSearchContext(mode = GateSearchMode.LOCKED_SCALE, lockedScale = 1.0),
                )
                assertEquals(2, result.gates.size)
                assertTrue(result.gates.all { it.score > .99 })
            }
        } finally {
            template.release()
            frame.release()
        }
    }
}

private inline fun <T> Mat.useMat(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
