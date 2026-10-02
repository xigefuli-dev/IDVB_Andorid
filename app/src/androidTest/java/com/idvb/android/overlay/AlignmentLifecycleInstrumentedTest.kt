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
            balls.setCaptureHidden(true, android.graphics.Rect(5000, 5000, 5001, 5001))
            balls.setCaptureHidden(true)
            // These presentation writers used to restore the eye/floor alpha mid-capture.
            balls.mapLocked = true
            balls.candidatesAvailable = true
            balls.mapLocked = false
            balls.candidatesAvailable = false
            controls.forEach {
                assertEquals(0f, it.alpha, 0f)
                assertTrue(it.isEnabled)
            }
            assertEquals(View.VISIBLE, balls.moreButton.visibility)
            balls.setCaptureHidden(false)
            controls.forEach { assertEquals(original.getValue(it), it.alpha, 0f) }
        }
    }

    @Test fun separatedControlGuideAndNotificationWindowsRenderBeforeCaptureAndCancelOldCallbacks() {
        org.junit.Assume.assumeTrue("Private test-APK screenshot streaming requires Android 12/API 31+",
            Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
        val context = if (Build.VERSION.SDK_INT >= 30)
            display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
        val layoutPrefs = base.getSharedPreferences("overlay_button_layout", Context.MODE_PRIVATE)
        val consentPrefs = base.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val previousLayout = layoutPrefs.all.filterKeys { it == "custom" ||
            it.matches(Regex("(search|eye|floor|variant)_(x|y|scale)")) }
        val previousConsent = consentPrefs.all["accepted_revision"] as? Int
        val events = java.util.Collections.synchronizedList(mutableListOf<com.idvb.android.alignment.AlignmentLogEvent>())
        val sink = com.idvb.android.alignment.AlignmentLogSink { events.add(it) }
        val complete = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancelledCalls = java.util.concurrent.atomic.AtomicInteger()
        val invalidated = java.util.concurrent.CountDownLatch(1)
        val invalidAccepted = java.util.concurrent.atomic.AtomicBoolean(true)
        val anchor = OverlayWindowManager(context)
        val guideWindow = OverlayWindowManager(context)
        lateinit var balls: OverlayBallView
        lateinit var layout: OverlayButtonLayout
        lateinit var notices: OverlayNotifications
        lateinit var guide: View
        var layoutCreated = false
        var noticesCreated = false
        var activeBarrier: OverlayCaptureFrameBarrier? = null
        var invalidBarrier: OverlayCaptureFrameBarrier? = null
        var before: Bitmap? = null
        var after: Bitmap? = null
        lateinit var eyeBounds: android.graphics.Rect
        lateinit var guideBounds: android.graphics.Rect
        val touches = java.util.concurrent.atomic.AtomicInteger()
        val testPackage = instrumentation.context.packageName
        check(testPackage in setOf("com.idvb.android.verification.test")) {
            "Capture evidence must belong to the isolated test APK, got $testPackage"
        }
        val evidenceDirectory = "files/idvb/test-evidence/capture-surfaces-${UUID.randomUUID()}"
        check(evidenceDirectory.matches(Regex("files/idvb/test-evidence/capture-surfaces-[0-9a-f-]{36}")))
        fun bounds(view: View): android.graphics.Rect {
            val position = IntArray(2).also(view::getLocationOnScreen)
            return android.graphics.Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }
        fun greenPixels(bitmap: Bitmap, region: android.graphics.Rect): Int {
            var count = 0
            for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(bitmap.height))
                for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(bitmap.width)) {
                    val color = bitmap.getPixel(x, y)
                    val green = android.graphics.Color.green(color)
                    if (green > android.graphics.Color.red(color) + 25 &&
                        green > android.graphics.Color.blue(color) + 25) count++
                }
            return count
        }
        fun save(name: String, bitmap: Bitmap) {
            check(name in setOf("before-hide.png", "after-render-barrier.png"))
            val relativePath = "$evidenceDirectory/$name"
            check(relativePath.matches(Regex("files/idvb/test-evidence/capture-surfaces-[0-9a-f-]{36}/[a-z-]+\\.png")))
            val png = java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            val marker = "IDVB_TESTAPK_EVIDENCE_SAVED"
            // Instrumentation runs as the target UID, so the shell writes as the test APK UID.
            // UiAutomation's documented RW descriptor order is stdout[0], stdin[1].
            // UiAutomationConnection uses Runtime.exec(String), which splits at whitespace.
            // Pass sh -c one generated argument; its standard IFS supplies argument separators.
            val separator = "\${IFS}"
            val script = "mkdir${separator}-p${separator}$evidenceDirectory&&cat>$relativePath&&echo${separator}$marker"
            check(script.none(Char::isWhitespace))
            val command = "run-as $testPackage sh -c $script"
            val descriptors = instrumentation.uiAutomation.executeShellCommandRw(command)
            try {
                check(descriptors.size == 2)
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { input ->
                    input.write(png)
                }
                // Closing stdin sends EOF to cat before waiting for the completion receipt.
                val receipt = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0])
                    .bufferedReader().use { it.readText().trim() }
                check(receipt == marker) {
                    "Private test-APK evidence write failed: $testPackage/$relativePath; receipt=$receipt"
                }
            } finally {
                descriptors.forEach { runCatching { it.close() } }
            }
        }
        try {
            // Only the isolated test APK is opened, giving both screenshots the same neutral background.
            instrumentation.context.startActivity(android.content.Intent().apply {
                component = android.content.ComponentName(instrumentation.context.packageName, TouchTargetActivity::class.java.name)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            })
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                consentPrefs.edit().putInt("accepted_revision", com.idvb.android.UsageConsent.REVISION).commit()
                layoutPrefs.edit().putBoolean("custom", false).commit()
                balls = OverlayBallView(context)
                balls.mapLocked = true
                anchor.width = 700; anchor.height = 160; anchor.x = 40; anchor.y = 80
                anchor.add(balls, locked = false)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                layoutPrefs.edit().putBoolean("custom", true).apply {
                    listOf("search", "eye", "floor", "variant").forEachIndexed { index, id ->
                        putFloat("${id}_x", .05f + index * .20f)
                        putFloat("${id}_y", .35f)
                        putFloat("${id}_scale", 1f)
                    }
                }.commit()
                val metrics = base.resources.displayMetrics
                layout = OverlayButtonLayout(context, balls, anchor) { metrics.widthPixels to metrics.heightPixels }
                layoutCreated = true
                assertTrue("Use the real independently attached button layout", layout.separated)
                guide = View(context).apply { setBackgroundColor(android.graphics.Color.GREEN) }
                guideWindow.width = 120; guideWindow.height = 100; guideWindow.x = 60; guideWindow.y = 400
                guideWindow.add(guide, locked = true)
                notices = OverlayNotifications(context) { metrics.widthPixels to metrics.heightPixels }
                noticesCreated = true
                notices.show("capture surface test", 0L)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                eyeBounds = bounds(balls.buttons.getValue("eye"))
                guideBounds = bounds(guide)
            }
            before = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            save("before-hide.png", before!!)
            val visibleEyePixels = greenPixels(before!!, eyeBounds)
            val visibleGuidePixels = greenPixels(before!!, guideBounds)
            assertTrue("The positive screenshot must contain the fixture eye border", visibleEyePixels > 40)
            assertTrue("The positive screenshot must contain the fixture guide", visibleGuidePixels > 500)
            instrumentation.runOnMainSync {
                // Match immediate close/reopen before traversal: the old guide surface is
                // visible in the positive screenshot although the current view flag changes.
                guide.visibility = View.INVISIBLE
                val controls = balls.captureControls().filter { it.second.isShown } +
                    listOf("guide" to guide, "notifications" to notices.captureView)
                val rootCount = controls.map { it.second.rootView }.distinct().size
                assertTrue("The guide, notification and separated controls must have distinct windows", rootCount >= 6)
                balls.setCaptureHidden(true)
                guide.alpha = 0f
                guide.visibility = View.VISIBLE
                notices.setCaptureHidden(true)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val old = OverlayCaptureFrameBarrier(handler, controls, { true }, sink) { cancelledCalls.incrementAndGet() }
                old.start()
                old.cancel()
                invalidBarrier = OverlayCaptureFrameBarrier(handler, controls, { false }, sink) {
                    invalidAccepted.set(it); invalidated.countDown()
                }.also { it.start() }
                activeBarrier = OverlayCaptureFrameBarrier(handler, controls, { true }, sink) {
                    accepted.set(it); complete.countDown()
                }.also { it.start() }
                // Updates between rendering registration and draw must not resurrect pixels.
                balls.mapLocked = false
                balls.candidatesAvailable = true
                notices.show("late notice while capturing", 0L)
                notices.setHidden(false)
                balls.captureControls().forEach { assertEquals(0f, it.second.alpha, 0f) }
                assertEquals(View.VISIBLE, notices.captureView.visibility)
                assertEquals(0f, notices.captureView.alpha, 0f)
                assertEquals(0f, guide.alpha, 0f)
                assertTrue(balls.buttons.getValue("eye").isEnabled)
            }
            assertTrue("Each independently attached window must render its hidden state",
                complete.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("The current request is released only after every root renders", accepted.get())
            assertTrue(invalidated.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("A stale generation must complete as invalid without releasing capture", invalidAccepted.get())
            instrumentation.waitForIdleSync()
            assertEquals("Cancelled draw/commit callbacks cannot release a later capture", 0, cancelledCalls.get())
            val snapshot = synchronized(events) { events.toList() }
            val finished = snapshot.last { it.stage == "capture.overlay-render-complete" }
            assertEquals(finished.measurements.getValue("roots"), finished.measurements.getValue("renderedRoots"), 0.0)
            assertTrue(snapshot.count { it.stage == "capture.overlay-root-rendered" } >= 6)
            assertTrue(snapshot.any { it.stage == "capture.overlay-render-cancelled" })
            after = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            save("after-render-barrier.png", after!!)
            assertEquals("The rendered transparent eye must leave no green border pixels", 0,
                greenPixels(after!!, eyeBounds))
            assertEquals("The rendered transparent guide must leave no green pixels", 0,
                greenPixels(after!!, guideBounds))
            // Alpha-zero controls still receive a real injected pointer and can cancel promptly.
            instrumentation.runOnMainSync {
                balls.buttons.getValue("eye").setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) touches.incrementAndGet()
                    true
                }
            }
            val downTime = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                    eyeBounds.exactCenterX(), eyeBounds.exactCenterY(), 0).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
            assertEquals("The transparent fixture eye window must retain its touch target", 1, touches.get())
            instrumentation.runOnMainSync {
                balls.setCaptureHidden(false)
                assertEquals(1f, balls.buttons.getValue("eye").alpha, 0f)
                assertEquals(.32f, balls.buttons.getValue("floor").alpha, 0f)
                notices.setCaptureHidden(false)
                assertEquals(View.VISIBLE, notices.captureView.visibility)
                notices.setHidden(true)
                notices.setCaptureHidden(true)
                notices.setCaptureHidden(false)
                assertEquals("Capture release preserves separately hidden practice notifications", View.INVISIBLE,
                    notices.captureView.visibility)
            }
        } finally {
            instrumentation.runOnMainSync {
                activeBarrier?.cancel()
                invalidBarrier?.cancel()
                if (layoutCreated) layout.dispose()
                if (noticesCreated) notices.close()
                guideWindow.remove(); anchor.remove()
                val layoutEdit = layoutPrefs.edit().remove("custom")
                listOf("search", "eye", "floor", "variant").forEach { id ->
                    listOf("x", "y", "scale").forEach { key -> layoutEdit.remove("${id}_$key") }
                }
                previousLayout.forEach { (key, value) -> when (value) {
                    is Boolean -> layoutEdit.putBoolean(key, value)
                    is Float -> layoutEdit.putFloat(key, value)
                } }
                layoutEdit.commit()
                val consentEdit = consentPrefs.edit()
                if (previousConsent == null) consentEdit.remove("accepted_revision") else consentEdit.putInt("accepted_revision", previousConsent)
                consentEdit.commit()
            }
            before?.recycle(); after?.recycle()
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
            fun callHideGuide(): Any? = OverlayService::class.java
                .getDeclaredMethod("hideGuide", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(service, true)
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
                callHideGuide()
                set("scanning", false)
                assertFalse(field("guideVisible").getBoolean(service))
                assertFalse(field("aligning").getBoolean(service))
                assertEquals(generation + 1, field("alignmentGeneration").getInt(service))
                assertEquals(View.INVISIBLE, guide.visibility)
                assertTrue("Hiding the eye must cancel computation, not just its callback", cancellation.isCancelled)
                callHideGuide()
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
