package com.idvb.android.alignment

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.overlay.OverlayWindowManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic inputs are explicit; attached-window and Bitmap behavior run on Android. */
class AutoMapOpenNegativeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private val fullRegion = floatArrayOf(0f, 0f, 1f, 1f)

    private fun privateContext(): Context {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "auto-map-open-negative-" + UUID.randomUUID())
        check(root.mkdirs())
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
        }
    }

    private fun frame(width: Int = 640, height: Int = 480, rearranged: Boolean = false): Bitmap {
        val colors = intArrayOf(0xff263a5e.toInt(), 0xffdbc472.toInt(),
            0xff822f35.toInt(), 0xff6cafab.toInt())
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sourceX = if (rearranged) (x + width / 2) % width else x
            if (sourceX % 29 <= 2 || y % 37 == 0) Color.WHITE
            else colors[(sourceX * 4 / width).coerceAtMost(3)]
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sampled(frame: Bitmap): IntArray {
        val executor = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        var output: Result<IntArray>? = null
        var owned: Bitmap? = null
        try {
            instrumentation.runOnMainSync {
                val sampler = AutoMapOpenFrameSampler(main, executor) { _, callback ->
                    owned = frame.copy(Bitmap.Config.ARGB_8888, false)
                    callback(Result.success(checkNotNull(owned)));
                    {}
                }
                sampler.sample(Rect(0, 0, frame.width, frame.height), { true }, { false }) {
                    output = it; done.countDown()
                }
            }
            assertTrue("Android Bitmap sampling must finish", done.await(3, TimeUnit.SECONDS))
            assertTrue("The sampler owns and recycles its captured frame", checkNotNull(owned).isRecycled)
            return checkNotNull(output).getOrThrow()
        } finally { executor.shutdownNow() }
    }

    private fun fixtureService(context: Context): com.idvb.android.overlay.OverlayService {
        val service = com.idvb.android.overlay.OverlayService()
        android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
        return service
    }

    private fun serviceField(service: com.idvb.android.overlay.OverlayService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setServiceField(service: com.idvb.android.overlay.OverlayService, name: String, value: Any?) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun closeFixtureService(service: com.idvb.android.overlay.OverlayService) {
        (serviceField(service, "mainHandler") as Handler).removeCallbacksAndMessages(null)
        (serviceField(service, "accessibilityCaptureExecutor") as java.util.concurrent.ExecutorService).shutdownNow()
        (serviceField(service, "recognitionExecutor") as com.idvb.android.recognize.RecognitionExecutor).close()
        val diagnosticDelegate = service.javaClass.declaredFields.firstOrNull {
            it.name.startsWith("autoDiagnostics") && Lazy::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }?.get(service) as? Lazy<*>
        if (diagnosticDelegate?.isInitialized() == true) (diagnosticDelegate.value as AutoMapOpenDiagnostics).close()
    }

    @Test fun serviceRejectsOldCompletionAfterSameCycleResumeAndContextReplacement() {
        val source = frame()
        val reference = try { AutoMapOpenDetector.signature(AutoMapOpenReferenceStore.samplePixels(source)) }
            finally { source.recycle() }
        instrumentation.runOnMainSync {
            val service = fixtureService(privateContext())
            try {
                val detector = AutoMapOpenDetector(reference, AutoMapOpenConfig(retryCooldownMs = 0L))
                val firstTime = android.os.SystemClock.uptimeMillis()
                detector.observe(reference, firstTime)
                detector.observe(reference, firstTime)
                val oldCycle = checkNotNull(detector.alignmentStarted(firstTime))
                setServiceField(service, "autoDetector", detector)
                setServiceField(service, "autoContext", "fixture-map:floor-1")
                setServiceField(service, "lastAlignmentRequestId", "old-request")
                detector.alignmentInterrupted()
                val resumedAt = android.os.SystemClock.uptimeMillis()
                detector.observe(reference, resumedAt)
                val newCycle = checkNotNull(detector.alignmentStarted(resumedAt))
                assertEquals("Pausing does not require the game to physically close", oldCycle, newCycle)
                setServiceField(service, "lastAlignmentRequestId", "new-request")
                val finish = service.javaClass.getDeclaredMethod("finishAutoAlignment", AutoMapOpenDetector::class.java,
                    String::class.java, java.lang.Long.TYPE, String::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                finish.invoke(service, detector, "fixture-map:floor-1", oldCycle, "old-request", true)
                assertTrue("A cancelled request cannot finish the resumed request in the same cycle", detector.alignmentInFlight)
                assertFalse(detector.alignedInCycle)
                finish.invoke(service, detector, "fixture-map:floor-1", newCycle, "new-request", true)
                assertFalse(detector.alignmentInFlight)
                assertTrue(detector.alignedInCycle)

                val replacement = AutoMapOpenDetector(reference, AutoMapOpenConfig(retryCooldownMs = 0L))
                val replacedAt = android.os.SystemClock.uptimeMillis()
                replacement.observe(reference, replacedAt); replacement.observe(reference, replacedAt)
                val replacementCycle = checkNotNull(replacement.alignmentStarted(replacedAt))
                setServiceField(service, "autoDetector", replacement)
                setServiceField(service, "autoContext", "fixture-map:floor-2")
                setServiceField(service, "lastAlignmentRequestId", "replacement-request")
                finish.invoke(service, detector, "fixture-map:floor-2", replacementCycle, "replacement-request", true)
                assertTrue("A replaced detector instance cannot consume the current completion", replacement.alignmentInFlight)
                assertFalse(replacement.alignedInCycle)
                finish.invoke(service, replacement, "fixture-map:floor-1", replacementCycle, "replacement-request", true)
                assertTrue("A changed map/floor context cannot consume the current completion", replacement.alignmentInFlight)
                assertFalse(replacement.alignedInCycle)
                finish.invoke(service, replacement, "fixture-map:floor-2", replacementCycle, "replacement-request", true)
                assertFalse(replacement.alignmentInFlight)
                assertTrue(replacement.alignedInCycle)
            } finally { closeFixtureService(service) }
        }
    }

    @Test fun manualOpenCancelsPendingAutomaticOwnerEvenWhenScanningBlocksNewAlignment() {
        val source = frame()
        val reference = try { AutoMapOpenDetector.signature(AutoMapOpenReferenceStore.samplePixels(source)) }
            finally { source.recycle() }
        instrumentation.runOnMainSync {
            val service = fixtureService(privateContext())
            try {
                val detector = AutoMapOpenDetector(reference)
                val now = android.os.SystemClock.uptimeMillis()
                detector.observe(reference, now); detector.observe(reference, now)
                checkNotNull(detector.alignmentStarted(now))
                val cancellation = AlignmentCancellation()
                val aborts = AtomicInteger()
                val abort: () -> Unit = { aborts.incrementAndGet(); Unit }
                setServiceField(service, "autoDetector", detector)
                setServiceField(service, "autoGuideOwned", true)
                setServiceField(service, "guideVisible", true)
                setServiceField(service, "alignmentDisplayReady", false)
                setServiceField(service, "aligning", true)
                setServiceField(service, "alignmentCapturing", true)
                setServiceField(service, "scanning", true)
                setServiceField(service, "activeAlignmentCancellation", cancellation)
                setServiceField(service, "abortAlignmentCapture", abort)
                service.javaClass.getDeclaredMethod("openGuide").apply { isAccessible = true }.invoke(service)
                assertTrue("A manual eye action must cancel the old calculation", cancellation.isCancelled)
                assertEquals("The pending screenshot must actually be aborted", 1, aborts.get())
                assertEquals(false, serviceField(service, "autoGuideOwned"))
                assertEquals(false, serviceField(service, "guideVisible"))
                assertEquals(false, serviceField(service, "aligning"))
                assertEquals(false, serviceField(service, "alignmentCapturing"))
                assertTrue("Manual ownership must suppress this physical opening", detector.manualSuppressed)
                assertFalse(detector.alignmentInFlight)
                assertFalse(detector.shouldAttemptAlignment(now))
            } finally { closeFixtureService(service) }
        }
    }

    @Test fun detectorCaptureOwnsItsSuccessorUntilCompletion() {
        instrumentation.runOnMainSync {
            val queued = LinkedHashMap<Runnable, Long>()
            var inFlight = false
            var polls = 0
            val loop = AutoMapOpenPollLoop(
                post = { task, delay -> queued[task] = delay }, remove = { queued.remove(it); Unit },
                canSchedule = { true }, canRecover = { true }, sampleInFlight = { inFlight },
                poll = { polls++; inFlight = true },
            )
            loop.schedule(0L)
            val tick = queued.keys.single()
            queued.remove(tick)
            tick.run()
            assertEquals(1, polls)
            assertFalse("The current capture owns successor scheduling", loop.pending)
            assertTrue(queued.isEmpty())
            inFlight = false
            loop.schedule(117L)
            assertTrue(loop.pending)
            assertEquals(117L, queued.values.single())
            loop.stop()
            assertTrue(queued.isEmpty())
        }
    }

    @Test fun floorChangeCancelsDetectorCaptureAndInvalidatesItsOldCallbackOwner() {
        instrumentation.runOnMainSync {
            val service = fixtureService(privateContext())
            try {
                val aborts = AtomicInteger()
                setServiceField(service, "autoCaptureGeneration", 71)
                setServiceField(service, "autoCaptureBusy", true)
                setServiceField(service, "autoPauseReason", "map-or-floor-changed")
                setServiceField(service, "abortAutoCapture", { aborts.incrementAndGet(); Unit })
                service.javaClass.getDeclaredMethod("refreshGuideSelection").apply { isAccessible = true }.invoke(service)
                assertEquals("Floor change must actually abort the detector capture", 1, aborts.get())
                assertTrue("Old callbacks no longer belong to this request", (serviceField(service, "autoCaptureGeneration") as Int) > 71)
                assertEquals(false, serviceField(service, "autoCaptureBusy"))
                assertNull(serviceField(service, "abortAutoCapture"))
                // Cancellation has one owner even if the selection is refreshed again.
                service.javaClass.getDeclaredMethod("refreshGuideSelection").apply { isAccessible = true }.invoke(service)
                assertEquals(1, aborts.get())
            } finally { closeFixtureService(service) }
        }
    }

    @Test fun closedDiagnosticsIgnoreLateCompletionFramesAndCaptureEvents() {
        val context = privateContext()
        val source = frame()
        val reference = try {
            AutoMapOpenReferenceStore(context).save(source, fullRegion, "com.idvb.synthetic.game")
        } finally { source.recycle() }
        val signature = AutoMapOpenDetector.signature(reference.signaturePixels)
        val detector = AutoMapOpenDetector(signature)
        val now = android.os.SystemClock.uptimeMillis()
        val observation = detector.observe(signature, now)
        val diagnostics = AutoMapOpenDiagnostics(context)
        val history = File(context.filesDir, "idvb/diagnostics")
        val before = if (history.exists()) history.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet() else emptySet()
        diagnostics.close()
        diagnostics.event("late-alignment-completion", reference, UUID.randomUUID().toString(), "late-request")
        diagnostics.captureEvent(AlignmentLogEvent("capture.accessibility.attempt", "captured"))
        diagnostics.frame(reference, reference.signaturePixels, observation, now, 1.0)
        diagnostics.close()
        diagnostics.event("later-completion", reference, UUID.randomUUID().toString(), "later-request")
        val after = if (history.exists()) history.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet() else emptySet()
        assertEquals("A closed diagnostic writer must not publish late evidence", before, after)
    }

    @Test fun persistedReferenceAndLiveSamplerUseTheSameActualBitmapSampling() {
        val source = frame(641, 479)
        try {
            val context = privateContext()
            val store = AutoMapOpenReferenceStore(context)
            val reference = store.save(source, fullRegion, "com.idvb.synthetic.game")
            assertFalse("Saving borrows the full capture", source.isRecycled)
            val live = sampled(source)
            assertArrayEquals("Reference and monitoring must use identical downsampling", reference.signaturePixels, live)
            val loaded = checkNotNull(store.load(true))
            assertArrayEquals(live, loaded.signaturePixels)
            assertEquals(641, loaded.screenWidth)
            assertEquals(479, loaded.screenHeight)
            assertNull("A landscape reference must not become a portrait fallback", store.load(false))
        } finally { source.recycle() }
    }

    @Test fun equalColorHistogramWithRearrangedPixelsCannotConfirmMapOpen() {
        val original = frame()
        val rearranged = frame(rearranged = true)
        try {
            val referencePixels = sampled(original)
            val wrongPixels = sampled(rearranged)
            assertArrayEquals("The adversarial frame preserves its sampled color histogram",
                referencePixels.sortedArray(), wrongPixels.sortedArray())
            val reference = AutoMapOpenDetector.signature(referencePixels)
            assertTrue(AutoMapOpenDetector.isUsableReference(reference))
            val detector = AutoMapOpenDetector(reference)
            val wrong = AutoMapOpenDetector.signature(wrongPixels)
            repeat(6) { index ->
                val observation = detector.observe(wrong, index * 500L)
                assertEquals(AutoMapOpenTransition.NONE, observation.transition)
                assertFalse("Color resemblance without spatial agreement must not open", observation.isOpen)
                assertFalse(detector.shouldAttemptAlignment(index * 500L))
            }
            assertTrue(AutoMapOpenDetector.compare(reference, wrong).score < detector.config.openThreshold)
        } finally { original.recycle(); rearranged.recycle() }
    }

    @Test fun consecutiveFramesManualCloseUnknownFramesAndOldCompletionCannotReopenGuide() {
        val source = frame()
        val wrongSource = frame(rearranged = true)
        try {
            val ready = AutoMapOpenDetector.signature(sampled(source))
            val closed = AutoMapOpenDetector.signature(sampled(wrongSource))
            val detector = AutoMapOpenDetector(ready)
            assertEquals(AutoMapOpenTransition.NONE, detector.observe(ready, 0).transition)
            assertFalse(detector.observe(null, 500).isOpen)
            assertFalse(detector.observe(ready, 1_000).isOpen)
            assertEquals(AutoMapOpenTransition.OPENED, detector.observe(ready, 1_500).transition)
            val oldCycle = checkNotNull(detector.alignmentStarted(1_500))
            assertNull("Only one alignment can be in flight", detector.alignmentStarted(1_600))
            detector.manualClose()
            repeat(4) { index ->
                val observation = detector.observe(ready, 2_000 + index * 500L)
                assertTrue(observation.manualSuppressed)
                assertFalse(detector.shouldAttemptAlignment(2_000 + index * 500L))
            }
            assertFalse("A late result cannot undo a manual eye close", detector.alignmentFinished(true, 4_000, oldCycle))
            assertTrue("An unavailable capture is not a closed map", detector.observe(null, 4_500).isOpen)
            assertTrue(detector.manualSuppressed)
            assertTrue("A single mismatched animation frame cannot close", detector.observe(closed, 5_000).isOpen)
            assertEquals(AutoMapOpenTransition.CLOSED, detector.observe(closed, 5_500).transition)
            assertFalse(detector.manualSuppressed)
            detector.observe(ready, 6_000)
            assertEquals(AutoMapOpenTransition.OPENED, detector.observe(ready, 6_500).transition)
            val currentCycle = checkNotNull(detector.alignmentStarted(6_500))
            assertTrue(currentCycle > oldCycle)
            assertFalse("The previous physical opening cannot commit to a new one", detector.alignmentFinished(true, 6_600, oldCycle))
            assertTrue(detector.alignmentInFlight)
            assertFalse(detector.alignedInCycle)
            assertTrue(detector.alignmentFinished(true, 6_700, currentCycle))
            assertTrue(detector.alignedInCycle)
        } finally { source.recycle(); wrongSource.recycle() }
    }

    @Test fun corruptDirectionDimensionsSignatureAndPngFailClosedInPrivateStorage() {
        val source = frame()
        try {
            val context = privateContext()
            val store = AutoMapOpenReferenceStore(context)
            store.save(source, fullRegion, "com.idvb.synthetic.game")
            val metadata = File(context.filesDir, "idvb/auto-map-open/reference-landscape.json")
            val png = File(context.filesDir, "idvb/auto-map-open/reference-landscape.png")
            val originalJson = metadata.readText(Charsets.UTF_8)
            val originalPng = png.readBytes()
            assertNotNull(store.load(true))
            fun corrupt(change: (JSONObject) -> Unit) {
                val json = JSONObject(originalJson)
                change(json)
                metadata.writeText(json.toString(), Charsets.UTF_8)
                assertNull("Corrupt metadata must keep automatic detection stopped", store.load(true))
            }
            corrupt { it.put("landscape", false) }
            corrupt { it.put("screenWidth", source.width + 1) }
            corrupt { it.getJSONArray("signaturePixels").put(0, Color.BLACK) }
            corrupt { it.getJSONArray("region").put(2, 1.2) }
            metadata.writeText("{unfinished", Charsets.UTF_8)
            assertNull(store.load(true))
            metadata.writeText(originalJson, Charsets.UTF_8)
            png.writeBytes(originalPng.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertNull("Mismatched PNG hash must fail closed", store.load(true))
            png.writeBytes(originalPng)
            assertNotNull("A valid pair still loads after restoring this fixture", store.load(true))
            assertTrue("Evidence is confined to this test's private directory", metadata.canonicalPath.startsWith(context.filesDir.canonicalPath))
        } finally { source.recycle() }
    }

    @Test fun attachedGuideBlocksCaptureWithoutHidingOrChangingAnyOverlay() {
        val base = instrumentation.targetContext
        assertTrue("Grant overlay permission only to the isolated verification App", Settings.canDrawOverlays(base))
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        lateinit var manager: OverlayWindowManager
        lateinit var guide: View
        lateinit var region: Rect
        var opacity = 0f
        var managerCreated = false
        try {
            instrumentation.runOnMainSync {
                val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                val context = if (Build.VERSION.SDK_INT >= 30)
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
                guide = View(context).apply { setBackgroundColor(Color.GREEN) }
                manager = OverlayWindowManager(context).apply { x = 40; y = 120; width = 160; height = 120 }
                manager.add(guide, locked = true)
                managerCreated = true
                opacity = (guide.layoutParams as WindowManager.LayoutParams).alpha
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("The fixture must be a genuinely attached window", guide.isAttachedToWindow)
                val location = IntArray(2).also(guide::getLocationOnScreen)
                assertTrue(guide.width > 0 && guide.height > 0)
                region = Rect(location[0], location[1], location[0] + guide.width, location[1] + guide.height)
                assertTrue(AutoMapOpenOcclusion.overlaps(RectF(region), OverlayWindowManager.visibleScreenBounds()))
                assertFalse(AutoMapOpenOcclusion.overlaps(RectF(region), OverlayWindowManager.visibleScreenBounds(setOf(manager))))
                val sampler = AutoMapOpenFrameSampler(main, executor) { _, callback ->
                    calls.incrementAndGet()
                    callback(Result.success(frame(region.width(), region.height())));
                    {}
                }
                repeat(6) {
                    var completed = false
                    sampler.sample(region, { true }, {
                        AutoMapOpenOcclusion.overlaps(RectF(region), OverlayWindowManager.visibleScreenBounds())
                    }) { result -> assertTrue(result.isFailure); completed = true }
                    assertTrue(completed)
                    assertEquals("Blocked monitoring cannot repeatedly capture its own guide", 0, calls.get())
                    assertEquals(View.VISIBLE, guide.visibility)
                    assertEquals(1f, guide.alpha, 0f)
                    assertEquals(opacity, (guide.layoutParams as WindowManager.LayoutParams).alpha, 0f)
                }
                manager.x += region.width() + 60
                manager.update()
            }
            instrumentation.waitForIdleSync()
            val done = CountDownLatch(1)
            var output: Result<IntArray>? = null
            instrumentation.runOnMainSync {
                assertFalse("Moving the fixture clears the ROI without hiding it",
                    AutoMapOpenOcclusion.overlaps(RectF(region), OverlayWindowManager.visibleScreenBounds()))
                val sampler = AutoMapOpenFrameSampler(main, executor) { _, callback ->
                    calls.incrementAndGet()
                    callback(Result.success(frame(region.width(), region.height())));
                    {}
                }
                sampler.sample(region, { true }, {
                    AutoMapOpenOcclusion.overlaps(RectF(region), OverlayWindowManager.visibleScreenBounds())
                }) { output = it; done.countDown() }
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(checkNotNull(output).isSuccess)
            assertEquals(1, calls.get())
            instrumentation.runOnMainSync {
                assertTrue(guide.isShown)
                assertEquals(1f, guide.alpha, 0f)
                assertEquals(opacity, (guide.layoutParams as WindowManager.LayoutParams).alpha, 0f)
            }
        } finally {
            instrumentation.runOnMainSync { if (managerCreated) manager.remove() }
            executor.shutdownNow()
        }
    }

    @Test fun wrapContentNotificationIsIncludedUsingItsActualAttachedBounds() {
        val base = instrumentation.targetContext
        assertTrue(Settings.canDrawOverlays(base))
        lateinit var manager: OverlayWindowManager
        lateinit var notification: android.widget.TextView
        var created = false
        try {
            instrumentation.runOnMainSync {
                val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                val context = if (Build.VERSION.SDK_INT >= 30)
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
                notification = android.widget.TextView(context).apply {
                    text = "Synthetic notification covering the reference ROI"
                    textSize = 22f
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.MAGENTA)
                }
                manager = OverlayWindowManager(context).apply {
                    x = 35; y = 140; width = 200; height = WindowManager.LayoutParams.WRAP_CONTENT
                }
                manager.add(notification, locked = true)
                created = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(notification.isAttachedToWindow && notification.isShown)
                assertTrue(notification.width > 0 && notification.height > 0)
                val location = IntArray(2).also(notification::getLocationOnScreen)
                val region = RectF(location[0].toFloat(), location[1].toFloat(),
                    (location[0] + notification.width).toFloat(), (location[1] + notification.height).toFloat())
                assertTrue("WRAP_CONTENT=-2 cannot make a visible notification disappear from the occlusion guard",
                    AutoMapOpenOcclusion.overlaps(region, OverlayWindowManager.visibleScreenBounds()))
                assertFalse(AutoMapOpenOcclusion.overlaps(region, OverlayWindowManager.visibleScreenBounds(setOf(manager))))
            }
        } finally { instrumentation.runOnMainSync { if (created) manager.remove() } }
    }

    @Test fun actualOverlayEventMarkerPreservesGameWhileMainActivityPausesDetection() {
        val base = instrumentation.targetContext
        assertTrue(Settings.canDrawOverlays(base))
        val service = com.idvb.android.recognize.AccessibilityScreenCaptureService()
        val previousPackage = com.idvb.android.recognize.AccessibilityScreenCaptureService.foregroundPackage
        lateinit var manager: OverlayWindowManager
        lateinit var surface: View
        var created = false
        fun event(packageName: String?, className: String): android.view.accessibility.AccessibilityEvent =
            android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                this.packageName = packageName
                this.className = className
            }
        try {
            instrumentation.runOnMainSync {
                val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                val context = if (Build.VERSION.SDK_INT >= 30)
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
                surface = View(context).apply { setBackgroundColor(Color.CYAN) }
                manager = OverlayWindowManager(context).apply { x = 30; y = 100; width = 120; height = 100 }
                manager.add(surface, locked = true)
                created = true
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(surface.isAttachedToWindow)
                val game = event("com.idvb.synthetic.game", "com.idvb.synthetic.GameActivity")
                val overlay = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                val app = event(base.packageName, "com.idvb.android.MainActivity")
                try {
                    service.onAccessibilityEvent(game)
                    assertEquals("com.idvb.synthetic.game", com.idvb.android.recognize.AccessibilityScreenCaptureService.foregroundPackage)
                    // Invoke the actual attached View initialization: a default View reports
                    // android.view.View unless OverlayWindowManager installed its delegate.
                    surface.onInitializeAccessibilityEvent(overlay)
                    assertEquals("com.idvb.android.overlay.Surface", overlay.className.toString())
                    assertEquals(base.packageName, overlay.packageName.toString())
                    service.onAccessibilityEvent(overlay)
                    assertEquals("An IDVB surface is not a foreground-app switch", "com.idvb.synthetic.game",
                        com.idvb.android.recognize.AccessibilityScreenCaptureService.foregroundPackage)
                    service.onAccessibilityEvent(app)
                    assertEquals("Opening the actual IDVB Activity must pause game detection", base.packageName,
                        com.idvb.android.recognize.AccessibilityScreenCaptureService.foregroundPackage)
                } finally { game.recycle(); overlay.recycle(); app.recycle() }
            }
        } finally {
            instrumentation.runOnMainSync {
                if (created) manager.remove()
                val restore = event(previousPackage, "previous.foreground.Activity")
                try { service.onAccessibilityEvent(restore) } finally { restore.recycle() }
            }
        }
    }

    @Test fun realAccessibilityCapturesExposeIntervalsCopyCropAndSamplingCosts() {
        val base = instrumentation.targetContext
        assertEquals("Device capture test must run in the isolated App", "com.idvb.android.verification", base.packageName)
        val consent = base.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val previousConsent = consent.all["accepted_revision"]
        check(previousConsent == null || previousConsent is Int)
        val executor = Executors.newSingleThreadExecutor()
        val evidence = File(base.cacheDir, "auto-map-open-device-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val requestEvidence = org.json.JSONArray()
        val allEvents = java.util.concurrent.CopyOnWriteArrayList<AlignmentLogEvent>()
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val output = JSONObject().apply {
            put("schemaVersion", 1)
            put("source", "actual AccessibilityScreenCaptureService.capture on the current device display")
            put("scope", "Screenshot API intervals and conversion/crop/sample latency; no game or power-consumption acceptance")
            put("requestCount", 4)
            put("requests", requestEvidence)
        }
        var activeCancel: (() -> Unit)? = null
        try {
            instrumentation.runOnMainSync {
                check(consent.edit().putInt("accepted_revision", com.idvb.android.UsageConsent.REVISION).commit())
            }
            val available = CountDownLatch(1)
            val availabilityDeadline = android.os.SystemClock.uptimeMillis() + 5_000L
            val checkAvailability = object : Runnable {
                override fun run() {
                    if (com.idvb.android.recognize.AccessibilityScreenCaptureService.available) available.countDown()
                    else if (android.os.SystemClock.uptimeMillis() < availabilityDeadline) main.postDelayed(this, 50L)
                }
            }
            main.post(checkAvailability)
            val connected = available.await(5_200L, TimeUnit.MILLISECONDS)
            main.removeCallbacks(checkAvailability)
            output.put("serviceConnected", connected)
            assertTrue("The explicitly enabled isolated accessibility service must connect within five seconds", connected)
            repeat(4) { requestIndex ->
                val done = CountDownLatch(1)
                val events = java.util.concurrent.CopyOnWriteArrayList<AlignmentLogEvent>()
                val request = JSONObject().apply {
                    put("index", requestIndex)
                    put("roi", org.json.JSONArray(listOf(0, 0, 160, 120)))
                }
                requestEvidence.put(request)
                val requestStarted = System.nanoTime()
                instrumentation.runOnMainSync {
                    activeCancel = com.idvb.android.recognize.AccessibilityScreenCaptureService.capture(
                        Rect(0, 0, 160, 120), executor, { result ->
                            val returnedAt = System.nanoTime()
                            val bitmap = result.getOrNull()
                            val validation = runCatching {
                                val frame = checkNotNull(bitmap) { result.exceptionOrNull()?.toString() ?: "empty capture" }
                                require(frame.width == 160 && frame.height == 120) { "Screenshot crop changed dimensions" }
                                val sampleStarted = System.nanoTime()
                                val pixels = AutoMapOpenReferenceStore.samplePixels(frame)
                                val sampleEnded = System.nanoTime()
                                require(pixels.size == AutoMapOpenDetector.PIXELS)
                                request.put("width", frame.width); request.put("height", frame.height)
                                request.put("samplePixels", pixels.size)
                                request.put("samplingMs", (sampleEnded - sampleStarted) / 1e6)
                                request.put("captureWaitApiCopyCropMs", (returnedAt - requestStarted) / 1e6)
                                events.lastOrNull { it.stage == "capture.accessibility.attempt" && it.detail == "captured" }?.let {
                                    request.put("apiMs", (it.durationNanos ?: 0L) / 1e6)
                                    request.put("conversionAndCropMs", (returnedAt - it.timestampNanos).coerceAtLeast(0L) / 1e6)
                                }
                            }
                            try {
                                request.put("success", validation.isSuccess)
                                validation.exceptionOrNull()?.let {
                                    request.put("failure", it.toString()); failures.add(it.toString())
                                }
                            } finally {
                                bitmap?.recycle()
                                request.put("bitmapRecycled", bitmap?.isRecycled ?: true)
                                done.countDown()
                            }
                        }, AlignmentLogSink { event -> events.add(event); allEvents.add(event) })
                }
                if (!done.await(5, TimeUnit.SECONDS)) {
                    instrumentation.runOnMainSync { activeCancel?.invoke() }
                    fail("Actual screenshot request did not finish: " + requestIndex)
                }
                request.put("events", org.json.JSONArray().apply {
                    events.forEach { event -> put(JSONObject().apply {
                        put("stage", event.stage); put("detail", event.detail)
                        put("timestampNanos", event.timestampNanos)
                        put("durationNanos", event.durationNanos ?: JSONObject.NULL)
                        put("measurements", JSONObject(event.measurements))
                        if (event.stage == "capture.accessibility.attempt" && event.durationNanos != null)
                            put("inferredAttemptStartNanos", event.timestampNanos - checkNotNull(event.durationNanos))
                    }) }
                })
                activeCancel = null
            }
            val attempts = allEvents.filter { it.stage == "capture.accessibility.attempt" }
            val starts = attempts.map { event -> checkNotNull(event.measurements["attemptStartUptimeMs"]) {
                "Attempt diagnostics must include the actual screenshot interval reservation"
            }.toLong() }
            val intervals = starts.zipWithNext { a, b -> b - a }
            output.put("attemptIntervalsMs", org.json.JSONArray(intervals))
            output.put("attemptCount", attempts.size)
            output.put("retryCount", allEvents.count { it.stage == "capture.accessibility.retry-scheduled" })
            assertTrue("Actual capture failures are retained in private JSON: " + failures.joinToString(), failures.isEmpty())
            assertEquals("All four real captures must produce an attempt log", 4, attempts.size)
            assertTrue("Any platform error must remain visible", attempts.all { it.detail == "captured" })
            assertEquals("The shared scheduler should avoid rate-limit retries in this sequential path", 0,
                allEvents.count { it.stage == "capture.accessibility.retry-scheduled" })
            assertEquals(3, intervals.size)
            assertTrue("Screenshot attempts must be spaced by at least 300ms: " + intervals,
                intervals.all { it >= 300L })
        } finally {
            instrumentation.runOnMainSync {
                activeCancel?.invoke()
                val editor = consent.edit()
                if (previousConsent is Int) editor.putInt("accepted_revision", previousConsent)
                else editor.remove("accepted_revision")
                check(editor.commit())
            }
            File(evidence, "capture-timing.json").writeText(output.toString(2), Charsets.UTF_8)
            android.util.Log.i("IDVB-AutoMap-Test", "Private screenshot timing evidence: " + File(evidence, "capture-timing.json").absolutePath)
            executor.shutdownNow()
        }
    }

    @Test fun cancelledCaptureRecyclesLateBitmapAndCannotCompleteTwice() {
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = AtomicInteger()
        val aborts = AtomicInteger()
        var pending: ((Result<Bitmap>) -> Unit)? = null
        var output: Result<IntArray>? = null
        lateinit var late: Bitmap
        try {
            instrumentation.runOnMainSync {
                val sampler = AutoMapOpenFrameSampler(main, executor) { _, callback ->
                    pending = callback;
                    val abort: () -> Unit = { aborts.incrementAndGet(); Unit }
                    abort
                }
                val cancel = sampler.sample(Rect(0, 0, 160, 120), { true }, { false }) {
                    output = it; callbacks.incrementAndGet()
                }
                cancel()
                assertTrue(checkNotNull(output).exceptionOrNull() is java.util.concurrent.CancellationException)
                assertEquals(1, callbacks.get())
                late = frame(160, 120)
                checkNotNull(pending).invoke(Result.success(late))
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, aborts.get())
            assertEquals(1, callbacks.get())
            assertTrue("A binder callback arriving after cancellation must release its Bitmap", late.isRecycled)
        } finally { executor.shutdownNow() }
    }

    @Test fun changedDimensionsAndOcclusionDuringCaptureDiscardTheFrame() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (mode in listOf("dimensions", "late-occlusion", "old-generation")) {
                val done = CountDownLatch(1)
                var pending: ((Result<Bitmap>) -> Unit)? = null
                var occluded = false
                var current = true
                var output: Result<IntArray>? = null
                lateinit var captured: Bitmap
                instrumentation.runOnMainSync {
                    val sampler = AutoMapOpenFrameSampler(main, executor) { _, callback -> pending = callback; {} }
                    sampler.sample(Rect(0, 0, 160, 120), { current }, { occluded }) {
                        output = it; done.countDown()
                    }
                    if (mode == "late-occlusion") occluded = true
                    if (mode == "old-generation") current = false
                    captured = frame(if (mode == "dimensions") 161 else 160, 120)
                    checkNotNull(pending).invoke(Result.success(captured))
                }
                assertTrue(mode, done.await(3, TimeUnit.SECONDS))
                assertTrue(mode, checkNotNull(output).isFailure)
                assertTrue(mode + " must release the unusable captured frame", captured.isRecycled)
            }
        } finally { executor.shutdownNow() }
    }
}








