package com.idvb.android.overlay

import org.junit.Assert.*
import org.junit.Test

class ScanProgressPlacementTest {
    @Test fun xiaomiOriginalFeedbackCoordinatesKeepFinishedBarOutOfSidebar() {
        for (right in listOf(2188.87, 2202.37)) {
            val bounds = requireNotNull(ScanProgressPlacement.place(1125.24, 176.5, right, 1016.62,
                2670, 1200, 1105, 182, kotlin.math.floor(right)))
            assertTrue(bounds.x >= 1126)
            assertTrue(bounds.x + bounds.width <= kotlin.math.floor(right))
            assertTrue(bounds.y >= 177 && bounds.y + bounds.height <= 1016)
        }
    }

    @Test fun fullScreenCalibrationReservesSidebarAndSmallCropsShrinkTheBar() {
        val full = requireNotNull(ScanProgressPlacement.place(0.0, 0.0, 2670.0, 1200.0,
            2670, 1200, 1105, 182, 2150.0))
        assertTrue(full.x + full.width <= 2150)
        assertEquals(ScanProgressPlacement.Bounds(11, 21, 89, 29),
            ScanProgressPlacement.place(10.2, 20.2, 100.0, 50.0, 200, 100, 300, 50))
        assertNull(ScanProgressPlacement.place(100.0, 20.0, 120.0, 50.0, 200, 100, 100, 20, 90.0))
    }
}
