package com.idvb.android.recognize.structure

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class MapStructureRegistrarInstrumentedTest {
    @Before
    fun initializeOpenCv() {
        assertTrue(OpenCvRuntime.initialize())
    }

    @Test
    fun repeatedFullMapStructureIsRejectedAsAmbiguous() {
        val referenceMask = Mat.zeros(160, 240, CvType.CV_8UC1)
        val liveMask = Mat.zeros(100, 100, CvType.CV_8UC1)
        try {
            drawPattern(referenceMask, 50, 45, 50)
            drawPattern(referenceMask, 120, 45, 50)
            drawPattern(liveMask, 25, 25, 50)
            features(referenceMask).use { reference ->
                features(liveMask).use { live ->
                    val result = MapStructureRegistrar().register(
                        reference = reference,
                        live = live,
                        viewportBounds = ScreenRect(31.0, 19.0, 100.0, 100.0),
                        seedScale = 1.0,
                        seedOffsetX = 6.0,
                        seedOffsetY = -1.0,
                        validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    )
                    assertFalse(result.accepted)
                    assertEquals(StructureRejectionReason.AMBIGUOUS_CANDIDATES, result.rejectionReason)
                    assertTrue(result.candidateMargin < .04)
                    assertTrue(result.candidates.size >= 2)
                }
            }
        } finally {
            referenceMask.release()
            liveMask.release()
        }
    }

    @Test
    fun scaleOutsideStrictSeedWindowCannotPromote() {
        val referenceMask = Mat.zeros(220, 260, CvType.CV_8UC1)
        val liveMask = Mat.zeros(180, 180, CvType.CV_8UC1)
        try {
            drawPattern(referenceMask, 80, 70, 54)
            // Same topology at a 1.48x size. The strict side-seed window is
            // only +/-2%, so identity must not be accepted at scale 1.0.
            drawPattern(liveMask, 40, 35, 80)
            features(referenceMask).use { reference ->
                features(liveMask).use { live ->
                    val result = MapStructureRegistrar().register(
                        reference = reference,
                        live = live,
                        viewportBounds = ScreenRect(0.0, 0.0, 180.0, 180.0),
                        seedScale = 1.0,
                        seedOffsetX = -40.0,
                        seedOffsetY = -35.0,
                        validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    )
                    assertFalse(result.accepted)
                    assertTrue(
                        result.rejectionReason == StructureRejectionReason.WEAK_ABSOLUTE_SCORE ||
                            result.rejectionReason == StructureRejectionReason.OUTSIDE_VALID_BOUNDS ||
                            result.rejectionReason == StructureRejectionReason.INCONSISTENT_STRUCTURE,
                    )
                }
            }
        } finally {
            referenceMask.release()
            liveMask.release()
        }
    }

    private fun drawPattern(target: Mat, x: Int, y: Int, extent: Int) {
        Imgproc.rectangle(target, Rect(x, y, extent, extent), Scalar.all(255.0), -1)
        val hole = (extent * .30).toInt().coerceAtLeast(8)
        Imgproc.rectangle(
            target,
            Rect(x + extent / 2 - hole / 2, y + extent / 2 - hole / 2, hole, hole),
            Scalar.all(0.0),
            -1,
        )
        Imgproc.rectangle(
            target,
            Rect(x + extent / 5, y, maxOf(5, extent / 7), extent / 3),
            Scalar.all(0.0),
            -1,
        )
    }

    private fun features(mask: Mat): StructureFeatures {
        val structure = mask.clone()
        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Imgproc.morphologyEx(structure, edges, Imgproc.MORPH_GRADIENT, kernel)
        } finally {
            kernel.release()
        }
        return StructureFeatures(structure, edges)
    }
}
