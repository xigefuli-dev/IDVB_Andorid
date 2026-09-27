package com.idvb.android.overlay

import android.content.ComponentName
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTouchThroughInstrumentedTest {
    @Test fun foreignUidReceivesTouchesUnderThreeOverlappingWindows() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, TouchTargetActivity::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        fun expectTouches(count: Int) {
            val deadline = SystemClock.uptimeMillis() + 3000
            do {
                if (automation.windows.any {
                    it.root?.findAccessibilityNodeInfosByText("Touches: $count")?.isNotEmpty() == true
                }) return
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            val roots = automation.windows.map { it.root?.toString() }
            throw AssertionError("Expected foreign UID to display Touches: $count; roots=$roots")
        }
        fun tap() {
            val metrics = context.resources.displayMetrics
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    metrics.widthPixels / 2f, metrics.heightPixels / 2f, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            SystemClock.sleep(300)
        }
        expectTouches(0)
        tap(); expectTouches(1)
        val wm = context.getSystemService(WindowManager::class.java)
        val baseline = View(context).apply { setBackgroundColor(Color.GREEN); alpha = .46f }
        val managers = mutableListOf<OverlayWindowManager>()
        var baselineAdded = false
        try {
            // Original defect: translucent pixels but default opaque window alpha.
            instrumentation.runOnMainSync {
                wm.addView(baseline, WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT))
                baselineAdded = true
            }
            SystemClock.sleep(300)
            tap()
            val baselineCount = automation.windows.flatMap {
                it.root?.findAccessibilityNodeInfosByText("Touches:").orEmpty()
            }.mapNotNull { it.text?.toString()?.substringAfter("Touches: ")?.toIntOrNull() }.first()
            assertTrue(baselineCount == 1 || baselineCount == 2)
            Log.i("OverlayTouchRegression", "Old opaque-window baseline touch count=$baselineCount (1=blocked, 2=not enforced by this device/injection path)")
            instrumentation.runOnMainSync { wm.removeViewImmediate(baseline); baselineAdded = false }
            instrumentation.runOnMainSync {
                repeat(3) {
                    managers += OverlayWindowManager(context).apply {
                        width = -1; height = -1
                        val layer = View(context).apply { setBackgroundColor(Color.GREEN) }
                        add(layer, locked = true)
                        val params = layer.layoutParams as WindowManager.LayoutParams
                        assertTrue(params.alpha < .8f)
                        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                    }
                }
            }
            SystemClock.sleep(300)
            tap(); expectTouches(baselineCount + 1)
            instrumentation.runOnMainSync {
                managers[0].opacity = .46f
                managers[1].update(locked = false)
            }
            SystemClock.sleep(300)
            tap(); expectTouches(baselineCount + 1) // Interactive editing must still intercept.
            instrumentation.runOnMainSync { managers[1].update(locked = true) }
            SystemClock.sleep(300)
            tap(); expectTouches(baselineCount + 2)
            instrumentation.runOnMainSync { managers[2].remove(); managers[0].update() }
            SystemClock.sleep(300)
            tap(); expectTouches(baselineCount + 3)
        } finally {
            instrumentation.runOnMainSync {
                if (baselineAdded) wm.removeViewImmediate(baseline)
                managers.forEach { it.remove() }
            }
        }
    }
}
