package com.idvb.android.alignment

import android.content.Context
import android.util.Log
import com.idvb.android.BuildConfig
import com.idvb.android.diagnostics.DiagnosticHistory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Bounded samples and the actual detector configuration join the private feedback history. */
class AutoMapOpenDiagnostics(context: Context) {
    private val history = DiagnosticHistory(File(context.filesDir, "idvb/diagnostics"))
    private val writer = Executors.newSingleThreadExecutor()
    private val runId = UUID.randomUUID().toString()
    private val frames = ArrayDeque<JSONObject>()
    private var snapshots = 0
    private var closed = false
    private var snapshotLimitReported = false
    private val captureEvents = ArrayDeque<JSONObject>()
    private var configuration = AutoMapOpenConfig()
    private var captureMethod = "ACCESSIBILITY"
    private var intervalMs = 350L
    private val provenance = runCatching {
        context.assets.open("alignment-provenance.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
    @Synchronized fun configure(config: AutoMapOpenConfig, source: String, interval: Long) {
        configuration = config; captureMethod = source; intervalMs = interval
    }
    @Synchronized fun captureEvent(event: AlignmentLogEvent) {
        if (closed) return
        captureEvents.addLast(JSONObject().put("stage", event.stage).put("detail", event.detail)
            .put("timestampNanos", event.timestampNanos).put("durationNanos", event.durationNanos ?: JSONObject.NULL)
            .put("measurements", JSONObject(event.measurements)).put("thresholds", JSONObject(event.thresholds))
            .put("labels", JSONObject(event.labels)))
        while (captureEvents.size > 128) captureEvents.removeFirst()
        Log.i("IDVB-AutoMap", "run=$runId stage=${event.stage} detail=${event.detail} " +
            "durationNanos=${event.durationNanos} measurements=${event.measurements}")
    }
    @Synchronized fun frame(reference: AutoMapOpenReference, pixels: IntArray?, observation: AutoMapOpenObservation,
        nowMs: Long, elapsedMs: Double, startedAtMs: Long = nowMs, decisionMs: Double = 0.0,
        cleanViewport: Boolean = false, failure: String? = null, sampleId: String = "",
        frameSequence: Long? = null, frameReceivedNanos: Long? = null) {
        if (closed) return
        val recordingStarted = System.nanoTime()
        val comparison = observation.comparison
        val event = JSONObject().put("nowMs", nowMs).put("captureAndSampleMs", elapsedMs)
            .put("sampleStartedAtMs", startedAtMs).put("decisionMs", decisionMs).put("cleanViewport", cleanViewport)
            .put("failure", failure ?: JSONObject.NULL)
            .put("nextPollDelayMs", AutoMapOpenTiming.delayAfterSample(startedAtMs, nowMs, intervalMs))
            .put("captureMethod", captureMethod)
            .put("autoSampleId", sampleId).put("frameSequence", frameSequence ?: JSONObject.NULL)
            .put("frameReceivedNanos", frameReceivedNanos ?: JSONObject.NULL)
            .put("cycle", observation.openCycle).put("isOpen", observation.isOpen)
            .put("manualSuppressed", observation.manualSuppressed).put("transition", observation.transition.name)
            .put("score", comparison?.score ?: JSONObject.NULL)
            .put("color", comparison?.color ?: JSONObject.NULL)
            .put("brightness", comparison?.brightness ?: JSONObject.NULL)
            .put("edge", comparison?.edge ?: JSONObject.NULL)
            .put("offsetX", comparison?.offsetX ?: JSONObject.NULL).put("offsetY", comparison?.offsetY ?: JSONObject.NULL)
            .put("windowLeft", comparison?.windowLeft ?: JSONObject.NULL).put("windowWidth", comparison?.windowWidth ?: JSONObject.NULL)
            .put("pixels", pixels?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        frames.addLast(event)
        while (frames.size > 24) frames.removeFirst()
        event.put("diagnosticRecordingMs", (System.nanoTime() - recordingStarted) / 1e6)
        Log.i("IDVB-AutoMap", "run=$runId reference=${reference.id} cycle=${observation.openCycle} " +
            "open=${observation.isOpen} suppressed=${observation.manualSuppressed} transition=${observation.transition} " +
            "score=${comparison?.score} color=${comparison?.color} brightness=${comparison?.brightness} " +
            "edge=${comparison?.edge} captureAndSampleMs=$elapsedMs decisionMs=$decisionMs " +
            "sampleStartedAtMs=$startedAtMs cleanViewport=$cleanViewport failure=$failure")
    }
    @Synchronized fun event(detail: String, reference: AutoMapOpenReference?, sessionId: String?, requestId: String? = null,
        state: Map<String, String> = emptyMap()) {
        Log.i("IDVB-AutoMap", "run=$runId stage=$detail alignmentRequest=$requestId state=$state")
        if (closed || sessionId == null) return
        if (snapshots >= 64) {
            if (!snapshotLimitReported) {
                snapshotLimitReported = true
                Log.w("IDVB-AutoMap", "run=$runId stage=diagnostics.snapshot-limit detail=64-snapshots-retained; later events have logs only and cannot be spatially replayed")
            }
            return
        }
        val config = configuration
        val savedFrames = frames.toList()
        val savedCaptureEvents = captureEvents.toList()
        val source = captureMethod
        val interval = intervalMs
        val knownInput = savedFrames.any { !it.isNull("pixels") }
        val replayReady = reference != null && provenance != null && knownInput
        val number = ++snapshots
        // Snapshot immutable inputs here; JSON construction and serialization run on the writer.
        writer.execute { synchronized(DiagnosticHistory.lock) {
            val recordingStarted = System.nanoTime()
            runCatching {
                val data = JSONObject().put("schemaVersion", 3).put("algorithmVersion",
                    if (config.sidebarWidthFraction == null) "spatial-roi-v1" else "builtin-sidebar-window-v2")
                    .put("buildVersion", BuildConfig.BUILD_VERSION).put("runId", runId).put("detail", detail)
                    .put("sourceFingerprint", provenance?.optString("sourceFingerprint") ?: JSONObject.NULL)
                    .put("gitBaseCommit", provenance?.optString("gitBaseCommit") ?: JSONObject.NULL)
                    .put("replayReady", replayReady).put("replayScope", "spatial-comparisons; bounded state history")
                    .put("replayUnavailableReason", when {
                        reference == null -> "reference-unavailable"
                        provenance == null -> "source-provenance-unavailable"
                        !knownInput -> "no-known-captured-samples"
                        else -> JSONObject.NULL
                    })
                    .put("state", JSONObject(state))
                    .put("alignmentRequestId", requestId ?: JSONObject.NULL).put("sampleFormat", "ARGB32 32x24 bilinear")
                    .put("reference", reference?.let { JSONObject().put("id", it.id).put("targetPackage", it.targetPackage)
                        .put("screenWidth", it.screenWidth).put("screenHeight", it.screenHeight)
                        .put("region", JSONArray(it.region.toList())).put("pngSha256", it.pngSha256)
                        .put("sidebarAspectRatio", it.sidebarAspectRatio ?: JSONObject.NULL)
                        .put("pixels", JSONArray(it.signaturePixels.toList())) } ?: JSONObject.NULL)
                    .put("configuration", JSONObject().put("openThreshold", config.openThreshold).put("closeThreshold", config.closeThreshold)
                        .put("openFrames", config.openFrames).put("closeFrames", config.closeFrames)
                        .put("maximumAttempts", config.maximumAttempts).put("retryCooldownMs", config.retryCooldownMs)
                        .put("maximumFrameAgeMs", config.maximumFrameAgeMs).put("sidebarWidthFraction", config.sidebarWidthFraction ?: JSONObject.NULL)
                        .put("intervalMs", interval).put("captureMethod", source)
                        .put("watchStrategy", "continuous-current-frame-independent-of-touch")
                        .put("nonAlignmentBudgetMs", if (source == "MEDIA_PROJECTION") AutoMapOpenTiming.NON_ALIGNMENT_BUDGET_MS else JSONObject.NULL)
                        .put("windowSearchScales", JSONArray(listOf(.85, 1.0, 1.15))).put("windowSearchStepCells", .5)
                        .put("intervalOrigin", "sample-request-start").put("preparedViewport", "same-unoccluded-capture-readiness-still-required"))
                    .put("frames", JSONArray(savedFrames)).put("captureEvents", JSONArray(savedCaptureEvents)).toString()
                val folder = history.directoryFor(sessionId, "alignment")
                File(folder, "auto-map-open-$runId-$number.json").writeText(data, Charsets.UTF_8)
            }.onFailure { Log.e("IDVB-AutoMap", "Detector evidence unavailable", it) }
            Log.i("IDVB-AutoMap", "run=$runId stage=diagnostics.write durationNanos=${System.nanoTime() - recordingStarted}")
        } }
    }
    @Synchronized fun clearFrames() { frames.clear(); captureEvents.clear() }
    @Synchronized fun close() { closed = true; writer.shutdown() }
}
