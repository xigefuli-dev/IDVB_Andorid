package com.idvb.android.recognize.side

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.GateDetection
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class SideEntranceScanPipelineInstrumentedTest {
    @Test
    fun gateMaskedCoarseAndRefineSearchKeepsCorrectMapAndCoordinates() {
        assertTrue(OpenCvRuntime.initialize())
        val correct = featureA()
        val wrong = featureB()
        val frame = Mat(420, 680, CvType.CV_8UC1, Scalar(40.0))
        try {
            frame.submat(Rect(260, 170, correct.cols(), correct.rows())).useMat { correct.copyTo(it) }
            val viewport = ScreenRect(100.0, 50.0, 680.0, 420.0)
            val gate = GateDetection(
                score = .93,
                scale = .275,
                screenBounds = ScreenRect(403.0, 255.0, 10.0, 10.0),
            )
            val unrelatedGate = GateDetection(
                score = .97,
                scale = .275,
                screenBounds = ScreenRect(175.0, 385.0, 10.0, 10.0),
            )
            val anchor = NormalizedRect(.4875, .4833333333, .025, .0333333334)
            val inputs = listOf(
                input("correct", correct, anchor),
                input("wrong", wrong, anchor),
            )
            val config = SideEntranceScanConfig(
                coarseScaleStep = .10,
                coarsePyramidFactor = 4,
                scanParallelism = 2,
                minimumScale = .80,
                maximumScale = 1.20,
                minimumReferenceSimilarity = .55,
                minimumTemplateMargin = .01,
                maximumGateSpatialResidualPixels = 12.0,
            )
            val results = SideEntranceScanPipeline(config).runScan(
                capturedGrayFrame = frame,
                inputs = inputs,
                detectedGates = listOf(unrelatedGate, gate),
                viewportBounds = viewport,
                topK = 2,
            )

            assertTrue(results.isNotEmpty())
            assertEquals("correct", results.first().map.id)
            assertEquals(SideEntranceGateAssociationKind.DETECTED_GATE, results.first().gateAssociationKind)
            assertEquals(1, results.first().associatedGateIndex)
            assertTrue(results.first().gateSpatialResidualPixels <= 12.0)
            assertTrue(results.first().matchLocation.x in 250.0..270.0)
            assertTrue(results.first().matchLocation.y in 160.0..180.0)
            assertTrue(results.first().matchScale in .90..1.10)
        } finally {
            correct.release()
            wrong.release()
            frame.release()
        }
    }

    @Test
    fun fullFrameRescueIsExplicitWhenNoGateBranchCanAssociate() {
        assertTrue(OpenCvRuntime.initialize())
        val feature = featureA()
        val frame = Mat(420, 680, CvType.CV_8UC1, Scalar(40.0))
        try {
            frame.submat(Rect(70, 60, feature.cols(), feature.rows())).useMat { feature.copyTo(it) }
            val viewport = ScreenRect(0.0, 0.0, 680.0, 420.0)
            val unrelated = GateDetection(.96, .275, ScreenRect(630.0, 370.0, 10.0, 10.0))
            val anchor = NormalizedRect(.4875, .4833333333, .025, .0333333334)
            val results = SideEntranceScanPipeline(testConfig()).runScan(
                frame,
                listOf(input("rescued", feature, anchor)),
                listOf(unrelated),
                viewport,
                topK = 1,
            )

            assertEquals(1, results.size)
            assertEquals(SideEntranceGateAssociationKind.TEMPLATE_ONLY_RESCUE, results[0].gateAssociationKind)
            assertEquals(-1, results[0].associatedGateIndex)
            assertTrue(results[0].gateSpatialResidualPixels.isInfinite())
        } finally {
            feature.release()
            frame.release()
        }
    }

    @Test
    fun gateMaskUsesOneOriginalMeanAtNonZeroViewportOrigin() {
        assertTrue(OpenCvRuntime.initialize())
        val frame = textured(260, 220)
        try {
            val originalMean = Core.mean(frame).`val`[0]
            val viewport = ScreenRect(100.0, 200.0, 260.0, 220.0)
            val gates = listOf(
                GateDetection(.95, 1.0, ScreenRect(120.0, 230.0, 18.0, 16.0)),
                GateDetection(.93, 1.0, ScreenRect(280.0, 330.0, 22.0, 20.0)),
            )
            SideEntranceScanPipeline.maskDetectedGates(frame, gates, viewport)
            frame.submat(Rect(20, 30, 18, 16)).useMat { first ->
                frame.submat(Rect(180, 130, 22, 20)).useMat { second ->
                    val firstPeak = Core.minMaxLoc(first)
                    val secondPeak = Core.minMaxLoc(second)
                    assertEquals(firstPeak.minVal, firstPeak.maxVal, .001)
                    assertEquals(secondPeak.minVal, secondPeak.maxVal, .001)
                    assertEquals(originalMean, firstPeak.maxVal, 1.0)
                    assertEquals(firstPeak.maxVal, secondPeak.maxVal, .001)
                }
            }
        } finally {
            frame.release()
        }
    }

    private fun input(id: String, feature: Mat, anchor: NormalizedRect) = SideEntranceScanInput(
        map = MapRecord(
            id = id,
            classId = "test",
            title = id,
            sourceMapId = id,
            mapVersion = 1,
            floors = listOf(FloorRecord("1f", "1F", 0, "unused.png", 400, 300)),
        ),
        floorKey = "1f",
        featureTemplate = feature,
        featureCenterX = 200.0,
        featureCenterY = 150.0,
        recognitionWidth = 400,
        recognitionHeight = 300,
        sideEntranceBounds = anchor,
    )

    private fun featureA(): Mat = Mat(80, 96, CvType.CV_8UC1, Scalar(40.0)).also { mat ->
        mat.submat(Rect(8, 8, 30, 12)).useMat { it.setTo(Scalar(210.0)) }
        mat.submat(Rect(14, 25, 12, 40)).useMat { it.setTo(Scalar(130.0)) }
        mat.submat(Rect(62, 12, 20, 50)).useMat { it.setTo(Scalar(235.0)) }
        mat.submat(Rect(43, 35, 10, 10)).useMat { it.setTo(Scalar(40.0)) }
    }

    private fun featureB(): Mat = Mat(80, 96, CvType.CV_8UC1, Scalar(40.0)).also { mat ->
        mat.submat(Rect(10, 12, 70, 8)).useMat { it.setTo(Scalar(180.0)) }
        mat.submat(Rect(38, 20, 12, 48)).useMat { it.setTo(Scalar(220.0)) }
        mat.submat(Rect(12, 58, 65, 10)).useMat { it.setTo(Scalar(110.0)) }
    }

    private fun textured(width: Int, height: Int): Mat {
        val pixels = ByteArray(width * height) { index -> ((index * 37 + index / width * 19) and 0xff).toByte() }
        return Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, pixels) }
    }

    private fun testConfig() = SideEntranceScanConfig(
        coarseScaleStep = .10,
        coarsePyramidFactor = 4,
        scanParallelism = 2,
        minimumScale = .80,
        maximumScale = 1.20,
        minimumReferenceSimilarity = .55,
        minimumTemplateMargin = .01,
        maximumGateSpatialResidualPixels = 12.0,
    )
}

private inline fun <T> Mat.useMat(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
