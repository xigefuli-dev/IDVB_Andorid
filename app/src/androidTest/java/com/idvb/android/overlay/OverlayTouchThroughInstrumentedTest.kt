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
        runCatching { automation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close() }
        runCatching { automation.executeShellCommand("appops set ${instrumentation.context.packageName} SYSTEM_ALERT_WINDOW allow").close() }
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
            }.mapNotNull { it.text?.toString()?.substringAfter("Touches: ")?.substringBefore(" ")?.toIntOrNull() }.first()
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

    @Test fun continuousTouchNotInterruptedDuringAlignmentOperations() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val consent = base.getSharedPreferences("mandatory_usage_consent", android.content.Context.MODE_PRIVATE)
        val previousConsent = consent.all["accepted_revision"] as? Int
        consent.edit().putInt("accepted_revision", com.idvb.android.UsageConsent.REVISION).commit()

        val context = base
        val automation = instrumentation.uiAutomation
        runCatching { automation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close() }
        runCatching { automation.executeShellCommand("appops set ${instrumentation.context.packageName} SYSTEM_ALERT_WINDOW allow").close() }
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
        expectTouches(0)

        fun getStatus(): String {
            val deadline = SystemClock.uptimeMillis() + 1000
            do {
                val text = automation.windows.flatMap {
                    it.root?.findAccessibilityNodeInfosByText("Status:") ?: emptyList()
                }.firstOrNull()?.text?.toString()
                if (text != null) return text
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            return ""
        }

        val metrics = context.resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f

        val downTime = SystemClock.uptimeMillis()
        fun sendTouch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(100)
        }

        // 1. Initial down (simulating holding joystick)
        sendTouch(MotionEvent.ACTION_DOWN, cx, cy)
        val status1 = getStatus()
        Log.i("TouchTest", "After down, status=$status1")
        assertTrue("Expected DOWN but got $status1", status1.contains("Status: DOWN"))

        // 2. Notification shown
        val display = base.createDisplayContext(base.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0))
        val windowContext = if (Build.VERSION.SDK_INT >= 30)
            display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
        val notifications = OverlayNotifications(windowContext) { 1080 to 2400 }
        try {
            instrumentation.runOnMainSync {
                notifications.show("正在准备自动贴合…")
            }
            SystemClock.sleep(200)
            sendTouch(MotionEvent.ACTION_MOVE, cx + 10f, cy)
            val status2 = getStatus()
            Log.i("TouchTest", "After notification show, status=$status2")
            assertTrue("Expected continuous touch but cancelled: $status2", !status2.contains("Status: CANCEL"))

            // 3. Notification updated
            instrumentation.runOnMainSync {
                notifications.update(1L, "正在自动贴合 · 等待计算")
            }
            SystemClock.sleep(200)
            sendTouch(MotionEvent.ACTION_MOVE, cx + 20f, cy)
            val status3 = getStatus()
            Log.i("TouchTest", "After notification update, status=$status3")
            assertTrue("Expected continuous touch but cancelled: $status3", !status3.contains("Status: CANCEL"))

            // 4. Guide window resized to full screen
            val guideWindow = OverlayWindowManager(windowContext)
            val guideView = View(windowContext)
            instrumentation.runOnMainSync {
                guideWindow.width = 100; guideWindow.height = 100
                guideWindow.add(guideView, locked = true)
            }
            SystemClock.sleep(200)
            sendTouch(MotionEvent.ACTION_MOVE, cx + 30f, cy)
            val status4 = getStatus()
            Log.i("TouchTest", "After guideWindow add, status=$status4")
            assertTrue("Expected continuous touch but cancelled: $status4", !status4.contains("Status: CANCEL"))

            instrumentation.runOnMainSync {
                guideWindow.width = 1080; guideWindow.height = 2400
                guideWindow.update()
            }
            SystemClock.sleep(200)
            sendTouch(MotionEvent.ACTION_MOVE, cx + 40f, cy)
            val status5 = getStatus()
            Log.i("TouchTest", "After guideWindow update fullscreen, status=$status5")
            assertTrue("Expected continuous touch but cancelled: $status5", !status5.contains("Status: CANCEL"))

            // 5. Take screenshot
            val screenshot = automation.takeScreenshot()
            Log.i("TouchTest", "Screenshot taken: ${screenshot?.width}x${screenshot?.height}")
            SystemClock.sleep(200)
            sendTouch(MotionEvent.ACTION_MOVE, cx + 50f, cy)
            val statusScreenshot = getStatus()
            Log.i("TouchTest", "After takeScreenshot, status=$statusScreenshot")
            assertTrue("Expected continuous touch after screenshot but got: $statusScreenshot", !statusScreenshot.contains("Status: CANCEL"))

            sendTouch(MotionEvent.ACTION_UP, cx + 40f, cy)
            val status6 = getStatus()
            Log.i("TouchTest", "Final status=$status6")
            assertTrue("Expected UP or CLICKED but got $status6", status6.contains("Status: UP") || status6.contains("Status: CLICKED"))

            instrumentation.runOnMainSync {
                guideWindow.remove()
            }
        } finally {
            instrumentation.runOnMainSync {
                notifications.close()
            }
            val edit = consent.edit()
            if (previousConsent == null) edit.remove("accepted_revision") else edit.putInt("accepted_revision", previousConsent)
            edit.commit()
        }
    }

    @Test fun twoPointersAcrossOverlayAndUnderlyingWindowDoNotCancelEachOther() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val automation = instrumentation.uiAutomation

        runCatching { automation.executeShellCommand("appops set ${base.packageName} SYSTEM_ALERT_WINDOW allow").close() }
        runCatching { automation.executeShellCommand("appops set ${instrumentation.context.packageName} SYSTEM_ALERT_WINDOW allow").close() }

        val display = base.createDisplayContext(base.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0))
        val windowContext = if (Build.VERSION.SDK_INT >= 30)
            display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display

        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        val overlayWindow = OverlayWindowManager(windowContext)
        val overlayView = View(windowContext).apply {
            isClickable = true
            isFocusable = false
        }

        try {
            base.startActivity(Intent().apply {
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
                throw AssertionError("Expected foreign UID to display Touches: $count")
            }
            expectTouches(0)

            fun getStatus(): String {
                val deadline = SystemClock.uptimeMillis() + 1000
                do {
                    val text = automation.windows.flatMap {
                        it.root?.findAccessibilityNodeInfosByText("Status:") ?: emptyList()
                    }.firstOrNull()?.text?.toString()
                    if (text != null) return text
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < deadline)
                return ""
            }

            val metrics = base.resources.displayMetrics
            val cx = metrics.widthPixels / 4f
            val cy = metrics.heightPixels / 2f
            val bx = (metrics.widthPixels * 3 / 4f)
            val by = metrics.heightPixels / 2f

            instrumentation.runOnMainSync {
                overlayWindow.x = (bx - 100).toInt()
                overlayWindow.y = (by - 100).toInt()
                overlayWindow.width = 200
                overlayWindow.height = 200
                overlayWindow.add(overlayView, locked = false)
            }
            SystemClock.sleep(300)

            val downTime = SystemClock.uptimeMillis()
            val pp0 = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val pc0 = MotionEvent.PointerCoords().apply { x = cx; y = cy; pressure = 1f; size = 1f }

            // 1. Pointer 0 DOWN on game (simulating joystick touch)
            val eventDown0 = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                1, arrayOf(pp0), arrayOf(pc0), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventDown0, true)) } finally { eventDown0.recycle() }
            SystemClock.sleep(100)

            val status1 = getStatus()
            Log.i("MultiTouchTest", "After P0 down, status=$status1")
            assertTrue("Expected DOWN but got $status1", status1.contains("Status: DOWN"))

            // 2. Pointer 1 DOWN on overlay window (simulating tapping eye button)
            val pp1 = MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val pc1 = MotionEvent.PointerCoords().apply { x = bx; y = by; pressure = 1f; size = 1f }
            val eventDown1 = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, arrayOf(pp0, pp1), arrayOf(pc0, pc1), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventDown1, true)) } finally { eventDown1.recycle() }
            SystemClock.sleep(100)

            val status2 = getStatus()
            Log.i("MultiTouchTest", "After P1 down on overlay, status=$status2")
            assertTrue("Expected no CANCEL on game after P1 touches overlay, but got $status2", !status2.contains("Status: CANCEL"))

            // 3. Pointer 0 MOVES (character moving) while Pointer 1 is still on overlay
            val pc0Move = MotionEvent.PointerCoords().apply { x = cx + 15f; y = cy; pressure = 1f; size = 1f }
            val eventMove0 = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                2, arrayOf(pp0, pp1), arrayOf(pc0Move, pc1), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventMove0, true)) } finally { eventMove0.recycle() }
            SystemClock.sleep(100)

            val status3 = getStatus()
            Log.i("MultiTouchTest", "After P0 move with P1 down, status=$status3")
            assertTrue("Expected no CANCEL on game during P0 move with P1 down, but got $status3", !status3.contains("Status: CANCEL"))

            // 4. Pointer 1 UP from overlay (tapping finished)
            val eventUp1 = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, arrayOf(pp0, pp1), arrayOf(pc0Move, pc1), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventUp1, true)) } finally { eventUp1.recycle() }
            SystemClock.sleep(100)

            val status4 = getStatus()
            Log.i("MultiTouchTest", "After P1 up from overlay, status=$status4")
            assertTrue("Expected no CANCEL on game after P1 released from overlay, but got $status4", !status4.contains("Status: CANCEL"))

            // 5. Pointer 0 continues moving
            val pc0Move2 = MotionEvent.PointerCoords().apply { x = cx + 30f; y = cy; pressure = 1f; size = 1f }
            val eventMove0Final = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                1, arrayOf(pp0), arrayOf(pc0Move2), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventMove0Final, true)) } finally { eventMove0Final.recycle() }
            SystemClock.sleep(100)

            val status5 = getStatus()
            Log.i("MultiTouchTest", "After P0 move after P1 up, status=$status5")
            assertTrue("Expected no CANCEL on game after P1 up, but got $status5", !status5.contains("Status: CANCEL"))

            // 6. Pointer 0 UP
            val eventUp0 = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                1, arrayOf(pp0), arrayOf(pc0Move2), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { assertTrue(automation.injectInputEvent(eventUp0, true)) } finally { eventUp0.recycle() }
            SystemClock.sleep(100)

            val status6 = getStatus()
            Log.i("MultiTouchTest", "After P0 up, status=$status6")
            assertTrue("Expected UP or CLICKED but got $status6", status6.contains("Status: UP") || status6.contains("Status: CLICKED"))
        } finally {
            instrumentation.runOnMainSync {
                overlayWindow.remove()
            }
        }
    }
}
