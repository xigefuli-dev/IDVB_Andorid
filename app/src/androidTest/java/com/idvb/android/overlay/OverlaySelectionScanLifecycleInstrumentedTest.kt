package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.data.MapIdentitySource
import com.idvb.android.idvm.*
import com.idvb.android.recognize.*
import com.idvb.android.recognize.side.SparseGateRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real service consumer, recognition executor, main Handler and overlay roots.
 * Reliable results are constructed to isolate lifecycle consumption from recognition quality. */
@RunWith(AndroidJUnit4::class)
class OverlaySelectionScanLifecycleInstrumentedTest {
    @Test fun nextFloorCancelsPendingScanBeforeSelectionChanges() = selection("nextFloor", 0, 1, false)
    @Test fun previousFloorCancelsPendingScanBeforeSelectionChanges() = selection("previousFloor", 1, 0, false)
    @Test fun nextVariantCancelsPendingScanBeforeSelectionChanges() = selection("nextVariant", 0, 0, true)

    private fun selection(action: String, initial: Int, expected: Int, variant: Boolean) {
        Fixture(initial).use { f ->
            f.startPending()
            f.main {
                f.set("aligning", true); f.set("alignmentCapturing", true)
                val cancellation = AlignmentCancellation()
                val aborted = AtomicInteger()
                f.set("activeAlignmentCancellation", cancellation)
                f.set("abortAlignmentCapture", { aborted.incrementAndGet(); Unit })
                f.balls.setCaptureHidden(true)
                f.call(action)
                assertTrue(cancellation.isCancelled)
                assertEquals(1, aborted.get())
                assertNull(f.get("abortAlignmentCapture"))
                assertFalse(f.get("alignmentCapturing") as Boolean)
                assertFalse(f.get("aligning") as Boolean)
            }
            f.releasePending()
            f.main {
                assertEquals(if (variant) f.b.id else f.a.id, (f.get("currentMap") as MapRecord).id)
                assertEquals(expected, f.get("floorIndex"))
                assertEquals(if (variant) f.b.id else f.a.id, AppServices.prefs.lastMapId)
                assertEquals(if (expected == 0) "1f" else "2f", AppServices.prefs.lastFloorKey)
                assertEquals(MapIdentitySource.MANUAL_UNVERIFIED, AppServices.prefs.lastMapIdentitySource)
                f.assertCancelled()
            }
            f.assertControlsDraw()
        }
    }

    @Test fun pendingForegroundDebugCannotOpenPickerAndManualChoiceInvalidatesItsCallback() {
        Fixture().use { f ->
            f.startPending()
            f.main {
                AppServices.prefs.debugMode = true
                AppServices.prefs.manualMapSelectionEnabled = true
                f.call("runForegroundScan")
                assertNull(f.get("candidateView")); assertNull(f.get("candidateResult"))
                assertEquals(f.a, f.get("currentMap"))
                assertEquals(41, f.get("scanGeneration"))
                val manual = RecognitionCandidate(f.b, "2f", CandidateDisposition.NEEDS_VERIFICATION, evidenceLabel = "manual")
                assertEquals(true, f.lock(manual))
                assertEquals(f.b, f.get("currentMap"))
                assertEquals(1, f.get("floorIndex"))
                assertEquals(MapIdentitySource.MANUAL_UNVERIFIED, AppServices.prefs.lastMapIdentitySource)
            }
            f.releasePending()
            f.main { assertEquals(f.b, f.get("currentMap")); assertEquals(1, f.get("floorIndex")); f.assertCancelled() }
            f.assertControlsDraw()
        }
    }

    @Test fun unchangedSelectionConsumesReliableLateResultAndReportsLocked() {
        Fixture().use { f ->
            f.startPending(); f.releasePending()
            f.main {
                assertEquals(f.a, f.get("currentMap"))
                assertEquals(MapIdentitySource.STRUCTURE_VERIFIED, AppServices.prefs.lastMapIdentitySource)
                assertEquals(ScanOutcome.LOCKED.label, f.progress.contentDescription)
                assertFalse(f.get("scanning") as Boolean)
                assertEquals(true, f.balls.identityVerified)
            }
        }
    }

