package com.idvb.android.alignment

import kotlin.math.abs
import kotlin.math.hypot

data class AlignmentTestCase(
    val methodId: String,
    val request: AlignmentRequest,
    val expectedTransform: AlignmentTransform? = null,
    val expectedOutcome: AlignmentOutcome = AlignmentOutcome.ALIGNED,
    val maximumCornerErrorPixels: Double = 4.0,
    val maximumRelativeScaleError: Double = .025,
)

data class AlignmentTestReport(
    val result: AlignmentResult,
    val events: List<AlignmentLogEvent>,
    val elapsedMilliseconds: Double,
    val passed: Boolean,
    val maximumCornerErrorPixels: Double?,
    val relativeScaleError: Double?,
)

/** Shared replay contract for synthetic captures, saved frames and future algorithm comparisons. */
fun interface AlignmentTestRunner {
    /** Run off the UI thread. The caller retains and releases the input bitmap. */
    fun run(case: AlignmentTestCase): AlignmentTestReport
}

/** Uses the production registry and acceptance gates; never modifies map identity or display state. */
class AlignmentReplayRunner(private val registry: AlignmentRegistry,
    private val log: AlignmentLogSink = AlignmentLogSink.NONE) : AlignmentTestRunner {
    override fun run(case: AlignmentTestCase): AlignmentTestReport {
        require(case.maximumCornerErrorPixels >= 0 && case.maximumRelativeScaleError >= 0)
        val events = mutableListOf<AlignmentLogEvent>()
        val started = System.nanoTime()
        val sink = object : AlignmentLogSink {
            override fun record(event: AlignmentLogEvent) { events += event; log.emit(event) }
            override fun attach(name: String, bytes: () -> ByteArray) = log.attach(name, bytes)
            override fun attachOwned(name: String, bytes: () -> ByteArray) = log.attachOwned(name, bytes)
        }
        val result = registry.align(case.methodId, case.request, sink)
        val actual = (result as? AlignmentResult.Aligned)?.transform
        val expected = case.expectedTransform
        val error = if (actual != null && expected != null) {
            listOf(0.0 to 0.0, expected.referenceWidth.toDouble() to 0.0,
                0.0 to expected.referenceHeight.toDouble(),
                expected.referenceWidth.toDouble() to expected.referenceHeight.toDouble()).maxOf { (x, y) ->
                hypot(x * (actual.scale - expected.scale) + actual.offsetX - expected.offsetX,
                    y * (actual.scale - expected.scale) + actual.offsetY - expected.offsetY)
            }
        } else null
        val scaleError = if (actual != null && expected != null) abs(actual.scale / expected.scale - 1) else null
        val dimensionsMatch = actual == null || expected == null ||
            actual.referenceWidth == expected.referenceWidth && actual.referenceHeight == expected.referenceHeight
        val passed = case.expectedOutcome == result.outcome && dimensionsMatch &&
            (error == null || error <= case.maximumCornerErrorPixels) &&
            (scaleError == null || scaleError <= case.maximumRelativeScaleError)
        return AlignmentTestReport(result, events.toList(), (System.nanoTime() - started) / 1_000_000.0,
            passed, error, scaleError)
    }
}
