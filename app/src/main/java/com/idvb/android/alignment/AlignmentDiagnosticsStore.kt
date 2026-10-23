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
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AlignmentDiagnosticContext(val methodId: String, val map: MapRecord, val floorKey: String,
    val viewport: ScreenRect, val screenWidth: Int, val screenHeight: Int, val captureSource: String,
    val sessionId: String? = null, val floorSelectedByIndicator: Boolean = false)

/** Local, bounded history. Numeric traces are always retained, including unavailable/cancelled attempts. */
class AlignmentDiagnosticsStore(context: Context) {
    private val app = context.applicationContext
    private val history = com.idvb.android.diagnostics.DiagnosticHistory(File(app.filesDir, "idvb/diagnostics"))
    private val requestSessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "idvb-alignment-diagnostics").apply { isDaemon = true }
    }
    init {
        // Disk-only recovery: no capture, rendering, navigation or consent state changes.
        val pending = com.idvb.android.diagnostics.DiagnosticWriteTracker.begin(File(app.filesDir, "idvb/diagnostics"), "alignment-recovery")
        writer.execute { try { history.preservingFiles {
            File(app.filesDir, "idvb/diagnostics/spool").listFiles().orEmpty().filter(File::isDirectory).forEach { folder ->
                runCatching {
                    val state = File(folder, "state.json").takeIf(File::isFile)?.readText()
                        ?.let { Json.parseToJsonElement(it).jsonObject } ?: return@runCatching
                    if (state["status"]?.jsonPrimitive?.content != "ready-to-publish") return@runCatching
                    val restored = AlignmentArchiveTransaction.publish(File(app.filesDir, "idvb/diagnostics"), AlignmentJournal(folder))
                    android.util.Log.i("IDVB-Align", "Recovered committed replay inputs: ${restored.name}")
                    history.discardCommittedJournal(folder)
                }.onFailure { android.util.Log.w("IDVB-Align", "Replay recovery remains pending: ${folder.name}", it) }
            }
        } } finally { pending.close() } }
    }

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
        val pending = com.idvb.android.diagnostics.DiagnosticWriteTracker.begin(File(app.filesDir, "idvb/diagnostics"), "alignment-lifecycle", requestId)
        try { writer.execute { try {
            synchronized(com.idvb.android.diagnostics.DiagnosticHistory.lock) { runCatching {
                val directory = history.files("alignment").firstOrNull { it.name == "request-$requestId.lifecycle.jsonl" }?.parentFile
                    ?: boundSession?.let { history.directoryFor(it, "alignment") }
                    ?: history.directoryAt(eventTime, "alignment")
                directory.mkdirs()
                File(directory, "request-$requestId.lifecycle.jsonl").appendText(event + "\n")
            }.onFailure { android.util.Log.e("IDVB-Align", "Lifecycle recording failed: $requestId", it) } }
        } finally { pending.close() } } } catch (error: Throwable) { pending.close(); throw error }
    }

    private fun lifecycle(file: File, manifest: JsonObject): File = File(file.parentFile,
        "request-${manifest.getValue("requestId").jsonPrimitive.content}.lifecycle.jsonl")

    /** Owns the frame. Spool rows and release before enqueueing, even behind a slow writer. */
    fun recordAsync(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String, onWritten: (Result<File>) -> Unit = {}) {
        if (!trace.completed.compareAndSet(false, true)) return
        val pending = com.idvb.android.diagnostics.DiagnosticWriteTracker.begin(File(app.filesDir, "idvb/diagnostics"), "alignment-archive", trace.id)
        val frameSize = frame?.let { it.width to it.height }
        val prepareStarted = System.nanoTime()
        val preparedFrame = runCatching {
            trace.persistTo(File(app.filesDir, "idvb/diagnostics/spool"))
            if (trace.captureArtifacts && frame != null) AlignmentFrameSpool.write(
                requireNotNull(trace.journal).directory, frame) else null
        }
        frame?.takeUnless(Bitmap::isRecycled)?.recycle()
        trace.emit(AlignmentLogEvent("diagnostics.spool-capture", durationNanos = System.nanoTime() - prepareStarted,
            labels = mapOf("storage" to "private-file", "queuedBitmapBytes" to "0",
                "status" to if (preparedFrame.isSuccess) "complete" else "failed",
                "costScope" to "before-archive-queue; uncompressed-row-stream; after-request-terminal")))
        val queued = System.nanoTime()
        try { writer.execute { try {
            val written = runCatching {
                trace.emit(AlignmentLogEvent("diagnostics.queue", durationNanos = System.nanoTime() - queued))
                AlignmentLogcat.write(trace, context.methodId, context.map.id, context.floorKey)
                val spool = preparedFrame.getOrThrow()
                recordWithFrameSize(trace, context, null, result, terminal, frameSize,
                    spool?.let { input -> { output -> input.writePng(output) } }).getOrThrow()
            }
            written.onFailure { error ->
                // Keep numeric/cancellation evidence when an archive cannot be published.
                // Explicitly non-replayable; never report a missing image as success.
                trace.emit(AlignmentLogEvent("diagnostics.write-failed", error.message.orEmpty(),
                    labels = mapOf("status" to "archive-unavailable", "replay" to "not-replayable")))
                history.preservingFiles { runCatching {
                    val directory = context.sessionId?.let { history.directoryFor(it, "alignment") }
                        ?: history.directoryAt(trace.createdAtMillis, "alignment")
                    AlignmentJournal.publish(File(directory, "request-${trace.id}.failed.json")) {
                        writeManifest(it, trace, context, frameSize, result, terminal, emptyMap(), false)
                    }
                }.onFailure { android.util.Log.e("IDVB-Align", "Numeric failure receipt unavailable: ${trace.id}", it) } }
            }
            if (written.isSuccess) trace.releaseWrittenArtifacts()
            else trace.retainUnpublishedEvidence()
            onWritten(written)
        } finally { pending.close() } } } catch (error: Throwable) {
            trace.retainUnpublishedEvidence(); pending.close(); throw error
        }
    }

    /** Wait off the UI thread; never discard accepted replay inputs or delete their files. */
    internal fun awaitIdle() { writer.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS) }
    internal fun releaseCompletedBindings() { requestSessions.clear() }

    /** Synchronous test/export adapter; borrows the bitmap. ZIP is published atomically. */
    @Synchronized fun record(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String): Result<File> =
        recordWithFrameSize(trace, context, frame, result, terminal, frame?.let { it.width to it.height })
            .also { if (it.isSuccess) trace.releaseWrittenArtifacts() else trace.retainUnpublishedEvidence() }

    private fun recordWithFrameSize(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frame: Bitmap?,
        result: AlignmentResult?, terminal: String, frameSize: Pair<Int, Int>?,
        capturedPng: ((OutputStream) -> Unit)? = null): Result<File> = history.preservingFiles { runCatching {
        val writeStarted = System.nanoTime()
        trace.persistTo(File(app.filesDir, "idvb/diagnostics/spool"))
        val journal = requireNotNull(trace.journal)
        val directory = context.sessionId?.let { history.directoryFor(it, "alignment") }
            ?: history.directoryAt(trace.createdAtMillis, "alignment")
        val destination = File(directory, "alignment-${trace.createdAtMillis}-${trace.id.take(8)}.zip")
        if (trace.captureArtifacts) {
            if (capturedPng != null) trace.attachStream("captured.png", capturedPng)
            else if (frame != null && !frame.isRecycled) trace.attachStream("captured.png") {
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法编码诊断截图" }
            }
        }
        // Background evidence writes (including captured.png) must be on disk before staged inputs are hashed.
        trace.measure("diagnostics.await-spool") { trace.awaitSpool() }
        var sourceComplete = false
        var sourceSnapshotDigests: Map<String, Pair<Long, String>> = emptyMap()
        trace.measure("diagnostics.stage-provenance") {
            runCatching {
                fun copyAssets(path: String) {
                    val names = app.assets.list(path).orEmpty()
                    if (names.isEmpty()) AlignmentJournal.publish(File(journal.artifacts, path)) { output ->
                        app.assets.open(path).use { it.copyTo(output, 64 * 1024) }
                    } else names.forEach { copyAssets("$path/$it") }
                }
                copyAssets("alignment-provenance.json")
                copyAssets("alignment-source")
                // The snapshot is a build input: identical bytes for every request of this process.
                // Verify it against the manifest once, then bind later requests to the same digests
                // after a size check. publish() still re-digests every entry while it copies, so a
                // staged file that changed under us fails the archive instead of being trusted.
                val cached = verifiedSourceSnapshot
                if (cached != null && cached.all { (path, known) ->
                        File(journal.artifacts, path).length() == known.first }) {
                    sourceSnapshotDigests = cached
                    trace.emit(AlignmentLogEvent("diagnostics.provenance-verified", "process-cached-snapshot",
                        measurements = mapOf("files" to cached.size.toDouble()),
                        labels = mapOf("policy" to "manifest-verified-once-per-process; staged-size-checked; digests-reverified-during-archive-copy")))
                    sourceComplete = true
                } else {
                    val provenance = Json.parseToJsonElement(File(journal.artifacts, "alignment-provenance.json").readText()).jsonObject
                    val files = provenance.getValue("files").jsonObject
                    check(files.isNotEmpty()) { "Empty source snapshot" }
                    val digests = linkedMapOf<String, Pair<Long, String>>()
                    files.forEach { (path, hash) ->
                        val staged = File(journal.artifacts, "alignment-source/$path")
                        val expected = hash.jsonPrimitive.content
                        check(AlignmentArchiveTransaction.sha256(staged) == expected) {
                            "Source snapshot incomplete or changed: $path"
                        }
                        digests["alignment-source/$path"] = staged.length() to expected
                    }
                    File(journal.artifacts, "alignment-provenance.json").let {
                        digests["alignment-provenance.json"] = it.length() to AlignmentArchiveTransaction.sha256(it)
                    }
                    verifiedSourceSnapshot = digests
                    sourceSnapshotDigests = digests
                    trace.emit(AlignmentLogEvent("diagnostics.provenance-verified", "manifest-verified",
                        measurements = mapOf("files" to digests.size.toDouble()),
                        labels = mapOf("policy" to "full-manifest-digest-verification; result-cached-for-this-process")))
                    sourceComplete = true
                }
            }.onFailure {
                sourceSnapshotDigests = emptyMap()
                trace.emit(AlignmentLogEvent("diagnostics.provenance-unavailable", it.message.orEmpty()))
            }
        }
        val reusable = sourceSnapshotDigests
        val artifacts = linkedMapOf<String, Pair<Long, String>>()
        var reusedDigests = 0
        trace.measure("diagnostics.hash-staged-inputs") {
            journal.artifacts.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "diagnostics.json" }
                .forEach { file ->
                    val name = file.relativeTo(journal.artifacts).invariantSeparatorsPath
                    val length = file.length()
                    val known = reusable[name]?.takeIf { it.first == length }
                    if (known != null) reusedDigests++
                    artifacts[name] = known ?: (length to AlignmentArchiveTransaction.sha256(file))
                }
        }
        trace.emit(AlignmentLogEvent("diagnostics.staged-input-digests",
            measurements = mapOf("files" to artifacts.size.toDouble(), "reusedBuildInputDigests" to reusedDigests.toDouble()),
            labels = mapOf("policy" to "request-evidence-hashed-here; immutable-build-inputs-reuse-this-process-verified-digests")))
        val serializeStarted = System.nanoTime()
        AlignmentJournal.publish(File(journal.artifacts, "diagnostics.json")) {
            writeManifest(it, trace, context, frameSize, result, terminal, artifacts, sourceComplete)
        }
        val serializeMs = (System.nanoTime() - serializeStarted) / 1e6
        // Commit plan is durable before ZIP creation. A restarted writer uses these exact source bytes.
        AlignmentArchiveTransaction.prepare(File(app.filesDir, "idvb/diagnostics"), journal, destination, artifacts)
        val publishStarted = System.nanoTime()
        AlignmentArchiveTransaction.publish(File(app.filesDir, "idvb/diagnostics"), journal)
        val archiveComplete = System.nanoTime()
        runCatching { AlignmentJournal.publish(completionFile(destination)) { output ->
            output.write(buildJsonObject {
                put("schemaVersion", 1); put("requestId", trace.id)
                put("archiveWriteTotalMs", (archiveComplete - writeStarted) / 1e6)
                put("archiveCompletedAtMs", (archiveComplete - trace.startedNanos) / 1e6)
                put("manifestSerializationMs", serializeMs)
                put("archiveCompressionVerificationPublishMs", (archiveComplete - publishStarted) / 1e6)
                put("archiveBytes", destination.length())
                put("scope", "Private input staging, streaming manifest, durable commit plan, ZIP and atomic publish; prequeue capture spool and receipt write are separate.")
            }.toString().toByteArray())
        } }.onFailure { android.util.Log.w("IDVB-Align", "Archive committed; timing receipt unavailable: ${trace.id}", it) }
        destination
    } }

    /** Write one event at a time; never construct a second full trace tree/string/byte array. */
    private fun writeManifest(output: OutputStream, trace: AlignmentTrace, context: AlignmentDiagnosticContext,
        frameSize: Pair<Int, Int>?, result: AlignmentResult?, terminal: String,
        artifacts: Map<String, Pair<Long, String>>, sourceComplete: Boolean) {
        val metadata = manifest(trace, context, frameSize, result, terminal, artifacts, sourceComplete).toString()
        output.write(metadata.dropLast(1).toByteArray())
        output.write(",\"events\":[".toByteArray())
        var sequence = 0
        trace.forEachEvent { event ->
            if (sequence > 0) output.write(','.code)
            output.write(eventJson(trace, sequence++, event).toString().toByteArray())
        }
        output.write("]}".toByteArray())
    }

    private fun eventJson(trace: AlignmentTrace, index: Int, event: AlignmentLogEvent) = buildJsonObject {
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
    }

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

    private fun manifest(trace: AlignmentTrace, context: AlignmentDiagnosticContext, frameSize: Pair<Int, Int>?,
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
        put("floorSelectedByIndicator", context.floorSelectedByIndicator)
        put("screenWidth", context.screenWidth); put("screenHeight", context.screenHeight)
        put("frameWidth", frameSize?.first?.let(::JsonPrimitive) ?: JsonNull)
        put("frameHeight", frameSize?.second?.let(::JsonPrimitive) ?: JsonNull)
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
        put("artifacts", buildJsonObject { artifacts.forEach { (name, info) -> put(name, buildJsonObject {
            put("bytes", info.first); put("sha256", info.second)
        }) } })
        val inputsAvailable = "captured.png" in artifacts && "reference.png" in artifacts
        var floorTemplates = 0
        var floorTemplatesComplete = true
        var floorSelected = false
        var readinessFrames = 0
        var readinessComplete = true
        trace.forEachEvent { event -> when (event.stage) {
            "floor-indicator.template-source" -> {
                floorTemplates++
                floorTemplatesComplete = floorTemplatesComplete && "floor-template-${event.labels["template"]}" in artifacts
            }
            "floor-indicator.selected" -> floorSelected = true
            "readiness.frame" -> {
                readinessFrames++
                readinessComplete = readinessComplete && "readiness-${event.measurements["attempt"]?.toInt()}.argb" in artifacts
            }
        } }
        val floorReplayReady = sourceComplete && floorSelected && "floor-indicator.png" in artifacts &&
            floorTemplates == 5 && floorTemplatesComplete && trace.recordingFailures == 0
        put("floorIndicatorFormat", FloorIndicatorPolicy.VERSION)
        put("floorIndicatorReplayReady", floorReplayReady)
        put("floorIndicatorReplayUnavailableReason", when {
            floorReplayReady -> ""
            !trace.captureArtifacts -> "input-retention-disabled"
            !sourceComplete -> "source-provenance-unavailable"
            !floorSelected -> "not-selected; see-floor-indicator-events-for-disabled-input-error-or-cancellation"
            else -> "indicator-or-template-inputs-incomplete"
        })
        val readinessReplayReady = sourceComplete && readinessFrames > 0 && readinessComplete && trace.recordingFailures == 0
        put("readinessFormat", "sampled-argb-v1")
        put("readinessReplayReady", readinessReplayReady)
        put("readinessReplayUnavailableReason", when {
            readinessReplayReady -> ""
            !trace.captureArtifacts -> "input-retention-disabled"
            !sourceComplete -> "source-provenance-unavailable"
            else -> "no-complete-readiness-samples"
        })
        val sourceAvailable = sourceComplete
        val dimensionsMatch = frameSize != null && frameSize.first.toDouble() == context.viewport.width && frameSize.second.toDouble() == context.viewport.height
        val replayReady = inputsAvailable && sourceAvailable && result != null && dimensionsMatch && trace.recordingFailures == 0
        put("recordingFailures", trace.recordingFailures)
        put("replayReady", replayReady)
        put("replayUnavailableReason", when {
            replayReady -> ""
            trace.recordingFailures > 0 -> "event-journal-incomplete"
            !trace.captureArtifacts -> "input-retention-disabled"
            !inputsAvailable -> "capture-or-reference-unavailable"
            !sourceAvailable -> "source-provenance-unavailable"
            !dimensionsMatch -> "frame-viewport-dimensions-mismatch"
            else -> "no-algorithm-outcome"
        })
        put("sourceSnapshotAvailable", sourceAvailable)
    }

    companion object {
        /** The bundled source snapshot is an immutable build input. Verifying its manifest digests
         * once per process removes a full re-read of several hundred files from every request. */
        @Volatile private var verifiedSourceSnapshot: Map<String, Pair<Long, String>>? = null
        internal fun forgetVerifiedSourceSnapshot() { verifiedSourceSnapshot = null }
        internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun number(value: Double): JsonElement = if (value.isFinite()) JsonPrimitive(value) else JsonNull
        private fun numbers(values: Map<String, Double>) = buildJsonObject { values.forEach { (key, value) -> put(key, number(value)) } }
    }
}
