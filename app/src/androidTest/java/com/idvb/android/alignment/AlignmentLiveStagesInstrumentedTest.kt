package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in matching-version live inputs. This measures saved-input stages, not a new live opening. */
class AlignmentLiveStagesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun corpus(): File {
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("alignmentLiveCorpus"))
        return File(context.cacheDir, path).canonicalFile.also {
            require(it.toPath().startsWith(context.cacheDir.canonicalFile.toPath()) && it.isDirectory)
        }
    }
    private fun save(name: String, rows: List<JsonObject>) {
        File(context.filesDir, "test-evidence/$name.json").apply { parentFile!!.mkdirs() }
            .writeText(JsonArray(rows).toString())
    }

    @Test fun liveFloorSelectionAndReadinessRetainTheirRecordedDecisions() {
        val archives = corpus().listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }
        assertTrue(archives.isNotEmpty())
        val rows = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        for (archive in archives) {
            val trace = AlignmentTrace(captureArtifacts = true)
            val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val report = try { FloorIndicatorReplay.run(archive, log = trace) }
                finally { android.os.Process.setThreadPriority(priority) }
            val cost = trace.snapshot().single { it.stage == "floor-indicator.total" }.durationNanos!! / 1e6
            val readiness = MapOpenReadinessReplay.run(archive)
            rows += buildJsonObject {
                put("input", archive.name); put("recordedFloor", report.recordedFloorKey)
                put("actualFloor", report.result.winner.floorKey); put("floorMatches", report.matches)
                put("floorWallMs", cost); put("similarity", report.result.winner.similarity)
                put("readinessMatches", readiness.all { it.recordedReady == it.replayed.ready })
                put("events", JsonArray(trace.snapshot().map { e -> buildJsonObject {
                    put("stage", e.stage); e.durationNanos?.let { put("durationMs", it / 1e6) }
                    put("measurements", buildJsonObject { e.measurements.forEach { (k, v) -> put(k, v) } })
                    put("labels", buildJsonObject { e.labels.forEach { (k, v) -> put(k, v) } })
                } }))
            }
            if (!report.matches || readiness.any { it.recordedReady != it.replayed.ready }) failures += archive.name
            assertTrue(trace.artifactDataSnapshot().containsKey("floor-indicator.png"))
        }
        save("live-floor-stages", rows)
        assertTrue("Recorded live decisions changed: $failures", failures.isEmpty())
    }

    @Test fun nativeSidebarMatchesManagedAndRecordedLiveScoresAtEveryProbeDomain() {
        assertTrue("Packaged native backend required", AutoMapOpenNativeKernel.available)
        val rows = mutableListOf<JsonObject>()
        val seen = hashSetOf<List<Int>>()
        val archives = corpus().listFiles { f -> f.name.startsWith("auto-map-open-") && f.extension == "json" }!!
        assertTrue("Live detector evidence required", archives.isNotEmpty())
        for (archive in archives.sortedBy { it.name }) {
            val doc = Json.parseToJsonElement(archive.readText()).jsonObject
            if (doc.getValue("replayReady").jsonPrimitive.boolean != true) continue
            val reference = doc.getValue("reference").jsonObject
            val refPixels = reference.getValue("pixels").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            val ref = AutoMapOpenDetector.signature(refPixels)
            val width = doc.getValue("configuration").jsonObject.getValue("sidebarWidthFraction").jsonPrimitive.double
            for (sample in doc.getValue("frames").jsonArray.map { it.jsonObject }) {
                val input = (sample["pixels"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: continue
                if (!seen.add(input)) continue
                val signature = AutoMapOpenDetector.signature(input.toIntArray())
                val began = System.nanoTime()
                val actual = AutoMapOpenDetector.compareSidebar(ref, signature, width)
                val wall = (System.nanoTime() - began) / 1e6
                val managed = AutoMapOpenDetector.compareSidebarManaged(ref, signature, width)
                for ((a, b) in listOf(actual.score to managed.score, actual.color to managed.color,
                    actual.brightness to managed.brightness, actual.edge to managed.edge,
                    actual.windowLeft to managed.windowLeft, actual.windowWidth to managed.windowWidth)) assertEquals(b, a, 1e-12)
                assertEquals(managed.offsetX, actual.offsetX); assertEquals(managed.offsetY, actual.offsetY)
                assertEquals(sample.getValue("score").jsonPrimitive.double, actual.score, 1e-12)
                rows += buildJsonObject {
                    put("input", archive.name); put("sampleId", sample.getValue("autoSampleId"))
                    put("nativeWallMs", wall); put("recordedWallMs", sample.getValue("decisionMs"))
                    put("score", actual.score); put("managedEqual", true)
                }
            }
        }
        save("live-sidebar-stages", rows)
        assertTrue(rows.isNotEmpty())
    }
}
