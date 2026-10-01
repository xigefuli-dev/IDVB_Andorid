package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.overlay.GuideMapView
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.ceil

/** Same saved readiness frames and recorded capture waits on the Android device.
 * This measures the replay pipeline, not fresh game interaction or GPU presentation. */
class AlignmentLatencyCorpusInstrumentedTest {
    @Test fun originalCapturePipelineMedianIsAtMostHalfOfRecordedRequests() {
        val args = InstrumentationRegistry.getArguments()
        val relative = args.getString("alignmentCorpus")
        assumeTrue("Private corpus required", !relative.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun privatePath(path: String) = File(context.cacheDir, path).canonicalFile.also {
            require(it.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        }
        val archives = privatePath(relative!!).listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }
        val supplements = args.getString("alignmentSupplementalReferences")?.let(::privatePath)
        val main = Handler(Looper.getMainLooper())
        val worker = Executors.newSingleThreadExecutor()
        val rows = mutableListOf<JsonObject>()
        try {
            for (archive in archives) AlignmentPackageReplay.open(context, archive, supplements).use { replay ->
                ZipFile(archive).use { zip ->
                    val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().readText()).jsonObject
                    val events = manifest.getValue("events").jsonArray.map { it.jsonObject }
                    fun stage(name: String) = events.firstOrNull { it["stage"]?.jsonPrimitive?.content == name }
                    val baseline = stage("request.total")!!.getValue("durationMs").jsonPrimitive.double
                    val frames = events.filter { it["stage"]?.jsonPrimitive?.content == "readiness.frame" }.map { event ->
                        val attempt = event.getValue("measurements").jsonObject.getValue("attempt").jsonPrimitive.double.toInt()
                        zip.getInputStream(zip.getEntry("readiness-$attempt.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    }
                    val captures = events.filter { it["stage"]?.jsonPrimitive?.content == "readiness.capture" }
                        .map { it.getValue("durationMs").jsonPrimitive.double }
                    val reference = stage("readiness.reference")?.let { event ->
                        val values = event.getValue("measurements").jsonObject
                        MapFrameSignature(event.getValue("series").jsonObject.getValue("histogram").jsonArray.map { it.jsonPrimitive.double },
                            values.getValue("blueGray").jsonPrimitive.double, values.getValue("meanValue").jsonPrimitive.double)
                    }
                    val drawSource = zip.getInputStream(zip.getEntry("reference.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    val request = replay.testCase.request
                    val render = Bitmap.createBitmap(manifest.getValue("screenWidth").jsonPrimitive.int,
                        manifest.getValue("screenHeight").jsonPrimitive.int, Bitmap.Config.ARGB_8888)
                    // Warm/cold costs are reported separately. Live toggles reuse these same assets.
                    val cold = replay.run().elapsedMilliseconds
                    val done = CountDownLatch(1)
                    val trace = AlignmentTrace(captureArtifacts = true)
                    var result: AlignmentTestReport? = null
                    var error: Throwable? = null
                    var elapsed = 0.0
                    var attempt = 0
                    val began = System.nanoTime()
                    main.postDelayed({
                        Choreographer.getInstance().postFrameCallback {
                            Choreographer.getInstance().postFrameCallback {
                                MapOpenFrameCapture(main, worker, AlignmentCancellation(), trace, reference,
                                    callback = { capture, _ ->
                                        val captured = capture.getOrNull()
                                        if (captured == null) { error = capture.exceptionOrNull(); done.countDown() }
                                        else worker.execute {
                                            try { result = replay.run(trace) } catch (failure: Throwable) { error = failure }
                                            finally { captured.recycle() }
                                            main.postDelayed({
                                                try {
                                                    val fit = result?.result as? AlignmentResult.Aligned
                                                    if (fit != null) GuideMapView(context).apply {
                                                        layout(0, 0, render.width, render.height)
                                                        showBitmap(drawSource)
                                                        val bounds = fit.transform.bounds
                                                        showAlignment(RectF(bounds.x.toFloat(), bounds.y.toFloat(), (bounds.x + bounds.width).toFloat(), (bounds.y + bounds.height).toFloat()),
                                                            RectF(request.viewport.x.toFloat(), request.viewport.y.toFloat(), (request.viewport.x + request.viewport.width).toFloat(), (request.viewport.y + request.viewport.height).toFloat()))
                                                        draw(Canvas(render)); showBitmap(null)
                                                    }
                                                } catch (failure: Throwable) { error = failure }
                                                finally { elapsed = (System.nanoTime() - began) / 1e6; done.countDown() }
                                            }, if (result?.result is AlignmentResult.Aligned) ceil(stage("render.first-draw")?.get("durationMs")?.jsonPrimitive?.double ?: 16.0).toLong() else 0L)
                                        }
                                    }, captureFrame = { _, callback ->
                                        val index = attempt++.coerceAtMost(frames.lastIndex)
                                        val deliver = Runnable { callback(Result.success(requireNotNull(frames[index].copy(Bitmap.Config.ARGB_8888, false)))) }
                                        main.postDelayed(deliver, ceil(captures[index]).toLong())
                                        val cancel: () -> Unit = { main.removeCallbacks(deliver) }
                                        cancel
                                    }).start(Rect(0, 0, frames.first().width, frames.first().height))
                            }
                        }
                    }, ceil(stage("display.prepare")?.get("durationMs")?.jsonPrimitive?.double ?: 0.0).toLong())
                    assertTrue("Pipeline timeout: ${archive.name}", done.await(15, TimeUnit.SECONDS))
                    try {
                        error?.let { throw AssertionError(archive.name, it) }
                        rows += buildJsonObject {
                            put("input", archive.name); put("baselineRequestMs", baseline); put("replayPipelineMs", elapsed)
                            put("coldAlgorithmMs", cold); put("warmAlgorithmMs", result!!.elapsedMilliseconds)
                            put("outcome", result!!.result.outcome.name); put("readinessCaptures", attempt)
                            put("stagesMs", buildJsonObject {
                                trace.snapshot().filter { it.durationNanos != null }.groupBy { it.stage }.forEach { (stage, events) ->
                                    put(stage, events.sumOf { it.durationNanos!! } / 1e6)
                                }
                            })
                        }
                        // Exercise deferred encoding only after timing the completed interaction.
                        assertEquals(frames.size * 2 + trace.artifactSnapshot().keys.count { !it.startsWith("readiness-") }, trace.artifactSnapshot().size)
                    } finally { frames.forEach(Bitmap::recycle); drawSource.recycle(); render.recycle() }
                }
            }
        } finally { worker.shutdownNow() }
        fun median(values: List<Double>): Double = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        val before = median(rows.map { it.getValue("baselineRequestMs").jsonPrimitive.double })
        val after = median(rows.map { it.getValue("replayPipelineMs").jsonPrimitive.double })
        File(context.filesDir, "test-evidence/latency-corpus.json").apply { parentFile!!.mkdirs() }.writeText(buildJsonObject {
            put("scope", "saved-frame pipeline; recorded OS capture and display/draw waits; actual readiness, algorithm, trace and Canvas; no claim of fresh game/GPU latency")
            put("baselineMedianMs", before); put("replayMedianMs", after); put("ratio", after / before); put("samples", JsonArray(rows))
        }.toString())
        assertTrue("Median $after ms exceeds half of $before ms", after <= before / 2)
    }
}
