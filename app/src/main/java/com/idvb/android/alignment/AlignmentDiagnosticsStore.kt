package com.idvb.android.alignment

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.idvb.android.BuildConfig
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AlignmentDiagnosticContext(val methodId: String, val map: MapRecord, val floorKey: String,
    val viewport: ScreenRect, val screenWidth: Int, val screenHeight: Int, val captureSource: String,
    val sessionId: String? = null)

/** Local, bounded history. Numeric traces are always retained, including unavailable/cancelled attempts. */
class AlignmentDiagnosticsStore(context: Context) {
    private val app = context.applicationContext
    private val history = com.idvb.android.diagnostics.DiagnosticHistory(File(app.filesDir, "idvb/diagnostics"))
    private val requestSessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "idvb-alignment-diagnostics").apply { isDaemon = true } }

    /** Post-result user actions cannot be appended to an already closed ZIP. Keep a correlated sidecar. */
    fun recordLifecycle(requestId: String, stage: String, detail: String, measurements: Map<String, Double> = emptyMap(), sessionId: String? = null) {
        require(Regex("[a-zA-Z0-9-]+").matches(requestId))
        if (sessionId != null) requestSessions[requestId] = sessionId
        val boundSession = sessionId ?: requestSessions[requestId]
        val event = buildJsonObject {
            put("requestId", requestId); put("stage", stage); put("detail", detail)
            put("timestampNanos", System.nanoTime()); put("createdAtMillis", System.currentTimeMillis())
            put("buildVersion", BuildConfig.BUILD_VERSION); put("measurements", numbers(measurements))
        }.toString()
        val eventTime = System.currentTimeMillis()
        writer.execute {
            synchronized(com.idvb.android.diagnostics.DiagnosticHistory.lock) { runCatching {
                val directory = history.files("alignment").firstOrNull { it.name == "request-$requestId.lifecycle.jsonl" }?.parentFile
                    ?: boundSession?.let { history.directoryFor(it, "alignment") }
                    ?: history.directoryAt(eventTime, "alignment")
                directory.mkdirs()
                File(directory, "request-$requestId.lifecycle.jsonl").appendText(event + "\n")
            }.onFailure { android.util.Log.e("IDVB-Align", "Lifecycle recording failed: $requestId", it) } }
        }
    }

    private fun lifecycle(file: File, manifest: JsonObject): File = File(file.parentFile,
        "request-${manifest.getValue("requestId").jsonPrimitive.content}.lifecycle.jsonl")

    /** Takes ownership of frame, releasing it after asynchronous encoding, independent of rendering. */
    fun recordAsync(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String, onWritten: (Result<File>) -> Unit = {}) {
        if (!trace.completed.compareAndSet(false, true)) return
        val queued = System.nanoTime()
        writer.execute {
            trace.emit(AlignmentLogEvent("diagnostics.queue", durationNanos = System.nanoTime() - queued))
            val written = try { record(trace, context, frame, result, terminal) }
            finally { frame?.takeUnless(Bitmap::isRecycled)?.recycle() }
            onWritten(written)
        }
    }

    /** Synchronous test/export adapter; borrows the bitmap. ZIP is published atomically. */
    @Synchronized fun record(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String): Result<File> = synchronized(com.idvb.android.diagnostics.DiagnosticHistory.lock) { runCatching {
        val writeStarted = System.nanoTime()
        val directory = context.sessionId?.let { history.directoryFor(it, "alignment") }
            ?: history.directoryAt(trace.createdAtMillis, "alignment")
        directory.mkdirs()
        val stem = "alignment-${trace.createdAtMillis}-${trace.id.take(8)}"
        val temporary = File(directory, ".$stem.tmp")
        val destination = File(directory, "$stem.zip")
        val artifacts = linkedMapOf<String, Pair<Long, String>>()
        var serializeMs = 0.0
        var manifestWriteMs = 0.0
        var finalizeStarted = writeStarted
        var sourceComplete = false
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                fun add(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    artifacts[name] = bytes.size.toLong() to sha256(bytes)
                }
                if (frame != null && trace.captureArtifacts) {
                    val encoded = trace.measure("diagnostics.encode-capture") {
                        ByteArrayOutputStream().use { output ->
                            check(frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                            output.toByteArray()
                        }
                    }
                    trace.measure("diagnostics.write-capture") { add("captured.png", encoded) }
                }
                trace.measure("diagnostics.write-intermediates") {
                    trace.artifactSnapshot().forEach { (name, bytes) -> add(name, bytes) }
                }
                trace.measure("diagnostics.write-provenance") {
                    fun copyAssets(path: String) {
                        val names = app.assets.list(path).orEmpty()
                        if (names.isEmpty()) app.assets.open(path).use { add(path, it.readBytes()) }
                        else names.forEach { copyAssets("$path/$it") }
                    }
                    runCatching {
                        copyAssets("alignment-provenance.json")
                        copyAssets("alignment-source")
                        val provenance = app.assets.open("alignment-provenance.json").bufferedReader().use {
                            Json.parseToJsonElement(it.readText()).jsonObject
                        }
                        val files = provenance.getValue("files").jsonObject
                        check(files.isNotEmpty()) { "Empty source snapshot" }
                        files.forEach { (path, hash) ->
                            check(artifacts["alignment-source/$path"]?.second == hash.jsonPrimitive.content) {
                                "Source snapshot incomplete or changed: $path"
                            }
                        }
                        sourceComplete = true
                    }.onFailure { trace.emit(AlignmentLogEvent("diagnostics.provenance-unavailable", it.message.orEmpty())) }
                }
                val serializeStarted = System.nanoTime()
                val manifest = manifest(trace, context, frame, result, terminal, artifacts, sourceComplete).toString().encodeToByteArray()
                serializeMs = (System.nanoTime() - serializeStarted) / 1e6
                val manifestStarted = System.nanoTime()
                add("diagnostics.json", manifest)
                manifestWriteMs = (System.nanoTime() - manifestStarted) / 1e6
                finalizeStarted = System.nanoTime()
            }
            val finalizeMs = (System.nanoTime() - finalizeStarted) / 1e6
            val publishStarted = System.nanoTime()
            check(temporary.renameTo(destination)) { "无法提交对齐诊断包" }
            val publishMs = (System.nanoTime() - publishStarted) / 1e6
            val archiveComplete = System.nanoTime()
            // A completion receipt is separate: a closed ZIP cannot record its own finalization.
            // Copy/export APIs embed it in the exported package. Its own tiny write is excluded.
            completionFile(destination).writeText(buildJsonObject {
                put("schemaVersion", 1); put("requestId", trace.id)
                put("archiveWriteTotalMs", (archiveComplete - writeStarted) / 1e6)
                put("archiveCompletedAtMs", (archiveComplete - trace.startedNanos) / 1e6)
                put("manifestSerializationMs", serializeMs); put("manifestCompressionWriteMs", manifestWriteMs)
                put("zipFinalizationMs", finalizeMs); put("atomicPublishMs", publishMs)
                put("archiveBytes", destination.length())
                put("scope", "From writer start through ZIP close and atomic publish; queue, completion receipt write, retention pruning and later export are excluded.")
            }.toString())
            destination
        } finally { temporary.delete() }
    } }

    fun recentPackages(): List<File> = history.files("alignment").filter { file -> file.isFile &&
        file.name.startsWith("alignment-") && file.extension == "zip" }.sortedByDescending(File::lastModified)

    @Synchronized fun diagnosticsJson(file: File): Result<String> = runCatching {
        requireRecent(file)
        val manifest = ZipFile(file).use { zip -> zip.getInputStream(requireNotNull(zip.getEntry("diagnostics.json")))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject } }
        JsonObject(manifest + ("lifecycle" to buildJsonArray {
            lifecycle(file, manifest).takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank)?.forEach {
                runCatching { Json.parseToJsonElement(it) }.getOrNull()?.let(::add)
            }
        }) + ("diagnosticsWrite" to completionFile(file).takeIf(File::isFile)?.let {
            Json.parseToJsonElement(it.readText())
        }.let { it ?: JsonNull })).toString()
    }

    fun exportPackage(file: File, context: Context, destination: Uri): Result<Unit> = runCatching {
        requireNotNull(context.contentResolver.openOutputStream(destination)).use { out -> copyPackage(file, out) }
    }

    /** Include the post-close timing receipt in every exported/replay artifact. */
    @Synchronized fun copyPackage(file: File, destination: java.io.OutputStream) {
        requireRecent(file)
        ZipFile(file).use { source -> ZipOutputStream(destination).use { zip ->
            source.entries().asSequence().forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                source.getInputStream(entry).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            completionFile(file).takeIf(File::isFile)?.let { receipt ->
                zip.putNextEntry(ZipEntry("diagnostics-write.json"))
                receipt.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val manifest = source.getInputStream(requireNotNull(source.getEntry("diagnostics.json")))
                .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            lifecycle(file, manifest).takeIf(File::isFile)?.let { events ->
                zip.putNextEntry(ZipEntry("lifecycle.jsonl"))
                events.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } }
    }

    private fun completionFile(archive: File) = File(archive.parentFile, "${archive.name}.write.json")

    private fun requireRecent(file: File) = require(recentPackages().any { it.canonicalFile == file.canonicalFile }) { "对齐诊断已不存在" }

    private fun manifest(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String, artifacts: Map<String, Pair<Long, String>>, sourceComplete: Boolean) = buildJsonObject {
        put("schemaVersion", 1); put("requestId", trace.id); put("createdAtMillis", trace.createdAtMillis)
        put("sessionId", context.sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("methodId", context.methodId); put("terminal", terminal)
        put("productVersion", BuildConfig.PRODUCT_VERSION); put("buildVersion", BuildConfig.BUILD_VERSION)
        put("runtime", buildJsonObject {
            put("sdk", Build.VERSION.SDK_INT); put("androidRelease", Build.VERSION.RELEASE)
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
            put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) } })
            put("availableProcessors", Runtime.getRuntime().availableProcessors())
            put("openCvVersion", OpenCvRuntime.version)
            put("openCvAvailable", OpenCvRuntime.available)
            put("openCvThreads", if (OpenCvRuntime.available) JsonPrimitive(org.opencv.core.Core.getNumThreads()) else JsonNull)
        })
        put("map", Json.parseToJsonElement(Json.encodeToString(context.map)))
        put("floorKey", context.floorKey); put("captureSource", context.captureSource)
        put("screenWidth", context.screenWidth); put("screenHeight", context.screenHeight)
        put("frameWidth", frame?.width?.let(::JsonPrimitive) ?: JsonNull)
        put("frameHeight", frame?.height?.let(::JsonPrimitive) ?: JsonNull)
        put("viewport", buildJsonObject {
            put("x", context.viewport.x); put("y", context.viewport.y)
            put("width", context.viewport.width); put("height", context.viewport.height)
        })
        put("result", buildJsonObject {
            put("outcome", result?.outcome?.name?.let(::JsonPrimitive) ?: JsonNull)
            when (result) {
                is AlignmentResult.Aligned -> {
                    val t = result.transform
                    put("transform", buildJsonObject {
                        put("scale", t.scale); put("offsetX", t.offsetX); put("offsetY", t.offsetY)
                        put("referenceWidth", t.referenceWidth); put("referenceHeight", t.referenceHeight)
                    })
                    put("evidence", numbers(result.evidence.measurements + mapOf(
                        "visibleSupport" to (result.evidence.visibleSupport ?: Double.NaN),
                        "referenceSupport" to (result.evidence.referenceSupport ?: Double.NaN),
                        "meanResidualPixels" to (result.evidence.meanResidualPixels ?: Double.NaN))))
                }
                is AlignmentResult.Rejected -> { put("code", result.code); put("reason", result.reason) }
                is AlignmentResult.Unavailable -> { put("code", result.code); put("reason", result.reason) }
                null -> Unit
            }
        })
        put("timingContract", "Monotonic nanosecond spans. Durations are inclusive and nested; do not sum nested spans. render.first-draw measures CPU drawing, not GPU presentation. diagnostics packaging runs after the interaction and is timed separately.")
        put("events", buildJsonArray {
            trace.snapshot().forEachIndexed { index, event -> add(buildJsonObject {
                put("sequence", index); put("stage", event.stage); put("detail", event.detail)
                put("endMs", (event.timestampNanos - trace.startedNanos) / 1e6)
                event.durationNanos?.let {
                    put("startMs", (event.timestampNanos - it - trace.startedNanos) / 1e6); put("durationMs", it / 1e6)
                }
                put("measurements", numbers(event.measurements)); put("thresholds", numbers(event.thresholds))
                put("gates", buildJsonArray { event.gates.forEach { gate -> add(buildJsonObject {
                    put("name", gate.name); put("actual", number(gate.actual)); put("comparison", gate.comparison)
                    put("threshold", number(gate.threshold)); put("passed", gate.passed)
                }) } })
                put("series", buildJsonObject { event.series.forEach { (name, values) ->
                    put(name, buildJsonArray { values.forEach { add(number(it)) } })
                } })
                put("labels", buildJsonObject { event.labels.forEach { (name, value) -> put(name, value) } })
            }) }
        })
        put("artifacts", buildJsonObject { artifacts.forEach { (name, info) -> put(name, buildJsonObject {
            put("bytes", info.first); put("sha256", info.second)
        }) } })
        val inputsAvailable = "captured.png" in artifacts && "reference.png" in artifacts
        val readinessFrames = trace.snapshot().filter { it.stage == "readiness.frame" }
        val readinessReplayReady = sourceComplete && readinessFrames.isNotEmpty() && readinessFrames.all {
            "readiness-${it.measurements.getValue("attempt").toInt()}.argb" in artifacts
        }
        put("readinessFormat", "sampled-argb-v1")
        put("readinessReplayReady", readinessReplayReady)
        put("readinessReplayUnavailableReason", when {
            readinessReplayReady -> ""
            !trace.captureArtifacts -> "input-retention-disabled"
            !sourceComplete -> "source-provenance-unavailable"
            else -> "no-complete-readiness-samples"
        })
        val sourceAvailable = sourceComplete
        val dimensionsMatch = frame != null && frame.width.toDouble() == context.viewport.width && frame.height.toDouble() == context.viewport.height
        val replayReady = inputsAvailable && sourceAvailable && result != null && dimensionsMatch
        put("replayReady", replayReady)
        put("replayUnavailableReason", when {
            replayReady -> ""
            !trace.captureArtifacts -> "input-retention-disabled"
            !inputsAvailable -> "capture-or-reference-unavailable"
            !sourceAvailable -> "source-provenance-unavailable"
            !dimensionsMatch -> "frame-viewport-dimensions-mismatch"
            else -> "no-algorithm-outcome"
        })
        put("sourceSnapshotAvailable", sourceAvailable)
    }

    companion object {
        internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun number(value: Double): JsonElement = if (value.isFinite()) JsonPrimitive(value) else JsonNull
        private fun numbers(values: Map<String, Double>) = buildJsonObject { values.forEach { (key, value) -> put(key, number(value)) } }
    }
}
