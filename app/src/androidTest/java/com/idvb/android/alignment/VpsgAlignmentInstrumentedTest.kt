package com.idvb.android.alignment

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.data.OverlayPrefs
import com.idvb.android.data.EyeButtonAction
import com.idvb.android.idvm.*
import com.idvb.android.overlay.GuideMapView
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import java.util.zip.ZipFile

class VpsgAlignmentInstrumentedTest {
    @Test fun selectedSecondFloorAlignsInScreenCoordinatesWithoutScanningOtherIdentities() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "alignment-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.BLACK)
            val paint = Paint().apply { color = Color.rgb(160, 100, 80) }
            drawRect(30f, 25f, 160f, 165f, paint)
            drawRect(185f, 55f, 295f, 218f, paint)
            paint.color = Color.rgb(80, 125, 160)
            drawRect(150f, 95f, 205f, 135f, paint)
        }
        val bgr = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(bgr).use { observed ->
                val reference = Mat.zeros(400, 500, CvType.CV_8UC1)
                try {
                    val destination = reference.submat(Rect(90, 70, 320, 240))
                    try { observed.edges.copyTo(destination) } finally { destination.release() }
                    val repository = MapRepository(context)
                    fun makeFloor(matrix: Mat, name: String): FloorRecord {
                        val path = "map-a/data/$name.png"
                        val file = File(repository.mapsRoot, path).apply { parentFile!!.mkdirs() }
                        assertTrue(Imgcodecs.imwrite(file.path, matrix))
                        return FloorRecord("2f", "二楼", 2, path, 1000, 800,
                            previewRegion = NormalizedRect(.2, .25, .5, .5),
                            recognitionWidth = matrix.cols(), recognitionHeight = matrix.rows(),
                            prebuiltStructureLine = PrebuiltStructureLineRecord(path, "fixture", matrix.cols(),
                                matrix.rows(), file.length(), "fixture"))
                    }
                    val second = makeFloor(reference, "lines")
                    val first = second.copy(key = "1f", sortOrder = 1, prebuiltStructureLine = null)
                    val map = MapRecord("map-a", "class-a", "test", "map-a", 1, listOf(first, second))
                    repository.saveCatalog(MapCatalogDocument(
                        classes = listOf(ClassRecord("class-a", "test", scanFloorKey = "1f")),
                        maps = listOf(map, map.copy(id = "ambiguous-other-map")),
                    ))
                    val viewport = ScreenRect(137.0, 81.0, 320.0, 240.0)
                    val request = AlignmentRequest(frame, viewport, map, second)
                    val registry = AlignmentRegistry.createDefault(repository)
                    val trace = AlignmentTrace(captureArtifacts = true)
                    val report = AlignmentReplayRunner(registry, trace).run(AlignmentTestCase("vpsg", request,
                        expectedTransform = AlignmentTransform(1.0, 47.0, 11.0, 500, 400)))
                    val result = report.result
                    assertTrue("$result", result is AlignmentResult.Aligned)
                    assertTrue("$report", report.passed)
                    assertTrue(report.events.any { it.stage == "verification" })
                    assertTrue((result as AlignmentResult.Aligned).evidence.visibleSupport!! >= .88)
                    val transform = result.transform
                    assertEquals(1.0, transform.scale, .025)
                    assertEquals(47.0, transform.offsetX, 4.0)
                    assertEquals(11.0, transform.offsetY, 4.0)
                    assertEquals(500, transform.referenceWidth)
                    assertEquals(400, transform.referenceHeight)
                    val diagnosticContext = AlignmentDiagnosticContext("vpsg", map, second.key, viewport, 800, 600, "test-frame")
                    val store = AlignmentDiagnosticsStore(context)
                    val archive = store.record(trace, diagnosticContext, frame, result, "test-result").getOrThrow()
                    ZipFile(archive).use { zip ->
                        val document = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics.json"))
                            .bufferedReader().use { it.readText() }).jsonObject
                        assertTrue(document.getValue("replayReady").jsonPrimitive.boolean)
                        assertTrue(document.getValue("sourceSnapshotAvailable").jsonPrimitive.boolean)
                        val events = document.getValue("events").jsonArray.map { it.jsonObject }
                        assertTrue(events.any { it["stage"]?.jsonPrimitive?.content == "vpsg.scale.result" && it.getValue("gates").jsonArray.isNotEmpty() })
                        assertTrue(events.any { it["stage"]?.jsonPrimitive?.content == "vpsg.verify.final" && it.getValue("thresholds").jsonObject.isNotEmpty() })
                        assertTrue(events.any { it["stage"]?.jsonPrimitive?.content == "vpsg.translation.total" && it.getValue("durationMs").jsonPrimitive.double > 0 })
                        assertNotNull(zip.getEntry("captured.png")); assertNotNull(zip.getEntry("reference.png"))
                        assertNotNull(zip.getEntry("observed-edges.gray8")); assertNotNull(zip.getEntry("valid-mask.gray8"))
                        assertNotNull(zip.getEntry("revealed-domain.gray8"))
                    }
                    AlignmentPackageReplay.open(context, archive).use { replay ->
                        assertTrue(replay.sourceMatches)
                        val replayed = replay.run()
                        assertTrue("Saved inputs must reproduce the accepted transform: $replayed", replayed.passed)
                    }
                    // Instrumentation executes under the target UID and cannot write the test APK's private directory.
                    val evidence = File(target.filesDir, "test-evidence/alignment-fixture.zip")
                    check(evidence.parentFile!!.mkdirs() || evidence.parentFile!!.isDirectory)
                    evidence.outputStream().use { store.copyPackage(archive, it) }
                    ZipFile(evidence).use { zip ->
                        val write = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics-write.json"))
                            .bufferedReader().use { it.readText() }).jsonObject
                        assertTrue(write.getValue("archiveWriteTotalMs").jsonPrimitive.double > 0)
                        assertTrue(write.getValue("manifestSerializationMs").jsonPrimitive.double > 0)
                    }
                    assertTrue(registry.align("vpsg", request.copy(floor = first)) is AlignmentResult.Unavailable)
                    assertTrue(registry.align("missing", request) is AlignmentResult.Unavailable)
                    assertFalse("The caller owns the frame", frame.isRecycled)

                    val repeated = Mat.zeros(400, 1000, CvType.CV_8UC1)
                    try {
                        for (x in listOf(90, 590)) {
                            val crop = repeated.submat(Rect(x, 70, 320, 240))
                            try { observed.edges.copyTo(crop) } finally { crop.release() }
                        }
                        val floor = makeFloor(repeated, "repeated")
                        val repeatedMap = map.copy(floors = listOf(floor))
                        assertTrue("Ambiguous placements must not move the guide",
                            registry.align("vpsg", request.copy(map = repeatedMap, floor = floor)) is AlignmentResult.Rejected)
                    } finally { repeated.release() }

                    frame.eraseColor(Color.BLACK)
                    val failedTrace = AlignmentTrace(captureArtifacts = true)
                    val failed = registry.align("vpsg", request, failedTrace)
                    assertTrue(failed is AlignmentResult.Rejected)
                    val failedPackage = store.record(failedTrace, diagnosticContext, frame, failed, "rejected").getOrThrow()
                    AlignmentPackageReplay.open(context, failedPackage).use { replay ->
                        val failedReplay = replay.run()
                        assertTrue(failedReplay.passed)
                        assertTrue(failedReplay.result is AlignmentResult.Rejected)
                    }
                } finally { reference.release() }
            }
        } finally { bgr.release(); frame.recycle(); isolated.deleteRecursively() }
    }

    @Test fun alignedRenderingClipsWithoutStretchingOrRecenteringTheVisiblePart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.RED)
                drawRect(50f, 0f, 100f, 100f, Paint().apply { color = Color.BLUE })
            }
            val output = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
            val view = GuideMapView(instrumentation.targetContext)
            try {
                view.layout(0, 0, 200, 160)
                view.showBitmap(bitmap)
                view.showAlignment(RectF(-40f, 10f, 160f, 110f), RectF(20f, 20f, 140f, 130f))
                view.draw(Canvas(output))
                assertEquals(Color.TRANSPARENT, output.getPixel(10, 50))
                assertEquals(Color.RED, output.getPixel(40, 50))
                assertEquals(Color.BLUE, output.getPixel(65, 50))
                assertEquals(Color.TRANSPARENT, output.getPixel(150, 50))
                assertEquals(Color.TRANSPARENT, output.getPixel(80, 120))
            } finally { view.showBitmap(null); bitmap.recycle(); output.recycle() }
        }
    }

    @Test fun alignedRenderingExtendsBeyondCalibrationWithoutChangingPose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.RED)
                drawRect(50f, 0f, 100f, 100f, Paint().apply { color = Color.BLUE })
            }
            val output = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
            val view = GuideMapView(instrumentation.targetContext)
            try {
                view.layout(0, 0, 200, 160)
                view.showBitmap(bitmap)
                // Calibration is (20, 20)-(140, 130); the render viewport is the screen.
                view.showAlignment(RectF(-40f, 10f, 160f, 110f), RectF(0f, 0f, 200f, 160f))
                view.draw(Canvas(output))
                assertEquals(Color.RED, output.getPixel(10, 50))
                assertEquals(Color.RED, output.getPixel(40, 50))
                assertEquals(Color.BLUE, output.getPixel(65, 50))
                assertEquals(Color.BLUE, output.getPixel(150, 50))
                assertEquals(Color.RED, output.getPixel(40, 15))
                assertEquals(Color.TRANSPARENT, output.getPixel(170, 50))
                assertEquals(Color.TRANSPARENT, output.getPixel(80, 120))
            } finally { view.showBitmap(null); bitmap.recycle(); output.recycle() }
        }
    }

    @Test fun reverseVisibilityKeepsKnownEmptyRoomInteriorsButExcludesFog() {
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(31, 38, 49))
            drawRect(50f, 60f, 220f, 200f, Paint().apply { color = Color.rgb(160, 100, 80) })
        }
        val bgr = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(bgr, visibilityScopedReverse = true).use { live ->
                val domain = requireNotNull(live.revealed)
                assertEquals("An absent reference wall inside a revealed room must still count as a miss",
                    255.0, domain.get(130, 130)[0], 0.0)
                assertEquals(0.0, live.edges.get(130, 130)[0], 0.0)
                assertEquals("Unrevealed background is neutral", 0.0, domain.get(20, 20)[0], 0.0)
                assertEquals("Visible wall rims remain testable", 255.0, domain.get(100, 50)[0], 0.0)
            }
        } finally { bgr.release(); frame.recycle() }
    }

    @Test fun redMarkersAreOccludedWhileLargeRedRoomsAndRealWallsRemainEvidence() {
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(31, 38, 49))
            drawRect(30f, 30f, 170f, 200f, Paint().apply { color = Color.rgb(160, 100, 80) })
            val red = Paint().apply { color = Color.HSVToColor(floatArrayOf(350f, .55f, .65f)) }
            drawRect(80f, 90f, 92f, 102f, red)
            drawRect(200f, 45f, 295f, 200f, red)
        }
        val bgr = CvImages.bitmapToBgr(frame)
        try {
            val trace = AlignmentTrace(captureArtifacts = true)
            VpsgLiveExtractor.extract(bgr, trace, visibilityScopedReverse = true).use { live ->
                val occlusion = trace.artifactSnapshot().getValue("occlusion-mask.gray8")
                assertEquals(255, occlusion[95 * 320 + 85].toInt() and 255)
                assertEquals("Marker footprint cannot become reverse missing-wall evidence", 0.0, live.revealed!!.get(95, 85)[0], 0.0)
                assertEquals("Large connected red room is not a marker", 0, occlusion[100 * 320 + 250].toInt() and 255)
                assertEquals(255.0, live.revealed.get(100, 250)[0], 0.0)
                val wall = live.edges.submat(Rect(27, 70, 8, 70))
                try { assertTrue("True wall outside the occlusion stays verifiable", org.opencv.core.Core.countNonZero(wall) > 50) }
                finally { wall.release() }
                val marker = live.edges.submat(Rect(78, 88, 16, 16))
                try { assertEquals("Marker must not invent a wall", 0, org.opencv.core.Core.countNonZero(marker)) }
                finally { marker.release() }
            }
        } finally { bgr.release(); frame.recycle() }
    }

    @Test fun operationPreferencesPersistWithoutTouchingConsent() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "alignment-prefs-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("$prefix-$name", mode)
        }
        try {
            val prefs = OverlayPrefs(context)
            assertEquals(EyeButtonAction.SHOW_AND_ALIGN, prefs.eyeButtonAction)
            prefs.eyeButtonAction = EyeButtonAction.SHOW_ONLY
            prefs.alignmentMethodId = "future-method"
            val reopened = OverlayPrefs(context)
            assertEquals(EyeButtonAction.SHOW_ONLY, reopened.eyeButtonAction)
            assertEquals("future-method", reopened.alignmentMethodId)
            assertFalse(com.idvb.android.UsageConsent.isAccepted(context))
        } finally {
            target.deleteSharedPreferences("$prefix-overlay")
            target.deleteSharedPreferences("$prefix-mandatory_usage_consent")
        }
    }
}
