package com.idvb.android.ui

import com.idvb.android.data.ScreenCaptureMethod
import org.junit.Assert.*
import org.junit.Test

class PermissionSnapshotTest {
    private val base = PermissionSnapshot(true, true, true, true, true, false,
        ScreenCaptureMethod.ACCESSIBILITY)

    @Test fun batterySkipDoesNotClaimSystemPermission() {
        assertFalse(base.readyToStart)
        val skipped = base.copy(batteryOptimizationSkipped = true)
        assertTrue(skipped.readyToStart)
        assertFalse(skipped.allGranted)
        assertFalse(skipped.batteryOptimization)
        assertFalse(skipped.copy(batteryOptimizationSkipped = false).readyToStart)
    }

    @Test fun skipNeverBypassesRequiredPermissions() {
        val skipped = base.copy(batteryOptimizationSkipped = true)
        listOf(skipped.copy(overlay = false), skipped.copy(screenCapture = false),
            skipped.copy(notifications = false), skipped.copy(foregroundService = false),
            skipped.copy(mediaProjectionService = false)).forEach {
            assertFalse(it.readyToStart)
            assertFalse(it.readyWithoutBattery)
        }
    }

    @Test fun projectionStillNeedsFreshCaptureGrant() {
        val projection = base.copy(captureMethod = ScreenCaptureMethod.MEDIA_PROJECTION,
            screenCapture = false)
        assertTrue(projection.readyWithoutBattery)
        assertFalse(projection.readyToStart)
        assertTrue(projection.copy(batteryOptimizationSkipped = true).readyToStart)
        assertFalse(projection.copy(batteryOptimizationSkipped = true).allGranted)
    }
}
