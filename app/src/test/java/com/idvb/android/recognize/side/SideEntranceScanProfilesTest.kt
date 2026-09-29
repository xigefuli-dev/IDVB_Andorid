package com.idvb.android.recognize.side

import org.junit.Assert.*
import org.junit.Test

class SideEntranceScanProfilesTest {
    @Test fun gameZoomRangeScalesWithResolutionAndOrientation() {
        for ((w,h) in listOf(800 to 450,1280 to 720,1600 to 900,1920 to 1080,
            2560 to 1440,3840 to 2160,5120 to 2880,3440 to 1440,2560 to 1600,1000 to 1000)) {
            val density = minOf(w,h)/1440.0
            val config = SideEntranceScanProfiles.resolve(w,h)
            for (zoom in listOf(.25,.5,1.0,2.3744,3.5,4.0)) {
                assertTrue("$w x $h zoom=$zoom", zoom*density in config.minimumScale..config.maximumScale)
            }
            val rotated = SideEntranceScanProfiles.resolve(h,w)
            assertEquals(config.minimumScale,rotated.minimumScale,0.0)
            assertEquals(config.maximumScale,rotated.maximumScale,0.0)
        }
    }
}
