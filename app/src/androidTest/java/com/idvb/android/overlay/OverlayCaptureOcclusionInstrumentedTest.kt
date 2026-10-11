package com.idvb.android.overlay

import android.content.ContextWrapper
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.alignment.AutoMapOpenOcclusion
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OverlayCaptureOcclusionInstrumentedTest {
    @Test fun actualLayoutEditorHasNoVisualOcclusionWhileItsButtonsStillDo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue(Settings.canDrawOverlays(target))
        val display = target.createDisplayContext(target.getSystemService(DisplayManager::class.java).getDisplay(0))
        val windowContext = if (Build.VERSION.SDK_INT >= 30)
            display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
        val preferences = "occlusion-fixture-${UUID.randomUUID()}"
        val context = object : ContextWrapper(windowContext) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferences, mode)
        }
        lateinit var anchor: OverlayWindowManager
        lateinit var layout: OverlayButtonLayout
        var added = false
        var layoutCreated = false
        try {
            instrumentation.runOnMainSync {
                val balls = OverlayBallView(context)
                val screen = windowContext.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                anchor = OverlayWindowManager(context).apply { x = 20; y = 50; width = 620; height = 160 }
                anchor.add(balls, locked = false)
                added = true
                layout = OverlayButtonLayout(context, balls, anchor) { screen.width() to screen.height() }
                layoutCreated = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { layout.begin("search") }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val roots = OverlayWindowManager.visibleScreenRoots()
                val editor = roots.single { it.viewClass == View::class.java.name }
                assertFalse(editor.occludesCapture)
                val corner = RectF(editor.bounds.right - 30, editor.bounds.bottom - 30,
                    editor.bounds.right - 1, editor.bounds.bottom - 1)
                assertFalse("Transparent gesture root must not pause the sidebar", AutoMapOpenOcclusion.overlaps(
                    corner, OverlayWindowManager.visibleScreenBounds()))
                val button = roots.first { it.occludesCapture && it.viewClass == "android.widget.LinearLayout" }
                assertTrue("Actual controls still block sampling", AutoMapOpenOcclusion.overlaps(
                    button.bounds, OverlayWindowManager.visibleScreenBounds()))
            }
        } finally {
            instrumentation.runOnMainSync { if (added) { if (layoutCreated) layout.dispose(); anchor.remove() } }
        }
    }

    @Test fun finishedProgressFitsOriginalXiaomiCropAndOpaquePopupStillBlocks() {
        // Original feedback geometry replay; no screenshot resampling or synthetic map identity.
        val placement = requireNotNull(ScanProgressPlacement.place(1125.2402, 176.5, 2202.37, 1016.6201,
            2670, 1200, 1105, 182, 2202.0))
        val reference = RectF(2202f, 0f, 2670f, 1200f)
        val progress = RectF(placement.x.toFloat(), placement.y.toFloat(),
            (placement.x + placement.width).toFloat(), (placement.y + placement.height).toFloat())
        assertFalse(AutoMapOpenOcclusion.overlaps(reference, listOf(progress)))
        assertTrue(AutoMapOpenOcclusion.overlaps(reference, listOf(RectF(2300f, 100f, 2400f, 200f))))
    }
}
