package com.idvb.android.recognize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.overlay.BlueprintCalibrationView
import com.idvb.android.overlay.OverlayWindowManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in device matrix: uses installed maps read-only and real overlay windows. */
@RunWith(AndroidJUnit4::class)
class CandidateDisplayMatrixInstrumentedTest {
    @Test fun actualOverlayAndCalibrationAtCurrentDisplayConfiguration() {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("matrixLabel")
        assumeTrue(label != null)
        require(label!!.matches(Regex("[a-zA-Z0-9_-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val display = target.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val context = target.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val density = context.resources.displayMetrics.density
        args.getString("expectedWidth")?.let { assertEquals("Display width", it.toInt(), bounds.width()) }
        args.getString("expectedHeight")?.let { assertEquals("Display height", it.toInt(), bounds.height()) }
        val repository = MapRepository(target)
        val maps = repository.loadCatalog().maps
        assertTrue("Matrix requires imported maps", maps.isNotEmpty())
        val candidates = maps.map { map -> RecognitionCandidate(map,
            map.floors.minBy { it.sortOrder }.key, CandidateDisposition.NEEDS_VERIFICATION,
            structureScale = .4, structureOffsetX = bounds.width() * .8,
            structureOffsetY = bounds.height() * .8, evidenceLabel = "设备测试 · 小匹配姿态") }
        val frame = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.DKGRAY)
        val window = OverlayWindowManager(context).apply { width = bounds.width(); height = bounds.height() }
        val directory = File(target.getExternalFilesDir(null), "candidate-matrix").apply { mkdirs() }
        fun screenshot(suffix: String) {
            instrumentation.waitForIdleSync()
            // Idle callbacks can precede the next compositor frame after invalidate().
            SystemClock.sleep(200)
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(directory, "$label-$suffix.png").outputStream().use {
                shot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { shot.recycle() }
        }
        fun tap(view: android.view.View, x: Float, y: Float) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 0, action, x, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        try {
            lateinit var view: CandidateSelectionView
            instrumentation.runOnMainSync {
                view = CandidateSelectionView(context, RecognitionResult(frame, candidates,
                    com.idvb.android.recognize.gate.ScreenRect(0.0, 0.0, bounds.width().toDouble(), bounds.height().toDouble())), repository)
                window.add(view, locked = false)
            }
            instrumentation.waitForIdleSync()
            val field = CandidateSelectionView::class.java.getDeclaredField("thumbnails").apply { isAccessible = true }
            var ready = false
            repeat(100) {
                if (!ready) {
                    instrumentation.runOnMainSync { ready = (field.get(view) as List<*>).take(2).all { it != null } }
                    if (!ready) SystemClock.sleep(50)
                }
            }
            assertTrue("Previews did not load", ready)
            screenshot("candidates")
            fun dimension(name: String): Float = (CandidateSelectionView::class.java
                .getDeclaredMethod(name).apply { isAccessible = true }.invoke(view) as Number).toFloat()
            val cardWidth = dimension("getCardWidth")
            val cardHeight = dimension("getCardHeight")
            val listTop = dimension("getListTop")
            File(directory, "$label.txt").writeText("screen=${bounds.width()}x${bounds.height()} dpi=${density * 160}\ncard=${cardWidth / density}x${cardHeight / density}dp listTop=${listTop / density}dp\n")
            assertTrue("Card too narrow: ${cardWidth / density}dp", cardWidth >= 150 * density)
            assertTrue("First card cannot fit: $cardHeight > ${view.height - listTop}", cardHeight <= view.height - listTop + 1)
            instrumentation.runOnMainSync {
                // Scroll must leave the final item reachable, including a single-column layout.
                for ((action, y) in listOf(MotionEvent.ACTION_DOWN to (view.height - 10f),
                    MotionEvent.ACTION_MOVE to (listTop + 5f), MotionEvent.ACTION_UP to (listTop + 5f))) {
                    val event = MotionEvent.obtain(0, 0, action, view.width - 30f, y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            instrumentation.waitForIdleSync()
            screenshot("scrolled")
            val chipsField = CandidateSelectionView::class.java.getDeclaredField("tagChipRects").apply { isAccessible = true }
            instrumentation.runOnMainSync {
                val chips = chipsField.get(view) as List<*>
                if (chips.isNotEmpty()) {
                    val rect = (chips.first() as Pair<*, *>).first as RectF
                    tap(view, rect.centerX(), rect.centerY())
                }
            }
            screenshot("filter")
            instrumentation.runOnMainSync {
                val menu = CandidateSelectionView::class.java.getDeclaredField("tagMenuBounds")
                    .apply { isAccessible = true }.get(view) as RectF?
                if (menu != null) {
                    assertTrue("Filter extends beyond screen", menu.top >= 0 && menu.bottom <= view.height)
                    val options = CandidateSelectionView::class.java.getDeclaredField("tagOptionRects")
                        .apply { isAccessible = true }.get(view) as List<*>
                    assertTrue(options.isNotEmpty())
                    val last = (options.last() as Pair<*, *>).first as RectF
                    tap(view, last.centerX(), last.centerY())
                    assertNull(CandidateSelectionView::class.java.getDeclaredField("openTagGroup")
                        .apply { isAccessible = true }.get(view))
                }
            }
            instrumentation.runOnMainSync { window.remove() }

            lateinit var calibration: BlueprintCalibrationView
            var confirmed: RectF? = null
            instrumentation.runOnMainSync {
                calibration = BlueprintCalibrationView(context).apply {
                    listener = object : BlueprintCalibrationView.Listener {
                        override fun onConfirmed(region: RectF) { confirmed = region }
                        override fun onCancelled() = Unit
                    }
                }
                window.add(calibration, locked = false)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                for ((action, point) in listOf(MotionEvent.ACTION_DOWN to (.2f to .35f),
                    MotionEvent.ACTION_MOVE to (.8f to .65f), MotionEvent.ACTION_UP to (.8f to .65f))) {
                    val event = MotionEvent.obtain(0, 0, action, point.first * calibration.width, point.second * calibration.height, 0)
                    try { calibration.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            instrumentation.waitForIdleSync()
            screenshot("calibration")
            instrumentation.runOnMainSync {
                val confirm = BlueprintCalibrationView::class.java.getDeclaredMethod("getConfirmRect")
                    .apply { isAccessible = true }.invoke(calibration) as RectF
                val reset = BlueprintCalibrationView::class.java.getDeclaredMethod("getResetRect")
                    .apply { isAccessible = true }.invoke(calibration) as RectF
                assertFalse("Calibration buttons overlap", RectF.intersects(confirm, reset))
                tap(calibration, confirm.centerX(), confirm.centerY())
            }
            val selected = requireNotNull(confirmed) { "Calibration confirm was not reachable" }
            assertEquals(.2f, selected.left / calibration.width, .001f)
            assertEquals(.35f, selected.top / calibration.height, .001f)
            assertEquals(.8f, selected.right / calibration.width, .001f)
            assertEquals(.65f, selected.bottom / calibration.height, .001f)
        } finally {
            instrumentation.runOnMainSync { window.remove() }
            frame.recycle()
        }
    }
}
