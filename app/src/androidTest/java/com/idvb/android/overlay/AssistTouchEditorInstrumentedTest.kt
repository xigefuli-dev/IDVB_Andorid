package com.idvb.android.overlay

import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AssistTouchEditorInstrumentedTest {
    @Test fun draggingBothKeysConfirmsNormalizedPositionsAndResetClearsBoth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val d = context.resources.displayMetrics.density
            var saved: List<Float>? = null
            val view = AssistTouchEditorView(context, emptyList()) { saved = it }
            val w = (720 * d).toInt(); val h = (400 * d).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            fun event(action: Int, x: Float, y: Float) {
                val e = MotionEvent.obtain(0, 10, action, x * d, y * d, 0)
                view.dispatchTouchEvent(e); e.recycle()
            }
            fun tap(x: Float, y: Float) { event(MotionEvent.ACTION_DOWN, x, y); event(MotionEvent.ACTION_UP, x, y) }
            for (i in 0..1) {
                event(MotionEvent.ACTION_DOWN, 44f + i * 72, 360f)
                event(MotionEvent.ACTION_MOVE, 200f + i * 100, 100f)
                event(MotionEvent.ACTION_UP, 200f + i * 100, 100f)
            }
            tap(670f, 292f)
            assertTrue(validAssistPoints(saved!!)); assertEquals(200f / 720, saved!![0], .002f)
            assertEquals(.25f, saved!![1], .002f)
            event(MotionEvent.ACTION_DOWN, 300f, 100f)
            event(MotionEvent.ACTION_MOVE, 500f, 380f)
            event(MotionEvent.ACTION_UP, 500f, 380f)
            tap(670f, 292f)
            assertEquals(.95f, saved!![3], .002f)
            tap(580f, 292f); tap(670f, 292f)
            assertEquals(emptyList<Float>(), saved)
        }
    }
}
