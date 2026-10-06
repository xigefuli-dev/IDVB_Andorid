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
    @Test fun resetWaitsForOldWorkerBeforeEvictingMapResourcesAndRejectsLateIdentity() {
        Fixture().use { f ->
            val cache = com.idvb.android.resources.MapBitmapCaches.previews
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val cancellation = AlignmentCancellation()
            f.startPending()
            f.main {
                cache.load("resource-release-fixture") { bitmap }
                f.set("scanCancellation", cancellation)
                f.call("resetMapIdentity", false, java.lang.Boolean.TYPE)
                assertTrue(cancellation.isCancelled)
                assertTrue((f.get("mapResources") as com.idvb.android.resources.MapResourceRelease).pending)
                assertTrue(cache.retainedBytes > 0)
                assertNull(f.get("currentMap")); assertNull(AppServices.prefs.lastMapId)
            }
            f.releasePending()
            f.awaitResourceRelease()
            f.main {
                assertEquals(0L, cache.retainedBytes)
                assertNull(f.get("currentMap")); assertNull(AppServices.prefs.lastMapId)
                assertFalse("Borrowed image must remain usable after cache release", bitmap.isRecycled)
                bitmap.recycle()
                f.assertCancelled()
            }
        }
    }

    @Test fun existingIdentityRescanReleasesResourcesBeforeOpeningManualPicker() {
        Fixture().use { f ->
            val cache = com.idvb.android.resources.MapBitmapCaches.previews
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            f.main {
                assertTrue(com.idvb.android.UsageConsent.accept(InstrumentationRegistry.getInstrumentation().targetContext))
                AppServices.prefs.debugMode = true
                AppServices.prefs.manualMapSelectionEnabled = true
                f.set("scanning", false)
                cache.load("resource-release-fixture") { bitmap }
                f.call("runForegroundScan")
                assertTrue((f.get("mapResources") as com.idvb.android.resources.MapResourceRelease).pending)
                assertNull(f.get("candidateView"))
                assertEquals(f.a, f.get("currentMap"))
            }
            f.awaitResourceRelease()
            f.main {
                assertEquals(0L, cache.retainedBytes)
                assertNotNull(f.get("candidateView"))
                assertEquals("Identity is kept until a new choice commits", f.a, f.get("currentMap"))
                assertEquals(f.a.id, AppServices.prefs.lastMapId)
                bitmap.recycle()
            }
        }
    }
    @Test fun scanWorkerAndEveryTerminalBranchRestoreControlsAfterVisibilityRefresh() {
        for (outcome in listOf("failure", "no-result", "retained-candidates")) {
            Fixture().use { f ->
                f.main {
                    f.set("scanCapturing", true)
                    f.call("applyPracticeVisibility")
                    assertEquals(View.INVISIBLE, f.balls.visibility)
                    f.set("scanCapturing", false)
                    f.call("applyPracticeVisibility")
                    assertTrue(f.get("scanning") as Boolean)
                    assertEquals("Worker computation must leave the controls visible", View.VISIBLE, f.balls.visibility)
                    // Reproduce a late visibility writer before the main-thread result consumer.
                    f.balls.visibility = View.INVISIBLE
                    AppServices.prefs.showUnconfirmedCandidates = outcome == "retained-candidates"
                    AppServices.prefs.backgroundScanEnabled = true
                    f.complete(if (outcome == "failure") null else f.result.copy(candidates = emptyList()),
                        if (outcome == "failure") IllegalStateException("controlled worker failure") else null)
                    assertFalse(f.get("scanning") as Boolean)
                    assertFalse(f.get("scanCapturing") as Boolean)
                    assertEquals(View.VISIBLE, f.balls.visibility)
                    assertEquals(f.a, f.get("currentMap"))
                    assertEquals(outcome == "retained-candidates", f.get("candidateResult") != null)
                    f.call("applyPracticeVisibility")
                    assertEquals("Later auto polling must not hide completed scans", View.VISIBLE, f.balls.visibility)
                }
                f.assertControlsDraw()
            }
        }
    }

    @Test fun missingHiddenSurfaceReceiptTimesOutOnceAndControlsCanRecover() {
        Fixture().use { f ->
            val finished = CountDownLatch(1)
            val calls = AtomicInteger()
            var barrier: OverlayCaptureFrameBarrier? = null
            try {
                f.main {
                    f.balls.visibility = View.INVISIBLE
                    barrier = OverlayCaptureFrameBarrier(f.handler, listOf("controls" to f.balls),
                        { true }, AlignmentLogSink.NONE, timeoutMs = 80L) { ready ->
                        assertFalse(ready)
                        calls.incrementAndGet()
                        f.set("scanCapturing", false)
                        f.set("scanning", false)
                        f.call("applyPracticeVisibility")
                        finished.countDown()
                    }.also { it.start() }
                }
                assertTrue("An invisible root must not leave capture waiting indefinitely", finished.await(2, TimeUnit.SECONDS))
                f.main { assertTrue(barrier!!.timedOut); assertEquals(View.VISIBLE, f.balls.visibility); barrier!!.cancel() }
                f.assertControlsDraw()
                f.main { assertEquals("Late draw callbacks cannot complete a timeout twice", 1, calls.get()) }
            } finally { f.main { barrier?.cancel() } }
        }
    }

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

    @Test fun automaticCandidateDismissalRetainsChoiceAndManualCommitRestoresControls() {
        Fixture().use { f ->
            f.main {
                AppServices.prefs.autoDetectMapOpenEnabled = true
                f.balls.automaticMapOpen = true
                f.set("candidateResult", f.result)
                f.set("candidateManualSelection", true)
                f.call("dismissCandidates")
                assertNull(f.get("candidateView"))
                assertEquals(f.result, f.get("candidateResult"))
                assertFalse(f.result.capturedRegion.isRecycled)
                assertTrue(f.balls.candidatesAvailable)
                assertEquals(true, f.call("showPendingCandidates"))
                assertNotNull(f.get("candidateView"))
                assertEquals(true, f.get("candidateManualSelection"))
                f.call("dismissCandidates")
                assertEquals(true, f.call("showPendingCandidates"))
                val manual = f.candidate().copy(disposition = CandidateDisposition.CATALOG_ONLY)
                assertEquals(true, f.lock(manual))
                assertNull(f.get("candidateResult"))
                assertFalse(f.balls.candidatesAvailable)
                assertTrue(OverlayControlPolicy(true, f.balls.mapLocked, false).eyeEnabled)
                assertTrue(OverlayControlPolicy(true, f.balls.mapLocked, false).floorEnabled)
                assertEquals(MapIdentitySource.MANUAL_UNVERIFIED, AppServices.prefs.lastMapIdentitySource)
            }
        }
    }

    @Test fun automaticFloorCommandsCancelPendingScanAndChangeSelection() {
        Fixture().use { f ->
            f.main {
                AppServices.prefs.autoDetectMapOpenEnabled = true
                f.call("nextFloor")
                assertEquals(1, f.get("floorIndex"))
                assertEquals("2f", AppServices.prefs.lastFloorKey)
                assertTrue((f.get("scanGeneration") as Int) > 41)
                f.call("previousFloor")
                assertEquals(0, f.get("floorIndex"))
                assertEquals("1f", AppServices.prefs.lastFloorKey)
                assertTrue((f.get("scanGeneration") as Int) > 41)
            }
        }
    }

    @Test fun oppositeRotationWithUnchangedDimensionsCancelsOldAutomaticCapture() {
        Fixture().use { f ->
            f.main {
                val screen = f.call("screenSize")
                val rotation = f.call("screenRotation") as Int
                f.set("lastCaptureScreen", screen)
                f.set("lastCaptureRotation", (rotation + 2) % 4)
                f.set("autoContext", "old-opposite-rotation")
                f.set("autoCaptureBusy", true)
                val aborted = AtomicInteger()
                f.set("abortAutoCapture", { aborted.incrementAndGet(); Unit })
                val detector = com.idvb.android.alignment.AutoMapOpenDetector(
                    com.idvb.android.alignment.AutoMapOpenDetector.signature(IntArray(768) {
                        if (it / 32 % 4 < 2) 0xff687580.toInt() else 0xffc0c8d0.toInt()
                    }))
                f.set("autoDetector", detector)
                f.call("refreshWindowsForDisplayChange")
                assertEquals(1, aborted.get())
                assertFalse(f.get("autoCaptureBusy") as Boolean)
                assertNull(f.get("autoDetector"))
                assertEquals("", f.get("autoContext"))
                assertEquals(screen, f.get("lastCaptureScreen"))
                assertEquals(rotation, f.get("lastCaptureRotation"))
                assertFalse(f.get("scanning") as Boolean)
            }
        }
    }

    @Test fun automaticEyeHandlerClosesAndCancelsActualAlignmentThenReopensSelectedGuide() {
        Fixture().use { f ->
            f.main {
                assertTrue(com.idvb.android.UsageConsent.accept(InstrumentationRegistry.getInstrumentation().targetContext))
                AppServices.prefs.autoDetectMapOpenEnabled = true
                AppServices.prefs.eyeButtonAction = com.idvb.android.data.EyeButtonAction.SHOW_ONLY
                f.set("scanning", false)
                val screen = f.call("screenSize") as Pair<*, *>
                AppServices.prefs.setCaptureRegion((screen.first as Int) > (screen.second as Int), .1f, .1f, .8f, .8f)
                f.installCachedGuide()
                val cancellation = AlignmentCancellation()
                f.set("activeAlignmentCancellation", cancellation)
                f.set("aligning", true); f.set("guideVisible", true); f.set("autoGuideOwned", true)
                f.call("handleEyeAction")
                assertTrue(cancellation.isCancelled)
                assertFalse(f.get("guideVisible") as Boolean)
                assertFalse(f.get("autoGuideOwned") as Boolean)
                f.call("handleEyeAction")
                assertTrue(f.get("guideVisible") as Boolean)
                assertTrue(f.balls.guideVisible)
                assertFalse(f.get("aligning") as Boolean)
            }
        }
    }

    @Test fun automaticDefaultControlRootDoesNotOccludeTheRightSidebar() {
        Fixture().use { f ->
            f.main {
                assertTrue(com.idvb.android.UsageConsent.accept(InstrumentationRegistry.getInstrumentation().targetContext))
                AppServices.prefs.autoDetectMapOpenEnabled = true
                f.set("scanning", false)
                (f.get("window") as OverlayWindowManager).remove()
                f.set("balls", null)
                f.call("ensureBalls")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            f.main {
                val screen = f.call("screenSize") as Pair<*, *>
                val width = screen.first as Int; val height = screen.second as Int
                assertEquals(0, (f.get("window") as OverlayWindowManager).x)
                assertEquals(false, f.call("autoRegionOccluded", RectF(width * .85f, 0f, width.toFloat(), height.toFloat()), RectF::class.java))
                assertTrue((f.get("balls") as OverlayBallView).isShown)
            }
        }
    }

    @Test fun recalibrationStopsOldCaptureAndScanAndPersistsActualWindowCoordinates() {
        Fixture().use { f ->
            val aborted = AtomicInteger()
            val scan = AlignmentCancellation()
            f.main {
                assertTrue(com.idvb.android.UsageConsent.accept(InstrumentationRegistry.getInstrumentation().targetContext))
                f.set("autoCaptureBusy", true)
                f.set("abortAutoCapture", { aborted.incrementAndGet(); Unit })
                f.set("autoContext", "before-recalibration")
                f.set("scanCancellation", scan)
                f.call("enterBlueprintMode")
                assertEquals(1, aborted.get())
                assertTrue(scan.isCancelled)
                assertFalse(f.get("scanning") as Boolean)
                assertFalse(f.get("autoCaptureBusy") as Boolean)
                assertEquals("", f.get("autoContext"))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            f.main {
                val view = f.get("blueprintView") as BlueprintCalibrationView
                assertTrue("Actual calibration window was not laid out", view.width > 0 && view.height > 0)
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val screen = f.call("screenSize") as Pair<*, *>
                val width = screen.first as Int; val height = screen.second as Int
                val region = RectF(view.width * .2f, view.height * .2f, view.width * .7f, view.height * .7f)
                val revision = f.get("calibrationRevision") as Long
                f.confirmCalibration(view, region)
                val saved = requireNotNull(AppServices.prefs.captureRegion(width > height))
                assertEquals((origin[0] + region.left) / width, saved[0], .00001f)
                assertEquals((origin[1] + region.top) / height, saved[1], .00001f)
                assertEquals((origin[0] + region.right) / width, saved[2], .00001f)
                assertEquals((origin[1] + region.bottom) / height, saved[3], .00001f)
                assertEquals(revision + 1, f.get("calibrationRevision"))
                assertNull(f.get("blueprintView"))
                assertNull(f.get("alignedGuideBounds"))
                assertEquals(1, aborted.get())
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
            "selected_map_class_id", "debug_mode", "manual_map_selection_enabled", "show_unconfirmed_candidates", "background_scan_enabled",
            "auto_detect_map_open_enabled", "eye_button_action") +
            listOf("capture_landscape", "capture_portrait", "guide_landscape", "guide_portrait")
                .flatMap { prefix -> listOf("left", "top", "right", "bottom").map { "${prefix}_$it" } }
        private val saved = savedKeys.associateWith { prefs.all[it] }
        private val layoutPrefs = base.getSharedPreferences("overlay_button_layout", Context.MODE_PRIVATE)
        private val hadCustomLayout = layoutPrefs.contains("custom")
        private val originalCustomLayout = layoutPrefs.getBoolean("custom", false)
        private val originalCatalog = AppServices.repository.loadCatalog()
        private val consentPrefs = base.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        private val hadConsentRecord = consentPrefs.contains("accepted_revision")
        private val originalConsentRevision = consentPrefs.getInt("accepted_revision", 0)
        private val tutorialStore = com.idvb.android.tutorial.TutorialStore.get(base)
        private val originalTutorial = tutorialStore.state.value
        private val tutorialPrefs = base.getSharedPreferences("beginner_tutorial_v1", Context.MODE_PRIVATE)
        private val originalTutorialRecord = tutorialPrefs.getString("progress", null)
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
                assertTrue(layoutPrefs.edit().putBoolean("custom", false).commit())
                // This fixture is restricted to the isolated verification App above.
                // Exercise controls after the real tutorial gate has been satisfied.
                tutorialStore.update { com.idvb.android.tutorial.TutorialProgress(
                    step = com.idvb.android.tutorial.TutorialStep.DONE,
                    passed = com.idvb.android.tutorial.TutorialStep.activeSteps.toSet(),
                    quizAnswered = com.idvb.android.tutorial.TutorialQuiz.questions.size,
                    completionRevision = com.idvb.android.tutorial.TutorialQuiz.REVISION,
                    practiceFinished = true) }
                AppServices.repository.saveCatalog(MapCatalogDocument(classes = listOf(ClassRecord(id, "fixture"),
                    ClassRecord(otherClass, "other")), maps = listOf(a, b),
                    variantGroups = listOf(MapVariantGroupRecord("$id-group", id, 0, listOf(a.id, b.id)))))
                AppServices.prefs.selectedMapClassId = id
                AppServices.prefs.lastMapId = a.id
                AppServices.prefs.lastFloorKey = if (initial == 0) "1f" else "2f"
                AppServices.prefs.lastMapIdentitySource = MapIdentitySource.MANUAL_UNVERIFIED
                AppServices.prefs.autoDetectMapOpenEnabled = false
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                set("overlayContext", context)
                for (name in listOf("window", "guideWindow", "candidateWindow", "scanProgressWindow", "blueprintWindow", "calibrationBorderWindow")) {
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
        fun installCachedGuide() {
            val prepared = call("guideFloorPreparation") as Pair<*, *>
            set("guideBitmapKey", prepared.first)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            set("guideBitmap", bitmap)
            val view = GuideMapView(get("overlayContext") as Context)
            set("guideView", view)
            windows[1].apply { width = 32; height = 32; add(view, locked = true) }
            AppServices.prefs.clearGuideRegion(true); AppServices.prefs.clearGuideRegion(false)
        }
        fun confirmCalibration(view: BlueprintCalibrationView, region: RectF) = OverlayService::class.java
            .getDeclaredMethod("commitBlueprintCalibration", BlueprintCalibrationView::class.java, RectF::class.java)
            .apply { isAccessible = true }.invoke(service, view, region)
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

        fun complete(recognition: RecognitionResult?, failure: Throwable?) = OverlayService::class.java.getDeclaredMethod(
            "completeCapturedScan", Bitmap::class.java, Integer.TYPE, MapCatalogDocument::class.java,
            String::class.java, RecognitionResult::class.java, Throwable::class.java, Throwable::class.java)
            .apply { isAccessible = true }.invoke(service, frame, 41, catalog, id, recognition, failure, null)

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
        fun awaitResourceRelease() {
            val release = get("mapResources") as com.idvb.android.resources.MapResourceRelease
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (release.pending && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse("Actual map resource release did not finish", release.pending)
            main { }
        }
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
            awaitResourceRelease()
            main {
                handler.removeCallbacksAndMessages(null)
                (get("buttonLayout") as? OverlayButtonLayout)?.dispose()
                (get("ballMenu") as? OverlayBallMenuWindow)?.hide()
                call("closeCandidates", true, java.lang.Boolean.TYPE)
                call("hideScanProgress")
                windows.forEach { it.remove() }
                (get("recognitionExecutor") as RecognitionExecutor).close()
                (get("accessibilityCaptureExecutor") as java.util.concurrent.ExecutorService).shutdown()
                for (name in listOf("guidePreparationExecutor", "alignmentReadinessExecutor", "resourceReleaseExecutor"))
                    (get(name) as java.util.concurrent.ExecutorService).shutdown()
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
                    is Float -> editor.putFloat(key, value)
                } }
                assertTrue(editor.commit())
                val layoutEditor = layoutPrefs.edit()
                if (hadCustomLayout) layoutEditor.putBoolean("custom", originalCustomLayout) else layoutEditor.remove("custom")
                assertTrue(layoutEditor.commit())
                val consentEditor = consentPrefs.edit()
                if (hadConsentRecord) consentEditor.putInt("accepted_revision", originalConsentRevision)
                else consentEditor.remove("accepted_revision")
                assertTrue(consentEditor.commit())
                tutorialStore.update { originalTutorial }
                val tutorialEditor = tutorialPrefs.edit()
                if (originalTutorialRecord == null) tutorialEditor.remove("progress")
                else tutorialEditor.putString("progress", originalTutorialRecord)
                assertTrue(tutorialEditor.commit())
                OverlayState.update { originalState }
                practiceField.setBoolean(null, originalPractice)
                if (!frame.isRecycled) frame.recycle()
                (get("guideBitmap") as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }
}
