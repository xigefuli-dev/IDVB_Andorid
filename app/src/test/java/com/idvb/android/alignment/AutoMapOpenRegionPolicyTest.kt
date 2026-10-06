package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class AutoMapOpenRegionPolicyTest {
    @Test fun screenEdgeAndOverwideCalibrationRetainTheEntireSidebarSearchDomain() {
        for ((width, height) in listOf(2400 to 1080, 1920 to 1080, 2560 to 1600)) {
            val aspect = 149.0 / 1024
            for (calibration in listOf(.96f, 1f)) {
                val left = AutoMapOpenRegionPolicy.leftRatio(width, height, calibration, aspect)
                assertTrue(left < calibration)
                val capturedWidth = width - kotlin.math.floor(left.toDouble() * width)
                assertTrue(capturedWidth >= aspect * height * 1.15)
            }
        }
    }

    @Test fun existingWideSearchRegionsKeepTheirRecordedGeometry() {
        assertEquals(.75f, AutoMapOpenRegionPolicy.leftRatio(2400, 1080, .75f, .15), 0f)
    }

    @Test fun invalidRegionsCannotSilentlyProduceUsableEvidence() {
        for (calibration in listOf(-.1f, 1.1f, Float.NaN)) {
            try {
                AutoMapOpenRegionPolicy.leftRatio(2400, 1080, calibration, .15)
                fail("Invalid calibration must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
