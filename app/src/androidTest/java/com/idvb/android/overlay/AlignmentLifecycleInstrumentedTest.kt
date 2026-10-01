package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.data.EyeButtonAction
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AlignmentLifecycleInstrumentedTest {
    @Test fun captureHidesEveryControlIncludingMoreWithoutDisablingHitTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val balls = OverlayBallView(instrumentation.targetContext)
            val controls = balls.buttons.values + balls.moreButton
            val original = controls.associateWith { it.alpha }
            balls.setCaptureHidden(true)
            balls.setCaptureHidden(true)
            controls.forEach {
                assertEquals(0f, it.alpha, 0f)
                assertTrue(it.isEnabled)
            }
            assertEquals(View.VISIBLE, balls.moreButton.visibility)
            balls.setCaptureHidden(false)
            controls.forEach { assertEquals(original.getValue(it), it.alpha, 0f) }
        }
    }

    @Test fun displayOnlyDoesNotCaptureAndHideFloorAndDisplayChangesInvalidateAlignment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
            val context = if (Build.VERSION.SDK_INT >= 30)
                display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
            val prefs = base.getSharedPreferences("overlay", Context.MODE_PRIVATE)
            val keys = listOf("eye_button_action", "last_floor_key")
            val saved = keys.associateWith { prefs.getString(it, null) }
            val previousState = OverlayState.state.value
            val root = File(AppServices.repository.mapsRoot, "alignment-test-${UUID.randomUUID()}").apply { mkdirs() }
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val image = File(root, "floor.png")
            image.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val service = OverlayService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, base)
            fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun set(name: String, value: Any?) = field(name).set(service, value)
            fun call(name: String): Any? = OverlayService::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(service)
            set("overlayContext", context)
            val size = call("screenSize") as Pair<*, *>
            val floor = FloorRecord("1f", "first", 1, "${root.name}/floor.png", 100, 100)
            val map = MapRecord(root.name, "fixture", "fixture", "fixture", 1,
                listOf(floor, floor.copy(key = "2f", sortOrder = 2)))
            val guide = GuideMapView(context)
            for (name in listOf("window", "guideWindow", "scanProgressWindow")) set(name, OverlayWindowManager(context))
            set("balls", OverlayBallView(context)); set("guideView", guide)
            set("currentMap", map); set("lastCaptureScreen", size)
            set("alignedGuideBounds", RectF(20f, 30f, 120f, 130f))
            set("alignedGuideViewport", RectF(0f, 0f, 200f, 200f))
            OverlayState.update { it.copy(running = true, visible = true) }
            try {
                AppServices.prefs.eyeButtonAction = EyeButtonAction.SHOW_ONLY
                val generation = field("alignmentGeneration").getInt(service)
                call("toggleGuide")
                assertTrue(field("guideVisible").getBoolean(service))
                assertEquals(generation, field("alignmentGeneration").getInt(service))
                assertFalse(field("aligning").getBoolean(service))
                assertNull(field("captureSession").get(service))
                assertEquals(View.VISIBLE, guide.visibility)
                val preparedBitmap = field("guideBitmap").get(service)
                assertEquals(true, call("loadGuideFloor"))
                assertSame("Repeated opens must reuse unchanged decoded guide pixels", preparedBitmap, field("guideBitmap").get(service))
                // Holding an already visible eye must never invert it to hidden.
                call("openGuide")
                assertTrue(field("guideVisible").getBoolean(service))

                set("aligning", true); set("alignmentCapturing", true)
                val cancellation = AlignmentCancellation()
                set("activeAlignmentCancellation", cancellation)
                call("applyPracticeVisibility")
                val balls = field("balls").get(service) as OverlayBallView
                assertEquals("Capture must keep the eye hit target available", View.VISIBLE, balls.visibility)
                // Release remains effective even if another action started scanning.
                set("scanning", true)
                call("hideGuide")
                set("scanning", false)
                assertFalse(field("guideVisible").getBoolean(service))
                assertFalse(field("aligning").getBoolean(service))
                assertEquals(generation + 1, field("alignmentGeneration").getInt(service))
                assertEquals(View.INVISIBLE, guide.visibility)
                assertTrue("Hiding the eye must cancel computation, not just its callback", cancellation.isCancelled)
                call("hideGuide")
                assertEquals("Repeated release must not start or cancel another request",
                    generation + 1, field("alignmentGeneration").getInt(service))

                // Reopen on the very same UI turn, without waiting for an old worker callback.
                call("toggleGuide")
                assertTrue(field("guideVisible").getBoolean(service))
                assertEquals(View.VISIBLE, guide.visibility)
                call("toggleGuide")
                assertFalse(field("guideVisible").getBoolean(service))

                set("aligning", true)
                call("nextFloor")
                assertEquals(1, field("floorIndex").getInt(service))
                assertFalse(field("aligning").getBoolean(service))
                assertNull(field("alignedGuideBounds").get(service))

                set("aligning", true); set("lastCaptureScreen", 1 to 1)
                call("refreshWindowsForDisplayChange")
                assertFalse(field("aligning").getBoolean(service))
                assertEquals(size, field("lastCaptureScreen").get(service))
            } finally {
                guide.showBitmap(null)
                (field("guideBitmap").get(service) as? Bitmap)?.recycle()
                (field("recognitionExecutor").get(service) as com.idvb.android.recognize.RecognitionExecutor).close()
                (field("accessibilityCaptureExecutor").get(service) as java.util.concurrent.ExecutorService).shutdown()
                val edit = prefs.edit()
                saved.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
                edit.commit()
                OverlayState.update { previousState }
                root.deleteRecursively()
            }
        }
    }
}
