package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.overlay.GuideMapView
import com.idvb.android.recognize.vpsg.VpsgNativeKernel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Opt-in original frames. The render measurement is Canvas replay, not live GPU presentation.
 * Every sample is checked, including the first call after reference preparation. */
class AlignmentBudgetCorpusInstrumentedTest {
    @Test fun allOriginalFramesMeetComputationAndCanvasBudgets() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun privatePath(path: String) = File(context.cacheDir, path).canonicalFile.also {
            require(it.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        }
        val root = privatePath(requireNotNull(args.getString("alignmentCorpus")) { "Original private corpus required; this acceptance must not skip" })
        val expected = args.getString("alignmentExpectedTransforms")?.let { Json.parseToJsonElement(privatePath(it).readText()).jsonObject }
        val samples = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        assertTrue("Packaged native backend required", VpsgNativeKernel.available)
        val archives = root.listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }
        assertTrue(archives.isNotEmpty())
        for (archive in archives) AlignmentPackageReplay.open(context, archive).use { replay ->
            val preparation = AlignmentTrace()
            val preparationStarted = System.nanoTime()
            replay.prepare(preparation)
            val preparationWallMs = (System.nanoTime() - preparationStarted) / 1e6
            val expectation = expected?.get(archive.name)?.jsonObject
            val wanted = expectation?.get("transform")?.jsonObject?.let { t -> AlignmentTransform(
                t.getValue("scale").jsonPrimitive.double, t.getValue("offsetX").jsonPrimitive.double,
                t.getValue("offsetY").jsonPrimitive.double, t.getValue("referenceWidth").jsonPrimitive.int,
                t.getValue("referenceHeight").jsonPrimitive.int) } ?: replay.testCase.expectedTransform
            val outcome = expectation?.get("outcome")?.jsonPrimitive?.content?.let(AlignmentOutcome::valueOf)
                ?: args.getString("alignmentExpected")?.let(AlignmentOutcome::valueOf) ?: replay.testCase.expectedOutcome
            val drawSource = ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry("reference.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) } }
            val viewport = replay.testCase.request.viewport
            val render = Bitmap.createBitmap((viewport.x + viewport.width).toInt(), (viewport.y + viewport.height).toInt(), Bitmap.Config.ARGB_8888)
            try {
                repeat(3) { iteration ->
                    val request = replay.testCase.request
                    val trace = AlignmentTrace(captureArtifacts = true)
                    val callerPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
                    // Match RecognitionExecutor's background worker, including the
                    // registry's foreground boost and restoration before returning.
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val began = System.nanoTime()
                    val calculation = try {
                        val report = replay.run(trace, outcome, wanted)
                        val computed = System.nanoTime()
                        assertEquals("Calculation must restore its background worker", android.os.Process.THREAD_PRIORITY_BACKGROUND,
                            android.os.Process.getThreadPriority(android.os.Process.myTid()))
                        report to computed
                    } finally { android.os.Process.setThreadPriority(callerPriority) }
                    val report = calculation.first; val computed = calculation.second
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val fit = report.result as? AlignmentResult.Aligned
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        try {
                            if (fit != null) GuideMapView(context).apply {
                                layout(0, 0, render.width, render.height); showBitmap(drawSource)
                                val b = fit.transform.bounds
                                showAlignment(RectF(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat()),
                                    RectF(viewport.x.toFloat(), viewport.y.toFloat(), (viewport.x + viewport.width).toFloat(), (viewport.y + viewport.height).toFloat()))
                                draw(Canvas(render)); showBitmap(null)
                            }
                        } finally { latch.countDown() }
                    }
                    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val ended = System.nanoTime()
                    val computeMs = (computed - began) / 1e6
                    val totalMs = (ended - began) / 1e6
                    // Production retains all events in the call, then formats text on its
                    // background diagnostics writer after drawing. Exercise the same writer.
                    AlignmentLogcat.write(trace, replay.testCase.methodId, request.map.id, request.floor.key, "IDVB-Align-Verify")
                    val textWrite = trace.snapshot().single { it.stage == "diagnostics.logcat-write" }
                    assertEquals("complete", textWrite.labels["status"])
                    assertEquals(1.0, textWrite.measurements["summaryLinesWritten"])
                    assertTrue(textWrite.measurements.getValue("eventCount") > 0.0)
                    assertTrue(textWrite.timestampNanos - textWrite.durationNanos!! >= ended)
                    samples += buildJsonObject {
                        put("input", archive.name); put("iteration", iteration); put("outcome", report.result.outcome.name)
                        put("correct", report.passed); put("computeWallMs", computeMs); put("computeAndCanvasMs", totalMs)
                        put("actualTransform", fit?.transform?.toString() ?: "none")
                        put("expectedTransform", wanted?.toString() ?: "none")
                        put("preparationMs", preparationWallMs)
                        put("workerPolicy", "background-entry; foreground-calculation; background-restored-before-return")
                        put("workerScheduling", buildJsonObject { trace.snapshot().first { it.stage == "worker.scheduling" }.measurements.forEach { (key, value) -> put(key, value) } })
                        put("deferredLogcatMs", textWrite.durationNanos / 1e6)
                        put("stagesMs", buildJsonObject { trace.snapshot().filter { it.durationNanos != null }.groupBy { it.stage }.forEach { (stage, events) -> put(stage, events.sumOf { it.durationNanos!! } / 1e6) } })
                        if (iteration == 0) put("decisions", JsonArray(trace.snapshot().filter { e ->
                            e.stage.contains("scale") || e.stage in setOf("vpsg.verify.final", "vpsg.verify.alternative-final",
                                "vpsg.verify.uniqueness", "vpsg.refine.precision.result", "vpsg.refine.precision-recheck")
                        }.map { e -> buildJsonObject {
                            put("stage", e.stage); put("detail", e.detail)
                            put("measurements", buildJsonObject { e.measurements.forEach { (k, v) -> if (v.isFinite()) put(k, v) } })
                            put("labels", buildJsonObject { e.labels.forEach { (k, v) -> put(k, v) } })
                            put("gates", JsonArray(e.gates.map { g -> buildJsonObject { put("name", g.name); put("actual", g.actual); put("passed", g.passed) } }))
                        } }))
                    }
                    if (!report.passed || computeMs > AlignmentPerformanceBudget.COMPUTATION_MS || totalMs > AlignmentPerformanceBudget.FIRST_DRAW_MS)
                        failures += "${archive.name} iteration=$iteration correct=${report.passed} compute=$computeMs ms total=$totalMs ms"
                    // Force deferred diagnostics after the timed result, then release their owned data.
                    val artifacts = trace.artifactDataSnapshot()
                    assertTrue(artifacts.isNotEmpty())
                    assertTrue("Replay must preserve the production owned-buffer path",
                        artifacts.getValue("resolved-wall-support.gray8") is AlignmentArtifact.Direct)
                }
            } finally { drawSource.recycle(); render.recycle() }
        }
        File(context.filesDir, "test-evidence/alignment-budgets.json").apply { parentFile!!.mkdirs() }.writeText(buildJsonObject {
            put("scope", "original-frame complete computation including synchronous structured diagnostics plus main-thread Canvas replay; reference/runtime preparation and deferred text Logcat/archive encoding separate; no fresh capture/GPU acceptance")
            put("computationBudgetMs", AlignmentPerformanceBudget.COMPUTATION_MS); put("firstDrawBudgetMs", AlignmentPerformanceBudget.FIRST_DRAW_MS)
            put("samples", JsonArray(samples)); put("failures", JsonArray(failures.map(::JsonPrimitive)))
        }.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
