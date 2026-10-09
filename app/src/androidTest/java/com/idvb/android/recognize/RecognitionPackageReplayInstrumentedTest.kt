package com.idvb.android.recognize

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.*
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.diagnostics.HistoryArchive
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RecognitionPackageReplayInstrumentedTest {
    private fun fixture(test: (android.content.Context, MapRepository, Bitmap) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "recognition-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val frame = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff000000.toInt()) }
        try {
            val repo = MapRepository(context)
            val maps = (1..2).map { number ->
                val path = "map-$number/maps/floor.png"
                File(repo.mapsRoot, path).apply { parentFile!!.mkdirs(); outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                MapRecord("map-$number", "class", "Fixture $number", "source-$number", 1,
                    listOf(FloorRecord("1f", "1F", 1, path, 128, 128)))
            }
            repo.saveCatalog(MapCatalogDocument(classes = listOf(ClassRecord("class", "Fixture", scanFloorKey = "1f")), maps = maps,
                variantGroups = listOf(MapVariantGroupRecord("variants", "class", 1, maps.map { it.id }))))
            test(context, repo, frame)
        } finally { frame.recycle(); root.deleteRecursively() }
    }
    private fun capture() = CaptureDiagnosticsContext(128, 128, 0, 0, 128, 128, "class", "Fixture",
        captureSource = "fixture", scanRequestId = "fixture-scan", rotationAtCaptureCallback = 1,
        calibrationRevisionAtCaptureCallback = 4, captureCallbackNanos = 1_000_000, workerStartedNanos = 2_000_000)

    @Test fun fullCatalogRoundTripsAfterOriginalReferencesDisappearAndFeedbackPreservesIt() = fixture { context, repo, frame ->
        assertTrue(OpenCvRuntime.initialize())
        RecognitionReplayInputs.capture(context, repo, repo.loadCatalog(), "class").use { inputs ->
            val result = MapScanRecognizer(context, repo).recognize(frame, ScreenRect(0.0, 0.0, 128.0, 128.0), 128, 128, "class")
            val saved = RecognitionDiagnosticsStore(context).record(frame, result, capture(), inputs).getOrThrow()
            ZipFile(saved).use { zip ->
                val m = JSONObject(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().use { it.readText() })
                assertTrue(m.getBoolean("replayReady")); assertEquals(2, m.getInt("eligibleCatalogMapCount"))
                assertEquals(1.0, m.getJSONObject("captureContext").getDouble("workerQueueMs"), 0.0)
                assertEquals("fixture-scan", m.getJSONObject("captureContext").getString("scanRequestId"))
                assertTrue(m.getJSONObject("captureContext").isNull("nativeFrameSequence"))
                assertNotNull(zip.getEntry("maps/map-2/maps/floor.png"))
                assertNotNull(zip.getEntry("alignment-provenance.json"))
                assertNotNull(zip.getEntry("runtime-assets/recognition/Gate.png"))
            }
            // Remove only this fixture's source image, proving replay is independent of live maps.
            assertTrue(File(repo.mapsRoot, "map-1/maps/floor.png").delete())
            RecognitionPackageReplay.open(context, saved).use { replay ->
                val repeated = replay.run()
                assertEquals(result.route, repeated.route)
                assertEquals(result.candidates.map { it.map.id to it.disposition }, repeated.candidates.map { it.map.id to it.disposition })
                assertEquals(replay.recordedSourceFingerprint, replay.runningSourceFingerprint)
            }
            val export = File(context.cacheDir, "feedback-diagnostics.zip")
            HistoryArchive.write(File(context.filesDir, "idvb/diagnostics"), export, true)
            val extracted = File(context.cacheDir, "exported-recognition.zip")
            ZipFile(export).use { zip ->
                val receipt = JSONObject(zip.getInputStream(zip.getEntry("diagnostic-export.json")).bufferedReader().use { it.readText() })
                assertTrue(receipt.getBoolean("acceptedWritesCompleted"))
                val entry = zip.entries().asSequence().single { it.name.endsWith(saved.name) }
                zip.getInputStream(entry).use { input -> extracted.outputStream().use { input.copyTo(it) } }
            }
            RecognitionPackageReplay.open(context, extracted).use { assertEquals(result.route, it.run().route) }
        }
    }

    @Test fun changedOrMissingReferencesAndCancelledScanStayExplicitlyUnavailable() = fixture { context, repo, frame ->
        val store = RecognitionDiagnosticsStore(context)
        RecognitionReplayInputs.capture(context, repo, repo.loadCatalog(), "class").use { inputs ->
            File(repo.mapsRoot, "map-1/maps/floor.png").appendBytes(byteArrayOf(1))
            val saved = store.record(frame, RecognitionResult(frame, emptyList()), capture(), inputs).getOrThrow()
            val m = JSONObject(store.diagnosticsJson(saved).getOrThrow())
            assertFalse(m.getBoolean("replayReady"))
            assertTrue(m.getJSONArray("replayUnavailableReasons").toString().contains("reference-changed-during-scan"))
            assertTrue(runCatching { RecognitionPackageReplay.open(context, saved) }.isFailure)
        }
        assertTrue(File(repo.mapsRoot, "map-2/maps/floor.png").delete())
        RecognitionReplayInputs.capture(context, repo, repo.loadCatalog(), "class").use { inputs ->
            val saved = store.record(frame, RecognitionResult(frame, emptyList()), capture(), inputs).getOrThrow()
            val m = JSONObject(store.diagnosticsJson(saved).getOrThrow())
            assertFalse(m.getBoolean("replayReady"))
            val reasons = m.getJSONArray("replayUnavailableReasons").toString()
            assertTrue(reasons, (0 until m.getJSONArray("replayUnavailableReasons").length()).any {
                m.getJSONArray("replayUnavailableReasons").getString(it) == "reference-missing:maps/map-2/maps/floor.png"
            })
        }
        val saved = store.record(frame, null, capture(), failure = java.util.concurrent.CancellationException("fixture-cancelled")).getOrThrow()
        val m = JSONObject(store.diagnosticsJson(saved).getOrThrow())
        assertFalse(m.getBoolean("replayReady")); assertEquals("cancelled", m.getString("terminal"))
        assertTrue(m.getJSONArray("replayUnavailableReasons").toString().contains("scan-has-no-result"))
        assertTrue(m.getJSONArray("replayUnavailableReasons").toString().contains("scan-input-snapshot-not-captured"))
    }

    @Test fun corruptedReferenceBytesCannotPassReplayIntegrity() = fixture { context, repo, frame ->
        RecognitionReplayInputs.capture(context, repo, repo.loadCatalog(), "class").use { inputs ->
            val saved = RecognitionDiagnosticsStore(context).record(frame, RecognitionResult(frame, emptyList()), capture(), inputs).getOrThrow()
            val damaged = File(context.cacheDir, "corrupted.zip")
            ZipFile(saved).use { original -> ZipOutputStream(damaged.outputStream()).use { zip ->
                original.entries().asSequence().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "maps/map-1/maps/floor.png") zip.write(byteArrayOf(1, 2, 3))
                    else original.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            } }
            assertTrue(runCatching { RecognitionPackageReplay.open(context, damaged) }.isFailure)
        }
    }
}
