package com.idvb.android.overlay

import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayLayoutGestureInstrumentedTest {
    @Test fun tapClicksWhileDragAndCancelDoNotClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicks = 0
        lateinit var balls: OverlayBallView
        lateinit var search: View
        instrumentation.runOnMainSync {
            balls = OverlayBallView(instrumentation.targetContext)
            balls.listener = object : OverlayBallView.Listener {
                override fun onSearch() { clicks++ }
                override fun onToggleGuide() {}
                override fun onNextFloor() {}
                override fun onNextVariant() {}
                override fun onFreeAdjust() {}
                override fun onCalibrate() {}
                override fun onClose() {}
                override fun onMove(dx: Float, dy: Float) {}
                override fun onMenuExpanded(expanded: Boolean) {}
            }
            search = (balls.getChildAt(0) as LinearLayout).getChildAt(0)
            fun event(action: Int, x: Float) {
                MotionEvent.obtain(0, 0, action, x, 0f, 0).also {
                    search.dispatchTouchEvent(it); it.recycle()
                }
            }
            event(MotionEvent.ACTION_DOWN, 0f)
            event(MotionEvent.ACTION_UP, 0f)
            assertEquals(1, clicks)
            event(MotionEvent.ACTION_DOWN, 0f)
            event(MotionEvent.ACTION_MOVE, 200f)
            event(MotionEvent.ACTION_UP, 200f)
            assertEquals(1, clicks)
            event(MotionEvent.ACTION_DOWN, 0f)
            event(MotionEvent.ACTION_CANCEL, 0f)
            assertEquals(1, clicks)
            // Unavailable actions must remain touchable so they can enter layout editing.
            assertTrue((balls.getChildAt(0) as LinearLayout).getChildAt(1).isEnabled)
            assertTrue((balls.getChildAt(0) as LinearLayout).getChildAt(2).isEnabled)
        }
    }
}

