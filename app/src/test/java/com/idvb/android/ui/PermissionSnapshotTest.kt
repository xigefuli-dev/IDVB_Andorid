package com.idvb.android.ui

import com.idvb.android.data.ScreenCaptureMethod
import org.junit.Assert.*
import org.junit.Test

class PermissionSnapshotTest {
    @Test fun enabledServiceDoesNotClaimSuccessfulCapture() {
        assertFalse(base.captureVerified)
        assertEquals("尚未实测截图", base.captureStatusText)
        val failed = base.copy(captureHealth = com.idvb.android.recognize.CaptureHealthSnapshot(
            base.captureMethod, com.idvb.android.recognize.CaptureAvailability.UNAVAILABLE,
            lastFailure = com.idvb.android.recognize.CaptureFailure.REJECTED,
            lastFailureReason = "安全窗口禁止截图", lastErrorCode = 6))
        assertFalse(failed.captureVerified)
        assertEquals("开关已开，但实测失败：安全窗口禁止截图", failed.captureStatusText)
        assertTrue(base.copy(captureHealth = base.captureHealth.copy(
            availability = com.idvb.android.recognize.CaptureAvailability.HEALTHY)).captureVerified)
    }

    @Test fun previousSourceSuccessCannotMakeANewSourceGreen() {
        val healthy = base.copy(captureHealth = base.captureHealth.copy(
            availability = com.idvb.android.recognize.CaptureAvailability.HEALTHY))
        assertFalse(healthy.copy(captureMethod = ScreenCaptureMethod.MEDIA_PROJECTION).captureVerified)
        assertFalse(healthy.copy(screenCapture = false).captureVerified)
    }

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
