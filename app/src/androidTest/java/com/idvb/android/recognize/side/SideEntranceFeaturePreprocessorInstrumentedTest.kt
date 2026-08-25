package com.idvb.android.recognize.side

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class SideEntranceFeaturePreprocessorInstrumentedTest {
    @Test
    fun ratioCropKeepsUnclampedAnchorCenterAndPadsImageEdge() {
        assertTrue(OpenCvRuntime.initialize())
        val image = texture(1000, 500)
        try {
            val result = SideEntranceFeaturePreprocessor.process(
                image,
                NormalizedRect(.01, .42, .04, .08),
                featureRegionRatio = .12,
                clampToBounds = false,
            )
            try {
                assertEquals(120, result.width)
                assertEquals(60, result.height)
                assertEquals(30.0, result.centerX, 1e-9)
                assertEquals(230.0, result.centerY, 1e-9)
                assertEquals(120, result.feature.cols())
                assertEquals(60, result.feature.rows())
            } finally {
                result.feature.release()
            }
        } finally {
            image.release()
        }
    }

    @Test
    fun sharedGateGlyphIsReplacedByUniformFeatureMean() {
        assertTrue(OpenCvRuntime.initialize())
        val image = texture(240, 240)
        try {
            Imgproc.rectangle(image, Rect(108, 108, 24, 24), Scalar(255.0), -1)
            val result = SideEntranceFeaturePreprocessor.process(
                image,
                NormalizedRect(.45, .45, .10, .10),
                featureRegionRatio = .5,
                clampToBounds = false,
            )
            try {
                result.feature.submat(Rect(48, 48, 24, 24)).useFeatureMat { icon ->
                    val peak = Core.minMaxLoc(icon)
                    assertEquals(peak.minVal, peak.maxVal, .001)
                    assertTrue(peak.maxVal < 250.0)
                }
                assertEquals("3-ratio-gate-masked", SideEntranceFeaturePreprocessor.ALGORITHM_VERSION)
            } finally {
                result.feature.release()
            }
        } finally {
            image.release()
        }
    }

    private fun texture(width: Int, height: Int): Mat {
        val pixels = ByteArray(width * height) { index -> ((index * 29 + index / width * 11) and 0xff).toByte() }
        return Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, pixels) }
    }
}

private inline fun <T> Mat.useFeatureMat(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
