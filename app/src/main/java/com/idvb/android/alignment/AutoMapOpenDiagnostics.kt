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

/** Bounded pre-event samples and exact user reference join the existing private feedback history. */
class AutoMapOpenDiagnostics(context: Context) {
    private val history = DiagnosticHistory(File(context.filesDir, "idvb/diagnostics"))
    private val writer = Executors.newSingleThreadExecutor()
    private val runId = UUID.randomUUID().toString()
    private val frames = ArrayDeque<JSONObject>()
    private var snapshots = 0
    private var closed = false
    private val captureEvents = ArrayDeque<JSONObject>()
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
        cleanViewport: Boolean = false, failure: String? = null) {
        if (closed) return
        val comparison = observation.comparison
        val event = JSONObject().put("nowMs", nowMs).put("captureAndSampleMs", elapsedMs)
            .put("sampleStartedAtMs", startedAtMs).put("decisionMs", decisionMs).put("cleanViewport", cleanViewport)
            .put("failure", failure ?: JSONObject.NULL)
            .put("nextPollDelayMs", AutoMapOpenTiming.delayAfterSample(startedAtMs, nowMs))
            .put("cycle", observation.openCycle).put("isOpen", observation.isOpen)
            .put("manualSuppressed", observation.manualSuppressed).put("transition", observation.transition.name)
            .put("score", comparison?.score ?: JSONObject.NULL)
            .put("color", comparison?.color ?: JSONObject.NULL)
            .put("brightness", comparison?.brightness ?: JSONObject.NULL)
            .put("edge", comparison?.edge ?: JSONObject.NULL)
            .put("offsetX", comparison?.offsetX ?: JSONObject.NULL).put("offsetY", comparison?.offsetY ?: JSONObject.NULL)
            .put("pixels", pixels?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        frames.addLast(event)
        while (frames.size > 24) frames.removeFirst()
        Log.i("IDVB-AutoMap", "run=$runId reference=${reference.id} cycle=${observation.openCycle} " +
            "open=${observation.isOpen} suppressed=${observation.manualSuppressed} transition=${observation.transition} " +
            "score=${comparison?.score} color=${comparison?.color} brightness=${comparison?.brightness} " +
            "edge=${comparison?.edge} captureAndSampleMs=$elapsedMs decisionMs=$decisionMs " +
            "sampleStartedAtMs=$startedAtMs cleanViewport=$cleanViewport failure=$failure")
    }
    @Synchronized fun event(detail: String, reference: AutoMapOpenReference?, sessionId: String?, requestId: String? = null,
        state: Map<String, String> = emptyMap()) {
        Log.i("IDVB-AutoMap", "run=$runId stage=$detail alignmentRequest=$requestId state=$state")
        if (closed || reference == null || snapshots >= 64 || sessionId == null) return
        val config = AutoMapOpenConfig()
        val data = JSONObject().put("schemaVersion", 2).put("algorithmVersion", "spatial-roi-v1")
            .put("buildVersion", BuildConfig.BUILD_VERSION).put("runId", runId).put("detail", detail)
            .put("state", JSONObject(state))
            .put("alignmentRequestId", requestId ?: JSONObject.NULL).put("sampleFormat", "ARGB32 32x24 bilinear")
            .put("reference", JSONObject().put("id", reference.id).put("targetPackage", reference.targetPackage)
                .put("screenWidth", reference.screenWidth).put("screenHeight", reference.screenHeight)
                .put("region", JSONArray(reference.region.toList())).put("pngSha256", reference.pngSha256)
                .put("pixels", JSONArray(reference.signaturePixels.toList())))
            .put("configuration", JSONObject().put("openThreshold", config.openThreshold).put("closeThreshold", config.closeThreshold)
                .put("openFrames", config.openFrames).put("closeFrames", config.closeFrames)
                .put("maximumAttempts", config.maximumAttempts).put("retryCooldownMs", config.retryCooldownMs)
                .put("maximumFrameAgeMs", config.maximumFrameAgeMs).put("intervalMs", AutoMapOpenTiming.INTERVAL_MS)
                .put("intervalOrigin", "sample-request-start").put("preparedViewport", "same-unoccluded-capture-readiness-still-required"))
            .put("frames", JSONArray(frames.toList())).put("captureEvents", JSONArray(captureEvents.toList())).toString()
        val number = ++snapshots
        writer.execute { synchronized(DiagnosticHistory.lock) {
            runCatching {
                val folder = history.directoryFor(sessionId, "alignment")
                File(folder, "auto-map-open-$runId-$number.json").writeText(data, Charsets.UTF_8)
            }.onFailure { Log.e("IDVB-AutoMap", "Detector evidence unavailable", it) }
        } }
    }
    @Synchronized fun clearFrames() { frames.clear(); captureEvents.clear() }
    @Synchronized fun close() { closed = true; writer.shutdown() }
}
