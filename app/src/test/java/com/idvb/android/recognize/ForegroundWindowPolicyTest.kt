package com.idvb.android.recognize

import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowPolicyTest {
    @Test fun latestMatchVolumeWindowRetainsGameWithoutRequiringADismissalEvent() {
        var foreground: String? = "com.netease.dwrg"
        val updated = "com.android.systemui"
        val reason = ForegroundWindowPolicy.ignoredWindowReason(updated,
            "com.android.systemui.volume.VolumeDialogImpl\$CustomDialog")
        if (reason == null) foreground = updated
        assertEquals("transient-volume-window", reason)
        assertEquals("com.netease.dwrg", foreground)
    }

    @Test fun volumePanelAndOurOverlayDoNotReplaceUnderlyingApp() {
        assertEquals("transient-volume-window", ForegroundWindowPolicy.ignoredWindowReason(
            "com.android.systemui", "com.android.systemui.volume.VolumePanel"))
        assertEquals("own-overlay", ForegroundWindowPolicy.ignoredWindowReason(
            "com.idvb.android", "com.idvb.android.overlay.CandidateSelectionView"))
    }

    @Test fun recordedColorOsVolumeDialogRetainsTheGameAfterItsPanelCloses() {
        // Original OPPO feedback: app.log 2026-10-03 23:03:05.620.
        val window = "com.oplus.systemui.volume.view.OplusVolumeDialogView\$CustomDialog"
        assertEquals("transient-volume-window", ForegroundWindowPolicy.ignoredWindowReason(
            "com.android.systemui", window))
        assertNull(ForegroundWindowPolicy.ignoredWindowReason("another.app", window))
        assertNull(ForegroundWindowPolicy.ignoredWindowReason("com.android.systemui",
            "com.oplus.systemui.shade.OplusNotificationShadeWindowView"))
    }

    @Test fun notificationShadeLockScreenHomeAndSettingsRemainForegroundChanges() {
        for ((pkg, window) in listOf(
            "com.android.systemui" to "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui" to "com.android.systemui.keyguard.KeyguardView",
            "com.android.launcher3" to "com.android.launcher3.Launcher",
            "com.android.settings" to "com.android.settings.Settings",
            "com.idvb.android" to "com.idvb.android.MainActivity",
            "com.netease.dwrg" to "com.netease.dwrg.Client",
        )) assertNull(ForegroundWindowPolicy.ignoredWindowReason(pkg, window))
        assertNull(ForegroundWindowPolicy.ignoredWindowReason("com.android.systemui", null))
        assertNull(ForegroundWindowPolicy.ignoredWindowReason("another.app", "VolumeDialog"))
    }
}
