package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdjustDisplayChangeInstrumentedTest {
    @Test fun repeatedDisplayNotificationsKeepAdjustmentWindowAttachedAndEditsIntact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val displayContext = base.createDisplayContext(
                base.getSystemService(DisplayManager::class.java).getDisplay(0),
            )
            val context = if (Build.VERSION.SDK_INT >= 30) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else displayContext
            // Exercise the real refresh path without starting capture or changing
            // consent, map preferences or the user's running service.
            val service = OverlayService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, base)
            fun set(name: String, value: Any) {
                OverlayService::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(service, value)
            }
            set("overlayContext", context)
            val size = OverlayService::class.java.getDeclaredMethod("screenSize")
                .apply { isAccessible = true }.invoke(service) as Pair<*, *>
            val window = OverlayWindowManager(context).apply {
                width = size.first as Int
                height = size.second as Int
            }
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val region = RectF(20f, 20f, 120f, 120f)
            val view = BlueprintImageAdjustView(context, bitmap, region, region, .5f)
            var detached = 0
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { detached++ }
            })
            set("window", OverlayWindowManager(context))
            set("blueprintWindow", window)
            set("balls", OverlayBallView(context))
            set("adjustView", view)
            set("lastCaptureScreen", size)
            val refresh = OverlayService::class.java.getDeclaredMethod("refreshWindowsForDisplayChange")
                .apply { isAccessible = true }
            try {
                window.add(view, locked = false, focusable = true)
                view.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null)
                repeat(30) {
                    refresh.invoke(service)
                    assertSame(view, OverlayService::class.java.getDeclaredField("adjustView")
                        .apply { isAccessible = true }.get(service))
                    assertTrue(view.isAttachedToWindow)
                    assertEquals(0, detached)
                }
                val opacity = BlueprintImageAdjustView::class.java.getDeclaredField("opacity")
                    .apply { isAccessible = true }.getFloat(view)
                assertEquals(.55f, opacity, .001f)
                val params = view.layoutParams as WindowManager.LayoutParams
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            } finally {
                window.remove()
                bitmap.recycle()
            }
        }
    }
}
