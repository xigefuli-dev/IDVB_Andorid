package com.idvb.android.recognize.side

import org.junit.Assert.assertEquals
import org.junit.Test

class SideEntranceScanProfilesTest {
    @Test
    fun exactProfilesPreserveDesktopScaleFloors() {
        assertEquals(.32, SideEntranceScanProfiles.resolve(1600, 900).minimumScale, 0.0)
        assertEquals(.40, SideEntranceScanProfiles.resolve(1920, 1080).minimumScale, 0.0)
        assertEquals(.45, SideEntranceScanProfiles.resolve(2560, 1440).minimumScale, 0.0)
        assertEquals(.50, SideEntranceScanProfiles.resolve(2560, 1600).minimumScale, 0.0)
    }

    @Test
    fun fuzzyMatchUsesDesktopOneHundredPixelTolerance() {
        val config = SideEntranceScanProfiles.resolve(2500, 1400)
        assertEquals(.45, config.minimumScale, 0.0)
        assertEquals(4, config.scanParallelism)
    }

    @Test
    fun aspectMatchUsesDesktopProfileOrder() {
        val config = SideEntranceScanProfiles.resolve(1280, 720)
        assertEquals(.40, config.minimumScale, 0.0)
        assertEquals(.05, config.coarseScaleStep, 0.0)
    }

    @Test
    fun unsupportedAspectFallsBackToDefaultRules() {
        val config = SideEntranceScanProfiles.resolve(1000, 1000)
        assertEquals(.55, config.minimumScale, 0.0)
        assertEquals(2.2, config.maximumScale, 0.0)
    }
}
