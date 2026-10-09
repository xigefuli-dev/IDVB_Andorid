package com.idvb.android.recognize

import android.view.accessibility.AccessibilityEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.alignment.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ForegroundRecoveryInstrumentedTest {
    @Test fun lenovoOriginalPixelsReplayAndTransientWindowDoesNotCancelOpening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, "lenovo-auto-corpus-v2")
        val documents = folder.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("Push the original feedback snapshots into private cache", documents.isNotEmpty())
        var replayed = 0
        var opening: JSONObject? = null
        var openingDocument: JSONObject? = null
        var closing: JSONObject? = null
        var unavailable = 0
        val samples = mutableSetOf<Long>()
        for (file in documents) {
            val text = file.readText()
            val document = JSONObject(text)
            if (!document.getBoolean("replayReady")) { unavailable++; continue }
            replayed += AutoMapOpenReplay.replay(text).knownFrames
            val frames = document.getJSONArray("frames")
            for (index in 0 until frames.length()) {
                val frame = frames.getJSONObject(index)
                samples.add(frame.getLong("nowMs"))
                if (frame.getLong("nowMs") == 731276867L) { opening = frame; openingDocument = document }
                if (frame.getLong("nowMs") == 731324450L) closing = frame
            }
        }
        assertTrue(replayed > 0)
        // Reset snapshots explicitly have no reference. All 32 unique frames also
        // survive in complete snapshots; preserve session paths (names repeat).
        assertEquals(33, unavailable)
        assertEquals(32, samples.size)
        val source = requireNotNull(openingDocument)
        fun signature(array: org.json.JSONArray) = AutoMapOpenDetector.signature(IntArray(array.length()) { array.getInt(it) })
        val reference = signature(source.getJSONObject("reference").getJSONArray("pixels"))
        val detector = AutoMapOpenDetector(reference, AutoMapOpenConfig(openFrames = 1, closeFrames = 1,
            sidebarWidthFraction = source.getJSONObject("configuration").getDouble("sidebarWidthFraction")))
        val frame = requireNotNull(opening)
        val now = frame.getLong("nowMs")
        assertEquals(AutoMapOpenTransition.OPENED, detector.observe(signature(frame.getJSONArray("pixels")), now).transition)
        assertNotNull(detector.alignmentStarted(now))
        val closedFrame = requireNotNull(closing)
        val closingDetector = AutoMapOpenDetector(reference, AutoMapOpenConfig(openFrames = 1, closeFrames = 1,
            sidebarWidthFraction = source.getJSONObject("configuration").getDouble("sidebarWidthFraction")))
        closingDetector.observe(signature(frame.getJSONArray("pixels")), now)
        assertEquals(AutoMapOpenTransition.CLOSED, closingDetector.observe(
            signature(closedFrame.getJSONArray("pixels")), closedFrame.getLong("nowMs")).transition)

        // Legacy feedback omitted isFullScreen. Exercise both interpretations explicitly;
        // original pixels are replay evidence, these event flags are regression inputs.
        instrumentation.runOnMainSync {
            val service = AccessibilityScreenCaptureService()
            val previous = AccessibilityScreenCaptureService.foregroundPackage
            val previousListener = AccessibilityScreenCaptureService.foregroundChangedListener
            fun event(pkg: String?, window: String, full: Boolean = false) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                try { event.packageName = pkg; event.className = window; event.isFullScreen = full
                    service.onAccessibilityEvent(event)
                } finally { event.recycle() }
            }
            try {
                AccessibilityScreenCaptureService.foregroundChangedListener = null
                event("com.netease.dwrg", "com.netease.dwrg.Client", true)
                var cancelled = 0
                AccessibilityScreenCaptureService.foregroundChangedListener = { pkg ->
                    if (pkg != "com.netease.dwrg") { cancelled++; detector.alignmentInterrupted() }
                }
                event("com.android.systemui", "android.widget.FrameLayout")
                event("com.zui.game.service", "android.widget.LinearLayout")
                event("com.zui.freeform.sidebar", "android.widget.FrameLayout")
                assertEquals("com.netease.dwrg", AccessibilityScreenCaptureService.foregroundPackage)
                assertEquals(0, cancelled)
                assertTrue(detector.alignmentInFlight)
                event("com.android.systemui", "android.widget.FrameLayout", true)
                assertEquals(1, cancelled)
                assertFalse(detector.alignmentInFlight)
                event("com.netease.dwrg", "com.netease.dwrg.Client", true)
                event("com.android.systemui", "com.android.systemui.shade.NotificationShadeWindowView")
                assertEquals(2, cancelled)
                event("com.zui.launcher", "com.zui.launcher.drawer.NormalLauncher", true)
                assertEquals("com.zui.launcher", AccessibilityScreenCaptureService.foregroundPackage)
            } finally {
                AccessibilityScreenCaptureService.foregroundChangedListener = null
                event(previous, "restore-test-foreground")
                AccessibilityScreenCaptureService.foregroundChangedListener = previousListener
            }
        }
    }
}
