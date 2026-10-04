package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Synthetic content tests, not target-game acceptance. No captures or shared storage. */
class FloorIndicatorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allFiveStatesWinAcrossPositionAndScaleWithoutResolutionPriors() {
        val recognizer = FloorIndicatorRecognizer.load(context)
        for ((name, key) in listOf("hard-1f.png" to "1f", "hard-2f.png" to "2f",
            "nightmare-b1f.png" to "b1f", "nightmare-1f.png" to "1f", "nightmare-2f.png" to "2f")) {
            for (scale in listOf(.55, 1.0, 1.7)) {
                val template = context.assets.open("FloorIndicators/$name").use { BitmapFactory.decodeStream(it)!! }
                val resized = Bitmap.createScaledBitmap(template, (template.width * scale).toInt(), (template.height * scale).toInt(), true)
                val frame = Bitmap.createBitmap(850, 210, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(30, 40, 50)); Canvas(this).drawBitmap(resized, 289f, 39f, null)
                }
                try {
                    val result = recognizer.recognize(frame, setOf("b1f", "1f", "2f"), AlignmentCancellation())
                    assertEquals("$name scale=$scale candidates=${result.candidates}", key, result.winner.floorKey)
                    assertEquals(result.candidates.maxOf { it.similarity }, result.winner.similarity, 0.0)
                } finally {
                    frame.recycle(); if (resized !== template) resized.recycle(); template.recycle()
                }
            }
        }
    }

    @Test fun texturelessFrameStillReturnsHighestCandidate() {
        val frame = Bitmap.createBitmap(800, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try { assertNotNull(FloorIndicatorRecognizer.load(context).recognize(frame,
            setOf("1f", "2f", "b1f"), AlignmentCancellation()).winner) } finally { frame.recycle() }
    }

    @Test fun cancellationStopsTemplateSearchAtANativeBoundary() {
        val frame = Bitmap.createBitmap(800, 180, Bitmap.Config.ARGB_8888)
        val token = AlignmentCancellation()
        val events = mutableListOf<AlignmentLogEvent>()
        try {
            try {
                FloorIndicatorRecognizer.load(context).recognize(frame, setOf("1f", "2f", "b1f"), token,
                    AlignmentLogSink { event -> events += event; if (event.stage == "floor-indicator.match") token.cancel("eye-hidden") })
                fail("Cancelled selection must stop actual search")
            } catch (_: AlignmentCancelledException) { }
            assertEquals(1, events.count { it.stage == "floor-indicator.match" })
            assertTrue(events.any { it.stage == "floor-indicator.exit" && it.detail == "cancelled" })
        } finally { frame.recycle() }
    }

    @Test fun diagnosticPackageReplaysFloorEvenWhenAlignmentHasNoOutcome() {
        val directory = java.io.File(context.cacheDir, "floor-indicator-roundtrip-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = directory
        }
        val frame = context.assets.open("FloorIndicators/nightmare-2f.png").use { BitmapFactory.decodeStream(it)!! }
        try {
            val trace = AlignmentTrace(captureArtifacts = true)
            val result = FloorIndicatorRecognizer.load(context).recognize(frame, setOf("b1f", "1f", "2f"), AlignmentCancellation(), trace)
            val floor = com.idvb.android.idvm.FloorRecord("2f", "2F", 2, "unavailable", 100, 100)
            val map = com.idvb.android.idvm.MapRecord("fixture", "fixture", "fixture", "fixture", 1,
                listOf(floor.copy(key = "1f"), floor, floor.copy(key = "b1f")))
            val archive = AlignmentDiagnosticsStore(isolated).record(trace,
                AlignmentDiagnosticContext("vpsg", map, result.winner.floorKey,
                    com.idvb.android.recognize.gate.ScreenRect(0.0, 0.0, 100.0, 100.0), 1920, 1080,
                    "synthetic", floorSelectedByIndicator = true), null,
                AlignmentResult.Unavailable("fixture missing pose reference", "prebuilt-unavailable"), "unavailable").getOrThrow()
            val replay = FloorIndicatorReplay.run(archive)
            assertTrue(replay.matches)
            assertEquals("2f", replay.result.winner.floorKey)
        } finally { frame.recycle(); directory.deleteRecursively() }
    }
}
