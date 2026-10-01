package com.idvb.android.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.UsageConsent
import org.junit.Assert.*
import org.junit.Test

class OverlayNotificationsInstrumentedTest {
    @Test fun newestStaysAboveLateUpdatesAndOldExpiryCannotRemoveNewCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val consent = base.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val previous = consent.all["accepted_revision"] as? Int
        var notifications: OverlayNotifications? = null
        try {
            // Isolated test fixture; restore the exact consent record in finally.
            assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
            instrumentation.runOnMainSync {
                val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                val context = if (Build.VERSION.SDK_INT >= 30)
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
                val notices = OverlayNotifications(context) { 1280 to 720 }.also { notifications = it }
                val old = notices.show("旧请求", 150L)
                notices.show("新请求", 5_000L)
                notices.update(old, "旧请求晚到的回调", 150L)
                assertEquals(listOf("新请求", "旧请求晚到的回调"), notices.messages())
                notices.setHidden(true); notices.setHidden(false)
            }
            Thread.sleep(300)
            instrumentation.runOnMainSync { assertEquals(listOf("新请求"), notifications!!.messages()) }
        } finally {
            instrumentation.runOnMainSync { notifications?.close() }
            val edit = consent.edit()
            if (previous == null) edit.remove("accepted_revision") else edit.putInt("accepted_revision", previous)
            assertTrue(edit.commit())
        }
    }
}
