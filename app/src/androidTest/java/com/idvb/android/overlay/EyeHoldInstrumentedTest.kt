package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.OverlayPrefs
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EyeHoldInstrumentedTest {
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val base = instrumentation.targetContext
        val storage = base.getSharedPreferences("eye-hold-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = storage
        }
        val prefs = OverlayPrefs(context)
        val balls = OverlayBallView(context)
        var opens = 0
        var closes = 0
        var toggles = 0
        var moves = 0
        var searches = 0
        var assist = false
        init {
            runCatching { instrumentation.uiAutomation.executeShellCommand("appops set ${base.packageName} SYSTEM_ALERT_WINDOW allow").close() }
            runCatching { instrumentation.uiAutomation.executeShellCommand("appops set ${instrumentation.context.packageName} SYSTEM_ALERT_WINDOW allow").close() }
            balls.mapLocked = true
            balls.listener = object : OverlayBallView.Listener {
                override fun useAssistTouchToggle() = assist
                override fun onOpenGuide() { opens++ }
                override fun onCloseGuide() { closes++ }
                override fun onToggleGuide() { toggles++ }
                override fun onSearch() { searches++ }
                override fun onNextFloor() {}
                override fun onNextVariant() {}
                override fun onFreeAdjust() {}
                override fun onCalibrate() {}
                override fun onClose() {}
                override fun onMove(dx: Float, dy: Float) { moves++ }
                override fun onMenuExpanded(expanded: Boolean) {}
            }
        }
        fun event(action: Int, x: Float = 10f, id: String = "eye") {
            MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, 10f, 0).also {
                balls.buttons.getValue(id).dispatchTouchEvent(it)
                it.recycle()
            }
        }
        fun dispose() { storage.edit().clear().commit() }
        fun attach(): OverlayWindowManager {
            val display = context.createDisplayContext(context.getSystemService(DisplayManager::class.java).getDisplay(0))
            val windowContext = if (Build.VERSION.SDK_INT >= 30)
                display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
            return OverlayWindowManager(windowContext).apply { width = 400; height = 100; add(balls, locked = false) }
        }
    }

    @Test fun configuredAssistUsesCompletedTapInsteadOfInjectingDuringHold() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            try {
                f.assist = true
                f.event(MotionEvent.ACTION_DOWN)
                assertEquals(0, f.opens); assertEquals(0, f.toggles)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(1, f.toggles); assertEquals(0, f.closes)
                f.event(MotionEvent.ACTION_DOWN); f.event(MotionEvent.ACTION_CANCEL)
                assertEquals(1, f.toggles)
            } finally { f.dispose() }
        }
    }

    @Test fun defaultHoldOpensOnDownAndClosesOnUpWithoutClickOrDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            try {
                assertTrue(f.prefs.holdToActivateEnabled)
                f.event(MotionEvent.ACTION_DOWN)
                assertEquals(1, f.opens)
                assertEquals(0, f.closes)
                f.event(MotionEvent.ACTION_MOVE, 200f)
                assertEquals(0, f.moves)
                f.event(MotionEvent.ACTION_UP, 200f)
                assertEquals(1, f.closes)
                assertEquals(0, f.toggles)
            } finally { f.dispose() }
        }
    }

    @Test fun cancelAndHiddenWindowReleaseOnceAndSettingChangesDoNotLatchDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var f: Fixture
        lateinit var anchor: OverlayWindowManager
        instrumentation.runOnMainSync {
            f = Fixture()
            anchor = f.attach()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            try {
                assertTrue(f.balls.isAttachedToWindow)
                f.event(MotionEvent.ACTION_DOWN)
                f.event(MotionEvent.ACTION_CANCEL)
                f.event(MotionEvent.ACTION_CANCEL)
                assertEquals(1, f.closes)
                f.event(MotionEvent.ACTION_DOWN)
                f.balls.visibility = View.INVISIBLE
                assertEquals(2, f.closes)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(2, f.closes)
                f.balls.visibility = View.VISIBLE
                f.event(MotionEvent.ACTION_DOWN)
                f.prefs.holdToActivateEnabled = false
                f.event(MotionEvent.ACTION_UP)
                assertEquals(3, f.closes)
                assertEquals(0, f.toggles)
                assertFalse(OverlayPrefs(f.context).holdToActivateEnabled)
                f.event(MotionEvent.ACTION_DOWN)
                assertEquals(3, f.opens)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(1, f.toggles)
                f.event(MotionEvent.ACTION_DOWN)
                f.event(MotionEvent.ACTION_MOVE, 200f)
                f.event(MotionEvent.ACTION_UP, 200f)
                assertEquals(1, f.toggles)
                assertEquals(1, f.moves)
                f.prefs.holdToActivateEnabled = true
                f.event(MotionEvent.ACTION_DOWN)
                anchor.remove()
                assertEquals(4, f.closes)
            } finally { anchor.remove(); f.dispose() }
        }
    }

    @Test fun unavailableEyeDoesNothingAndPendingCandidatesUseOpenEntry() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            try {
                f.balls.mapLocked = false
                f.event(MotionEvent.ACTION_DOWN)
                f.balls.mapLocked = true
                f.event(MotionEvent.ACTION_UP)
                assertEquals(0, f.opens)
                assertEquals(0, f.closes)
                assertEquals(0, f.toggles)
                f.balls.mapLocked = false
                f.balls.candidatesAvailable = true
                f.event(MotionEvent.ACTION_DOWN)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(1, f.opens)
                assertEquals(1, f.closes)
            } finally { f.dispose() }
        }
    }

    @Test fun longPressDoesNotEnterLayoutAndMenuEntryStillDoes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var f: Fixture
        lateinit var anchor: OverlayWindowManager
        lateinit var layout: OverlayButtonLayout
        instrumentation.runOnMainSync {
            f = Fixture()
            val display = f.context.createDisplayContext(f.context.getSystemService(DisplayManager::class.java).getDisplay(0))
            val context = if (Build.VERSION.SDK_INT >= 30)
                display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
            anchor = OverlayWindowManager(context).apply { width = 400; height = 100; add(f.balls, locked = false) }
            layout = OverlayButtonLayout(context, f.balls, anchor) { 1080 to 1920 }
            f.event(MotionEvent.ACTION_DOWN, id = "search")
        }
        try {
            SystemClock.sleep(2200)
            instrumentation.runOnMainSync {
                assertFalse(layout.editing)
                f.event(MotionEvent.ACTION_UP, id = "search")
                assertEquals(1, f.searches)
                f.event(MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(2200)
            instrumentation.runOnMainSync {
                assertFalse(layout.editing)
                assertEquals(1, f.opens)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(1, f.closes)
                val layoutItem = (0 until f.balls.morePanel.childCount)
                    .map { f.balls.morePanel.getChildAt(it) as android.widget.TextView }
                    .first { it.text.contains("悬浮窗布局调整") }
                layoutItem.performClick()
                assertTrue(layout.editing)
                val before = f.opens
                f.event(MotionEvent.ACTION_DOWN)
                f.event(MotionEvent.ACTION_UP)
                assertEquals(before, f.opens)
            }
        } finally {
            instrumentation.runOnMainSync { layout.dispose(); anchor.remove(); f.dispose() }
        }
    }

    @Test fun overlayWindowManagerDeclaresSplitTouchAndNonModalFlags() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var f: Fixture
        lateinit var anchor: OverlayWindowManager
        instrumentation.runOnMainSync {
            f = Fixture()
            anchor = f.attach()
            val params = f.balls.layoutParams as WindowManager.LayoutParams
            assertTrue(
                "FLAG_SPLIT_TOUCH must be set to allow touches alongside game gestures",
                params.flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH != 0
            )
            assertTrue(
                "FLAG_NOT_TOUCH_MODAL must be set to allow touches outside window to reach game",
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0
            )
            anchor.update(locked = false)
            val updatedParams = f.balls.layoutParams as WindowManager.LayoutParams
            assertTrue(updatedParams.flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH != 0)
            assertTrue(updatedParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            anchor.update(locked = true)
            val lockedParams = f.balls.layoutParams as WindowManager.LayoutParams
            assertTrue(lockedParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(lockedParams.flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH != 0)
            anchor.remove()
            f.dispose()
        }
    }

    @Test fun simultaneousTouchAcrossBallsDispatchesIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var f: Fixture
        lateinit var anchor: OverlayWindowManager
        instrumentation.runOnMainSync {
            f = Fixture()
            anchor = f.attach()
            try {
                // Ball container must enable motion event splitting between children
                assertTrue(f.balls.isMotionEventSplittingEnabled)

                // 1. Hold eye button (finger 1 down)
                f.event(MotionEvent.ACTION_DOWN, id = "eye")
                assertEquals(1, f.opens)
                assertEquals(0, f.closes)

                // 2. While eye is held, tap search button (finger 2 down and up)
                f.event(MotionEvent.ACTION_DOWN, id = "search")
                f.event(MotionEvent.ACTION_UP, id = "search")
                assertEquals(1, f.searches)
                assertEquals(1, f.opens)
                assertEquals(0, f.closes) // eye must still remain open!

                // 3. Release eye button (finger 1 up)
                f.event(MotionEvent.ACTION_UP, id = "eye")
                assertEquals(1, f.closes)
            } finally {
                anchor.remove()
                f.dispose()
            }
        }
    }
}