    @Test fun staleCandidateMapOrFloorCannotLockOrReportLocked() {
        Fixture().use { f ->
            f.main {
                for (changed in listOf(f.catalog.copy(maps = listOf(f.b)),
                    f.catalog.copy(maps = listOf(f.a.copy(floors = emptyList()), f.b)))) {
                    AppServices.repository.saveCatalog(changed)
                    assertEquals(false, f.lock(f.candidate()))
                    assertEquals(f.a, f.get("currentMap"))
                    assertEquals(41, f.get("scanGeneration"))
                    f.call("handleScanResult", f.result, RecognitionResult::class.java)
                    assertEquals(ScanOutcome.NO_RESULT.label, f.progress.contentDescription)
                    assertNull(f.get("candidateResult"))
                    assertEquals(MapIdentitySource.MANUAL_UNVERIFIED, AppServices.prefs.lastMapIdentitySource)
                }
            }
        }
    }

    @Test fun pendingCandidateFromAnotherActiveClassIsRejectedWithoutChangingIdentity() {
        Fixture().use { f ->
            f.main {
                AppServices.prefs.selectedMapClassId = f.otherClass
                assertEquals(false, f.lock(f.candidate()))
                assertEquals(f.a, f.get("currentMap"))
                assertEquals(f.a.id, AppServices.prefs.lastMapId)
                assertEquals(41, f.get("scanGeneration"))
                assertEquals(f.otherClass, AppServices.prefs.selectedMapClassId)
                assertEquals(MapIdentitySource.MANUAL_UNVERIFIED, AppServices.prefs.lastMapIdentitySource)
            }
        }
    }

    @Test fun catalogReplacementInvalidatesProductionAlignmentPredicateAndPendingRenderBarrier() {
        Fixture().use { f ->
            val finished = CountDownLatch(1)
            val accepted = AtomicBoolean(true)
            var barrier: OverlayCaptureFrameBarrier? = null
            try {
                f.main {
                    assertTrue(f.catalogCurrent())
                    f.balls.setCaptureHidden(true)
                    barrier = OverlayCaptureFrameBarrier(f.handler, listOf("controls" to f.balls),
                        { f.catalogCurrent() }, AlignmentLogSink.NONE) { ready -> accepted.set(ready); finished.countDown() }
                    barrier!!.start()
                    // Same immutable map/floor value, but a replaced catalog still invalidates this request.
                    AppServices.repository.saveCatalog(f.catalog.copy(maps = f.catalog.maps.toList()))
                    assertFalse(f.catalogCurrent())
                }
                assertTrue("Actual barrier callback did not exit", finished.await(5, TimeUnit.SECONDS))
                assertFalse(accepted.get())
                f.main { assertNull(f.get("alignedGuideBounds")); assertEquals(f.a, f.get("currentMap")) }
            } finally {
                f.main { barrier?.cancel(); f.balls.setCaptureHidden(false) }
            }
            f.assertControlsDraw()
        }
    }

