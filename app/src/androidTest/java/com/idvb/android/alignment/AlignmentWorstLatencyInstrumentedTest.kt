package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Timing inventory of every supplied original frame. It preserves production logging
 * and reports individual maxima; this inventory does not bless recorded outcomes as
 * correctness ground truth. Archive round trips use the separate corpus replay test. */
class AlignmentWorstLatencyInstrumentedTest {
    @Test fun inventoryOriginalFrameMaximumLatency() {
        val args = InstrumentationRegistry.getArguments()
        val folders = args.getString("alignmentLatencyCorpora")?.split(',')
        assumeTrue("Private original-image corpora required", !folders.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archives = folders!!.flatMap { relative ->
            val root = File(context.cacheDir, relative).canonicalFile
            require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
            root.listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }.map { relative to it }
        }
        val selected = args.getString("alignmentLatencyCases")?.split(',')?.toSet()
        val inputs = archives.filter { selected == null || it.second.name in selected }
        require(inputs.isNotEmpty())
        val repeats = (args.getString("alignmentLatencyRepeats")?.toInt() ?: 2).also { require(it in 1..5) }
        val output = File(context.filesDir, "test-evidence/latency-${UUID.randomUUID()}").apply { mkdirs() }
        val privateJournal = args.getString("alignmentLatencyStorage") == "private-journal"
        val rows = mutableListOf<JsonObject>()
        for ((corpus, archive) in inputs) {
            AlignmentPackageReplay.open(context, archive).use { replay ->
                var firstResult: AlignmentResult? = null
                repeat(repeats) { run ->
                    val trace = AlignmentTrace(captureArtifacts = true,
                        spoolDirectory = if (privateJournal) File(output, "spool") else null)
                    try {
                        val report = replay.run(trace)
                        val result = report.result
                        if (firstResult == null) firstResult = result else {
                            assertEquals("Repeated outcome changed: ${archive.name}", firstResult!!.outcome, result.outcome)
                            val first = firstResult as? AlignmentResult.Aligned
                            val aligned = result as? AlignmentResult.Aligned
                            if (first != null && aligned != null) assertEquals(first.transform, aligned.transform)
                            assertEquals((firstResult as? AlignmentResult.Rejected)?.code, (result as? AlignmentResult.Rejected)?.code)
                        }
                        rows += buildJsonObject {
                            put("corpus", corpus); put("input", archive.name); put("run", run)
                            put("phase", if (run == 0) "cold" else "warm")
                            put("traceStorage", if (privateJournal) "production-private-journal" else "memory-replay-adapter")
                            put("algorithmMs", report.elapsedMilliseconds); put("outcome", result.outcome.name)
                            put("runningSource", replay.runningSourceFingerprint)
                            (result as? AlignmentResult.Rejected)?.let { put("code", it.code) }
                            (result as? AlignmentResult.Aligned)?.transform?.let {
                                put("scale", it.scale); put("x", it.offsetX); put("y", it.offsetY)
                            }
                            put("timingEvents", buildJsonArray {
                                for (event in trace.snapshot()) if (event.durationNanos != null ||
                                    event.stage == "vpsg.profile" || event.stage == "diagnostics.trace-recording") add(buildJsonObject {
                                    put("stage", event.stage)
                                    put("detail", event.detail)
                                    event.durationNanos?.let { put("durationMs", it / 1e6) }
                                    put("measurements", buildJsonObject { event.measurements.forEach { (name, value) -> put(name, value) } })
                                    put("labels", buildJsonObject { event.labels.forEach { (name, value) -> put(name, value) } })
                                })
                            })
                        }
                        File(output, "summary.json").writeText(JsonArray(rows).toString())
                        android.util.Log.i("IDVB-Latency", "${archive.name} run=$run algorithmMs=${report.elapsedMilliseconds}")
                    } finally { trace.releaseWrittenArtifacts() }
                }
            }
        }
        assertTrue(rows.size == inputs.size * repeats)
    }
}
