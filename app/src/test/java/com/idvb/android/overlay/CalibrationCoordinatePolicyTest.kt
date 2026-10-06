package com.idvb.android.overlay

import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.*
import org.junit.Test

class CalibrationCoordinatePolicyTest {
    @Test fun zeroOriginCalibrationRetainsScreenCoordinates() {
        val region = ScreenRect(120.5, 80.25, 1600.0, 800.0)
        assertEquals(region, CalibrationCoordinatePolicy.screenRegion(region, 0, 0, 2400, 1080, 2400, 1080))
    }

    @Test fun offsetWindowUsesActualScreenOriginAndFullDisplayRatios() {
        // Both landscape directions, with a cutout/letterboxed window origin.
        for (origin in listOf(120 to 72, 121 to 72, 0 to 0)) {
            val region = CalibrationCoordinatePolicy.screenRegion(ScreenRect(200.0, 100.0, 1700.0, 700.0),
                origin.first, origin.second, 2200, 936, 2414, 1080)
            assertEquals(origin.first + 200.0, region.x, 0.0)
            assertEquals(origin.second + 100.0, region.y, 0.0)
            assertEquals(1700.0, region.width, 0.0)
            assertEquals((origin.first + 1900.0) / 2414, (region.x + region.width) / 2414, 0.0)
        }
    }

    @Test fun offscreenWindowClipsOnlyToThePhysicalDisplay() {
        assertEquals(ScreenRect(0.0, 0.0, 180.0, 280.0), CalibrationCoordinatePolicy.screenRegion(
            ScreenRect(0.0, 0.0, 200.0, 300.0), -20, -20, 200, 300, 2400, 1080))
    }

    @Test fun invalidOrStaleSelectionCannotReplaceCalibration() {
        for (region in listOf(ScreenRect(0.0, 0.0, 2300.0, 500.0), ScreenRect(-1.0, 0.0, 100.0, 100.0),
            ScreenRect(Double.NaN, 0.0, 100.0, 100.0), ScreenRect(0.0, 0.0, 0.0, 100.0))) {
            try {
                CalibrationCoordinatePolicy.screenRegion(region, 0, 0, 2200, 936, 2414, 1080)
                fail("Invalid view-local selection must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
