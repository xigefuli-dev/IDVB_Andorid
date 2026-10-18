package com.idvb.android.recognize

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.emit
import com.idvb.android.data.ScreenCaptureMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared by every actual capture consumer and the overlay's bounded lifecycle probes. */
object CaptureHealthMonitor {
    private val machines = ScreenCaptureMethod.entries.associateWith { CaptureHealthMachine(it) }
    private val mutable = MutableStateFlow(machines.mapValues { it.value.snapshot })
    val state = mutable.asStateFlow()

    @Synchronized fun beginSession(source: ScreenCaptureMethod) {
        machines.getValue(source).beginSession(); publish()
    }
    @Synchronized fun ticket(source: ScreenCaptureMethod) = machines.getValue(source).ticket()
    @Synchronized private fun record(source: ScreenCaptureMethod, ticket: CaptureHealthMachine.Ticket, error: Throwable?) {
        if (machines.getValue(source).record(ticket, SystemClock.elapsedRealtime(), error)) publish()
    }
    private fun publish() { mutable.value = machines.mapValues { it.value.snapshot } }

    fun failure(source: ScreenCaptureMethod, error: Throwable, invalidate: Boolean = false) {
        if (invalidate) beginSession(source)
        record(source, ticket(source), error)
        Log.w("IDVBCapture", "health source=$source reason=${(error as? CaptureException)?.failure} error=${error.message}")
    }

    /** Takes ownership on failure; a black image never reaches map recognition or diagnostic image storage. */
    fun assess(source: ScreenCaptureMethod, ticket: CaptureHealthMachine.Ticket, result: Result<Bitmap>,
        log: AlignmentLogSink = AlignmentLogSink.NONE): Result<Bitmap> {
        val started = System.nanoTime()
        var assessment: BlackFrameDetector.Assessment? = null
        val checked = result.fold(onSuccess = { bitmap ->
            runCatching {
                assessment = BlackFrameDetector.assess(bitmap.width, bitmap.height, bitmap::getPixel)
                if (assessment!!.isBlack) throw CaptureException(CaptureFailure.BLACK_FRAME, "截图为黑屏，可能被系统拦截；请检查游戏防护或重新授权")
                bitmap
            }.onFailure { bitmap.recycle() }
        }, onFailure = { Result.failure(it) })
        val assessmentNanos = System.nanoTime() - started
        record(source, ticket, checked.exceptionOrNull())
        val diagnosticStarted = System.nanoTime()
        log.emit(AlignmentLogEvent("capture.health", if (checked.isSuccess) "usable" else "unavailable",
            durationNanos = assessmentNanos,
            measurements = assessment?.let { mapOf("samples" to it.samples.toDouble(), "darkSamples" to it.darkSamples.toDouble(),
                "darkFraction" to it.darkFraction, "maximumChannel" to it.maximumChannel.toDouble()) } ?: emptyMap(),
            thresholds = mapOf("channelLimit" to BlackFrameDetector.CHANNEL_LIMIT.toDouble(), "blackFraction" to BlackFrameDetector.BLACK_FRACTION,
                "gridColumns" to BlackFrameDetector.COLUMNS.toDouble(), "gridRows" to BlackFrameDetector.ROWS.toDouble()),
            labels = mapOf("source" to source.name, "policy" to BlackFrameDetector.VERSION,
                "failureType" to ((checked.exceptionOrNull() as? CaptureException)?.failure?.name ?: "none-or-unclassified"),
                "errorCode" to (checked.exceptionOrNull() as? CaptureException)?.errorCode.toString(),
                "reason" to checked.exceptionOrNull()?.message.orEmpty(), "costScope" to "in-memory-assessment; nested-in-capture; excludes-diagnostic-log",
                "replay" to if (assessment?.isBlack == true) "not-replayable-black-input-discarded; statistics-only" else "unchanged-consumer-input-policy")))
        if (checked.isFailure) Log.w("IDVBCapture", "health source=$source result=${checked.exceptionOrNull()?.message} assessment=$assessment")
        log.emit(AlignmentLogEvent("capture.health-diagnostic-cost", durationNanos = System.nanoTime() - diagnosticStarted,
            labels = mapOf("costScope" to "health-event-recording; nested-in-capture; excludes-assessment")))
        return checked
    }
}
