package com.idvb.android.overlay

import android.os.Build
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class OverlayMenuPositionInstrumentedTest {
    @Test fun draggingThenTogglingMenuKeepsControlsAtTheirCurrentPositionOnEveryFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val context = if (Build.VERSION.SDK_INT >= 30) {
            base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else base
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val controls = OverlayWindowManager(context)
        lateinit var balls: OverlayBallView
        lateinit var menu: OverlayBallMenuWindow
        var menuForCleanup: OverlayBallMenuWindow? = null
        lateinit var more: View
        fun position(): Pair<Int, Int> {
            var result = 0 to 0
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                more.getLocationOnScreen(location)
                result = location[0] to location[1]
            }
            return result
        }
        fun inject(action: Int, x: Float, y: Float, downTime: Long) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        fun tap(view: View) {
            val location = IntArray(2)
            var x = 0f; var y = 0f
            instrumentation.runOnMainSync {
                view.getLocationOnScreen(location)
                x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
            }
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x, y, down)
            inject(MotionEvent.ACTION_UP, x, y, down)
        }
        fun drag(dx: Float, dy: Float) {
            val (left, top) = position()
            val x = left + more.width / 2f; val y = top + more.height / 2f
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, x, y, down)
            for (step in 1..12) {
                inject(MotionEvent.ACTION_MOVE, x + dx * step / 12, y + dy * step / 12, down)
                SystemClock.sleep(16)
            }
            inject(MotionEvent.ACTION_UP, x + dx, y + dy, down)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
        }
        fun toggleAndCheck(expectedMenuVisible: Boolean) {
            val anchor = position()
            val originalBounds = listOf(controls.x, controls.y, controls.width, controls.height)
            val frames = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
            var observing = true
            val observer = object : Runnable {
                override fun run() {
                    if (!observing) return
                    val location = IntArray(2)
                    more.getLocationOnScreen(location)
                    frames.add(location[0] to location[1])
                    more.postOnAnimation(this)
                }
            }
            instrumentation.runOnMainSync { more.postOnAnimation(observer) }
            try {
                tap(more)
                SystemClock.sleep(350)
            } finally {
                instrumentation.runOnMainSync { observing = false; more.removeCallbacks(observer) }
            }
            assertTrue("Frame observer did not run", frames.isNotEmpty())
            frames.forEach { assertEquals("Control jumped during menu toggle", anchor, it) }
            assertEquals(anchor, position())
            assertEquals(originalBounds, listOf(controls.x, controls.y, controls.width, controls.height))
            assertEquals(expectedMenuVisible, balls.morePanel.isAttachedToWindow)
            if (expectedMenuVisible) instrumentation.runOnMainSync {
                val location = IntArray(2)
                balls.morePanel.getLocationOnScreen(location)
                assertTrue(location[1] + balls.morePanel.height <= anchor.second ||
                    location[1] >= anchor.second + more.height)
            }
        }
        try {
            instrumentation.runOnMainSync {
                controls.width = (212 * metrics.density).toInt()
                controls.height = (48 * metrics.density).toInt()
                controls.x = (screenWidth - controls.width).coerceAtLeast(0)
                controls.y = (screenHeight * .28f).toInt()
                balls = OverlayBallView(context)
                more = (balls.getChildAt(0) as LinearLayout).getChildAt(3)
                menu = OverlayBallMenuWindow(context, controls, balls.morePanel) { screenWidth to screenHeight }
                menuForCleanup = menu
                balls.listener = object : OverlayBallView.Listener {
                    override fun onSearch() {}
                    override fun onToggleGuide() {}
                    override fun onNextFloor() {}
                    override fun onNextVariant() {}
                    override fun onFreeAdjust() {}
                    override fun onCalibrate() {}
                    override fun onClose() {}
                    override fun onMenuExpanded(expanded: Boolean) {
                        if (expanded) menu.show() else menu.hide()
                    }
                    override fun onMove(dx: Float, dy: Float) {
                        controls.x = (controls.x + dx.toInt()).coerceIn(0, (screenWidth - controls.width).coerceAtLeast(0))
                        controls.y = (controls.y + dy.toInt()).coerceIn(0, (screenHeight - controls.height).coerceAtLeast(0))
                        controls.update()
                        menu.updatePosition()
                    }
                }
                controls.add(balls, locked = false)
            }
            SystemClock.sleep(250)
            val initial = position()
            drag(-40 * metrics.density, screenHeight * .1f)
            assertTrue("Drag did not move controls", initial != position())
            repeat(3) { toggleAndCheck(true); toggleAndCheck(false) }
            // Bottom edge opens upward; dragging an open menu must still use row bounds.
            drag(0f, screenHeight - position().second - more.height - 24 * metrics.density)
            toggleAndCheck(true)
            drag(0f, -screenHeight * .2f)
            toggleAndCheck(false)
            toggleAndCheck(true)
            tap((balls.morePanel as LinearLayout).getChildAt(0))
            instrumentation.waitForIdleSync()
            assertFalse(balls.morePanel.isAttachedToWindow)
            toggleAndCheck(true)
            instrumentation.runOnMainSync { balls.visibility = View.INVISIBLE }
            assertFalse(balls.morePanel.isAttachedToWindow)
            instrumentation.runOnMainSync { balls.visibility = View.VISIBLE }
            toggleAndCheck(true)
            toggleAndCheck(false)
        } finally {
            instrumentation.runOnMainSync {
                menuForCleanup?.hide()
                controls.remove()
            }
        }
    }
}