    private class Fixture(initial: Int = 0) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val base = instrumentation.targetContext
        private val prefs = base.getSharedPreferences("overlay", Context.MODE_PRIVATE)
        private val savedKeys = listOf("last_map_id", "last_floor_key", "last_map_identity_source",
            "selected_map_class_id", "debug_mode", "manual_map_selection_enabled")
        private val saved = savedKeys.associateWith { prefs.all[it] }
        private val originalCatalog = AppServices.repository.loadCatalog()
        private val originalState = OverlayState.state.value
        private val practiceField = OverlayService::class.java.getDeclaredField("practiceForeground").apply { isAccessible = true }
        private val originalPractice = practiceField.getBoolean(null)
        private val id = "selection-lifecycle-${UUID.randomUUID()}"
        val otherClass = "$id-other"
        private val floor1 = FloorRecord("1f", "1F", 1, "unused.png", 100, 100)
        private val floor2 = floor1.copy(key = "2f", displayName = "2F", sortOrder = 2)
        val a = MapRecord("$id-a", id, "A", "$id-a", 1, listOf(floor1, floor2))
        val b = a.copy(id = "$id-b", title = "B", sourceMapId = "$id-b")
        private val service = OverlayService()
        val catalog: MapCatalogDocument
        val balls: OverlayBallView
        val progress: ScanProgressView
        val handler: Handler
        private val windows = mutableListOf<OverlayWindowManager>()
        private val release = CountDownLatch(1)
        private val started = CountDownLatch(1)
        private val delivered = CountDownLatch(1)
        private var pending = false
        private val frame = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val result = RecognitionResult(frame, listOf(RecognitionCandidate(a,
            if (initial == 0) "1f" else "2f", CandidateDisposition.RELIABLE, evidenceLabel = "controlled verified result")),
            route = SparseGateRecognizer.ROUTE, sparseGateDiagnostics = SparseGateScanDiagnostics(
                0.0, 0.0, 0.0, 0.0, 1, 200, true, 1, true, emptyList()))

        init {
            check(base.packageName == "com.idvb.android.verification") { "Lifecycle catalog fixtures require the isolated verification APK" }
            val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
            val context = if (Build.VERSION.SDK_INT >= 30)
                display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display
            var createdBalls: OverlayBallView? = null
            var createdProgress: ScanProgressView? = null
            var createdHandler: Handler? = null
            main {
                practiceField.setBoolean(null, false)
                AppServices.repository.saveCatalog(MapCatalogDocument(classes = listOf(ClassRecord(id, "fixture"),
                    ClassRecord(otherClass, "other")), maps = listOf(a, b),
                    variantGroups = listOf(MapVariantGroupRecord("$id-group", id, 0, listOf(a.id, b.id)))))
                AppServices.prefs.selectedMapClassId = id
                AppServices.prefs.lastMapId = a.id
                AppServices.prefs.lastFloorKey = if (initial == 0) "1f" else "2f"
                AppServices.prefs.lastMapIdentitySource = MapIdentitySource.MANUAL_UNVERIFIED
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                set("overlayContext", context)
                for (name in listOf("window", "guideWindow", "candidateWindow", "scanProgressWindow")) {
                    val window = OverlayWindowManager(context)
                    set(name, window); windows.add(window)
                }
                createdBalls = OverlayBallView(context).apply { mapLocked = true; variantsAvailable = true }
                set("balls", createdBalls); set("currentMap", a); set("floorIndex", initial)
                set("scanGeneration", 41); set("scanning", true)
                OverlayState.update { it.copy(running = true, visible = true) }
                windows[0].apply { x = 10; y = 10; width = 640; height = 160; add(createdBalls!!, false) }
                call("showScanProgress", RectF(0f, 0f, 640f, 360f), RectF::class.java,
                    1280 to 720, Pair::class.java, 41, Integer.TYPE)
                createdProgress = get("scanProgressView") as ScanProgressView
                createdHandler = get("mainHandler") as Handler
            }
            catalog = AppServices.repository.loadCatalog()
            balls = requireNotNull(createdBalls); progress = requireNotNull(createdProgress); handler = requireNotNull(createdHandler)
        }

        fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
        private fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun get(name: String): Any? = field(name).get(service)
        fun set(name: String, value: Any?) = field(name).set(service, value)
        fun call(name: String) = OverlayService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
        fun call(name: String, value: Any?, type: Class<*>) = OverlayService::class.java.getDeclaredMethod(name, type)
            .apply { isAccessible = true }.invoke(service, value)
        private fun call(name: String, v1: Any, t1: Class<*>, v2: Any, t2: Class<*>, v3: Any, t3: Class<*>) =
            OverlayService::class.java.getDeclaredMethod(name, t1, t2, t3).apply { isAccessible = true }.invoke(service, v1, v2, v3)
        fun candidate() = result.candidates.single()
        fun lock(candidate: RecognitionCandidate): Any? = OverlayService::class.java.getDeclaredMethod("lockSelectedMap",
            RecognitionCandidate::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(service, candidate, true)
        fun catalogCurrent(): Boolean = OverlayService::class.java.getDeclaredMethod("isAlignmentCatalogCurrent",
            MapCatalogDocument::class.java, MapRecord::class.java, FloorRecord::class.java).apply { isAccessible = true }
            .invoke(service, catalog, a, floor1) as Boolean

        fun startPending() {
            pending = true
            val executor = get("recognitionExecutor") as RecognitionExecutor
            assertTrue(executor.execute {
                started.countDown()
                if (!release.await(10, TimeUnit.SECONDS)) return@execute
                handler.post {
                    try {
                        OverlayService::class.java.getDeclaredMethod("completeCapturedScan", Bitmap::class.java,
                            Integer.TYPE, MapCatalogDocument::class.java, String::class.java, RecognitionResult::class.java,
                            Throwable::class.java, Throwable::class.java).apply { isAccessible = true }
                            .invoke(service, frame, 41, catalog, id, result, null, null)
                    } finally { delivered.countDown() }
                }
            })
            assertTrue("Actual recognition worker did not start", started.await(5, TimeUnit.SECONDS))
        }
        fun releasePending() { release.countDown(); assertTrue("Actual main Handler completion did not run", delivered.await(5, TimeUnit.SECONDS)) }
        fun assertCancelled() {
            assertTrue((get("scanGeneration") as Int) > 41)
            assertFalse(get("scanning") as Boolean)
            assertNull(get("scanProgressView")); assertFalse(windows[3].isAdded())
            assertTrue(frame.isRecycled)
            assertNull(get("candidateResult"))
            assertEquals(View.VISIBLE, balls.visibility)
            (balls.buttons.values + balls.moreButton).forEach { assertTrue(it.isEnabled); assertTrue(it.alpha > 0f) }
        }
        fun assertControlsDraw() {
            val drawn = CountDownLatch(1)
            val observer = balls.viewTreeObserver
            val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
            main { observer.addOnDrawListener(listener); balls.invalidate() }
            try { assertTrue("Restored controls did not render", drawn.await(5, TimeUnit.SECONDS)) }
            finally { main { if (observer.isAlive) observer.removeOnDrawListener(listener) } }
        }

        override fun close() {
            release.countDown()
            if (pending) delivered.await(5, TimeUnit.SECONDS)
            main {
                handler.removeCallbacksAndMessages(null)
                call("closeCandidates", true, java.lang.Boolean.TYPE)
                call("hideScanProgress")
                windows.forEach { it.remove() }
                (get("recognitionExecutor") as RecognitionExecutor).close()
                (get("accessibilityCaptureExecutor") as java.util.concurrent.ExecutorService).shutdown()
                val autoLogs = get("autoDiagnostics\u0024delegate") as Lazy<*>
                if (autoLogs.isInitialized()) (autoLogs.value as com.idvb.android.alignment.AutoMapOpenDiagnostics).close()
                val session = get("sessionLogs\u0024delegate") as Lazy<*>
                if (session.isInitialized()) (session.value as com.idvb.android.diagnostics.SessionLogRecorder).stop()
                AppServices.repository.saveCatalog(originalCatalog)
                val editor = prefs.edit()
                saved.forEach { (key, value) -> when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                } }
                assertTrue(editor.commit())
                OverlayState.update { originalState }
                practiceField.setBoolean(null, originalPractice)
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }
}
