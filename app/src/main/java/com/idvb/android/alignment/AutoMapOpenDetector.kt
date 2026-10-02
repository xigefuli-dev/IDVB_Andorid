package com.idvb.android.alignment

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Spatial, privately captured 32 x 24 ROI. Pixels are owned by the caller only during signature(). */
class AutoMapOpenSignature internal constructor(
    internal val luminance: DoubleArray,
    internal val redChroma: DoubleArray,
    internal val blueChroma: DoubleArray,
    val luminanceDeviation: Double,
    val chromaDeviation: Double,
    val edgeStrength: Double,
)

data class AutoMapOpenComparison(
    val score: Double,
    val color: Double,
    val brightness: Double,
    val edge: Double,
    val offsetX: Int,
    val offsetY: Int,
    val windowLeft: Double = 0.0,
    val windowWidth: Double = 1.0,
)

data class AutoMapOpenConfig(
    val openThreshold: Double = .85,
    val closeThreshold: Double = .72,
    val openFrames: Int = 2,
    val closeFrames: Int = 2,
    val maximumAttempts: Int = 3,
    val retryCooldownMs: Long = 1_000L,
    val maximumFrameAgeMs: Long = 1_000L,
    val sidebarWidthFraction: Double? = null,
) {
    init {
        require(closeThreshold in 0.0..1.0 && openThreshold in 0.0..1.0 && closeThreshold < openThreshold)
        require(openFrames > 0 && closeFrames > 0 && maximumAttempts > 0 && retryCooldownMs >= 0L && maximumFrameAgeMs > 0L)
        require(sidebarWidthFraction == null || sidebarWidthFraction.isFinite() && sidebarWidthFraction > 0.0 && sidebarWidthFraction <= 1.0)
    }
}

enum class AutoMapOpenTransition { NONE, OPENED, CLOSED }

data class AutoMapOpenObservation(
    val transition: AutoMapOpenTransition,
    val isOpen: Boolean,
    val openCycle: Long,
    val manualSuppressed: Boolean,
    val comparison: AutoMapOpenComparison?,
) {
    val unknown: Boolean get() = comparison == null
}

/**
 * Single-thread-owned presence and attempt state. This never chooses map identity or validates
 * an alignment. The service owns screenshot scheduling, actual cancellation and display ownership.
 */
class AutoMapOpenDetector(
    private val reference: AutoMapOpenSignature,
    val config: AutoMapOpenConfig = AutoMapOpenConfig(),
) {
    var isOpen: Boolean = false
        private set
    var openCycle: Long = 0L
        private set
    var manualSuppressed: Boolean = false
        private set
    var attemptsInCycle: Int = 0
        private set
    var alignmentInFlight: Boolean = false
        private set
    var alignedInCycle: Boolean = false
        private set
    private var consecutiveOpen = 0
    private var consecutiveClose = 0
    private var frameAllowsAlignment = false
    private var nextAttemptAtMs = Long.MIN_VALUE
    private var lastFrameAtMs = Long.MIN_VALUE

    init { require(isUsableReference(reference)) { "开图参照区域缺少可区分的细节，请重新选择包含固定界面的区域" } }

    /** A failed capture is unknown, never evidence that the game map closed. */
    fun observe(candidate: AutoMapOpenSignature?, nowMs: Long): AutoMapOpenObservation {
        return observeComparison(candidate?.let(::compareCandidate), nowMs)
    }

    /** Immutable inputs; the costly spatial search runs on the sampling worker. */
    fun compareCandidate(candidate: AutoMapOpenSignature): AutoMapOpenComparison =
        config.sidebarWidthFraction?.let { compareSidebar(reference, candidate, it) } ?: compare(reference, candidate)

    /** Presence/attempt bookkeeping remains owned by the main thread. */
    fun observeComparison(comparison: AutoMapOpenComparison?, nowMs: Long): AutoMapOpenObservation {
        lastFrameAtMs = nowMs
        var transition = AutoMapOpenTransition.NONE
        frameAllowsAlignment = comparison != null && comparison.score >= config.openThreshold
        when {
            comparison == null -> { consecutiveOpen = 0; consecutiveClose = 0 }
            comparison.score >= config.openThreshold -> {
                consecutiveClose = 0
                if (!isOpen && ++consecutiveOpen >= config.openFrames) {
                    isOpen = true
                    openCycle++
                    attemptsInCycle = 0
                    alignmentInFlight = false
                    alignedInCycle = false
                    nextAttemptAtMs = Long.MIN_VALUE
                    consecutiveOpen = 0
                    transition = AutoMapOpenTransition.OPENED
                }
            }
            comparison.score <= config.closeThreshold -> {
                consecutiveOpen = 0
                if (++consecutiveClose >= config.closeFrames) {
                    if (isOpen) transition = AutoMapOpenTransition.CLOSED
                    isOpen = false
                    // Only observed closed frames release a manual override, even if it was
                    // issued before the opening debounce completed.
                    manualSuppressed = false
                    alignmentInFlight = false
                    consecutiveClose = 0
                }
            }
            else -> { consecutiveOpen = 0; consecutiveClose = 0 }
        }
        return AutoMapOpenObservation(transition, isOpen, openCycle, manualSuppressed, comparison)
    }

    fun shouldAttemptAlignment(nowMs: Long): Boolean = isOpen && frameAllowsAlignment &&
        !manualSuppressed && !alignmentInFlight && !alignedInCycle &&
        attemptsInCycle < config.maximumAttempts && nowMs >= nextAttemptAtMs &&
        nowMs >= lastFrameAtMs && nowMs - lastFrameAtMs <= config.maximumFrameAgeMs

    /** Returns the cycle token that must accompany the asynchronous completion. */
    fun alignmentStarted(nowMs: Long): Long? {
        if (!shouldAttemptAlignment(nowMs)) return null
        attemptsInCycle++
        alignmentInFlight = true
        nextAttemptAtMs = cooldownAfter(nowMs)
        return openCycle
    }

    fun alignmentFinished(success: Boolean, nowMs: Long, cycle: Long): Boolean {
        if (!isOpen || manualSuppressed || cycle != openCycle || !alignmentInFlight) return false
        alignmentInFlight = false
        alignedInCycle = success
        nextAttemptAtMs = cooldownAfter(nowMs)
        return true
    }

    /** The service cancels the request or loses its display; a fresh frame may resume this cycle. */
    fun alignmentInterrupted() {
        alignmentInFlight = false
        alignedInCycle = false
        frameAllowsAlignment = false
        // Keep identity, physical presence, manual suppression, attempt count and retry deadline.
    }

    /** Suppression belongs to the physical opening, not to whether our overlay is visible. */
    fun manualClose() {
        manualSuppressed = true
        alignmentInFlight = false
        consecutiveOpen = 0
    }

    fun reset() {
        isOpen = false
        manualSuppressed = false
        attemptsInCycle = 0
        alignmentInFlight = false
        alignedInCycle = false
        consecutiveOpen = 0
        consecutiveClose = 0
        frameAllowsAlignment = false
        lastFrameAtMs = Long.MIN_VALUE
        nextAttemptAtMs = Long.MIN_VALUE
        // Cycle IDs stay monotonic so an old callback cannot complete a task after reset/reopen.
    }

    private fun cooldownAfter(nowMs: Long): Long = if (nowMs > Long.MAX_VALUE - config.retryCooldownMs)
        Long.MAX_VALUE else nowMs + config.retryCooldownMs

    companion object {
        const val WIDTH = 32
        const val HEIGHT = 24
        const val PIXELS = WIDTH * HEIGHT
        private const val MOTION_RADIUS = 1

        fun signature(argb: IntArray): AutoMapOpenSignature {
            require(argb.size == PIXELS) { "自动开图检测需要 32 x 24 区域样本" }
            val luminance = DoubleArray(PIXELS)
            val red = DoubleArray(PIXELS)
            val blue = DoubleArray(PIXELS)
            argb.forEachIndexed { i, pixel ->
                val r = (pixel ushr 16 and 255) / 255.0
                val g = (pixel ushr 8 and 255) / 255.0
                val b = (pixel and 255) / 255.0
                val value = .2126 * r + .7152 * g + .0722 * b
                luminance[i] = value
                red[i] = r - value
                blue[i] = b - value
            }
            fun deviation(values: DoubleArray): Double {
                val mean = values.average()
                return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            }
            var edges = 0.0
            var count = 0
            for (y in 0 until HEIGHT - 1) for (x in 0 until WIDTH - 1) {
                val i = y * WIDTH + x
                edges += abs(luminance[i + 1] - luminance[i]) + abs(luminance[i + WIDTH] - luminance[i])
                count++
            }
            return AutoMapOpenSignature(luminance, red, blue, deviation(luminance),
                max(deviation(red), deviation(blue)), edges / count)
        }

        fun isUsableReference(signature: AutoMapOpenSignature): Boolean =
            max(signature.luminanceDeviation, signature.chromaDeviation) >= .025 && signature.edgeStrength >= .008

        /** Locate the built-in sidebar inside the calibrated right-hand search strip, without
         * stretching the whole strip to the template width. Only the builtin uses this search;
         * recorded user references retain their original comparison and thresholds. */
        fun compareSidebar(reference: AutoMapOpenSignature, candidate: AutoMapOpenSignature,
            widthFraction: Double): AutoMapOpenComparison {
            require(widthFraction.isFinite() && widthFraction > 0.0 && widthFraction <= 1.0)
            var best = compare(reference, candidate)
            for (scale in listOf(.85, 1.0, 1.15)) {
                val width = (WIDTH * widthFraction * scale).coerceIn(4.0, WIDTH.toDouble())
                val last = WIDTH - width
                // At most 57 windows per scale, each with the existing bounded nine probes.
                val steps = kotlin.math.ceil(last / .5).toInt()
                for (step in 0..steps) {
                    val left = min(step * .5, last)
                    fun resample(values: DoubleArray): DoubleArray = DoubleArray(PIXELS) { index ->
                        val x = index % WIDTH
                        val row = index / WIDTH * WIDTH
                        val source = (left + (x + .5) * width / WIDTH - .5).coerceIn(0.0, WIDTH - 1.0)
                        val x0 = source.toInt()
                        val fraction = source - x0
                        values[row + x0] * (1 - fraction) + values[row + min(x0 + 1, WIDTH - 1)] * fraction
                    }
                    val window = AutoMapOpenSignature(resample(candidate.luminance), resample(candidate.redChroma),
                        resample(candidate.blueChroma), candidate.luminanceDeviation, candidate.chromaDeviation, candidate.edgeStrength)
                    val comparison = compare(reference, window).copy(windowLeft = left / WIDTH, windowWidth = width / WIDTH)
                    if (comparison.score > best.score) best = comparison
                }
            }
            return best
        }

        /** Bounded nine-position spatial comparison; a global histogram cannot establish presence. */
        fun compare(reference: AutoMapOpenSignature, candidate: AutoMapOpenSignature): AutoMapOpenComparison {
            var best: AutoMapOpenComparison? = null
            for (dy in -MOTION_RADIUS..MOTION_RADIUS) for (dx in -MOTION_RADIUS..MOTION_RADIUS) {
                var globalBrightnessDelta = 0.0
                var count = 0
                // Ignore only the one-cell border in every probe; all probes compare the same area.
                for (y in MOTION_RADIUS until HEIGHT - MOTION_RADIUS) for (x in MOTION_RADIUS until WIDTH - MOTION_RADIUS) {
                    globalBrightnessDelta += candidate.luminance[(y + dy) * WIDTH + x + dx] - reference.luminance[y * WIDTH + x]
                    count++
                }
                val compensation = (globalBrightnessDelta / count).coerceIn(-.12, .12)
                var colorError = 0.0
                var brightnessError = 0.0
                var edgeError = 0.0
                for (y in MOTION_RADIUS until HEIGHT - MOTION_RADIUS) for (x in MOTION_RADIUS until WIDTH - MOTION_RADIUS) {
                    val a = y * WIDTH + x
                    val b = (y + dy) * WIDTH + x + dx
                    colorError += min(1.0, (abs(reference.redChroma[a] - candidate.redChroma[b]) +
                        abs(reference.blueChroma[a] - candidate.blueChroma[b])) / .24)
                    brightnessError += min(1.0, abs(reference.luminance[a] - candidate.luminance[b] + compensation) / .18)
                    // Signed gradients preserve edge orientation and location; random arrangements
                    // of the same colors therefore fail even when their color histogram is identical.
                    val ax = reference.luminance[a + 1] - reference.luminance[a]
                    val ay = reference.luminance[a + WIDTH] - reference.luminance[a]
                    val bx = if (x + dx + 1 < WIDTH) candidate.luminance[b + 1] - candidate.luminance[b] else 0.0
                    val by = if (y + dy + 1 < HEIGHT) candidate.luminance[b + WIDTH] - candidate.luminance[b] else 0.0
                    edgeError += min(1.0, (abs(ax - bx) + abs(ay - by)) / .24)
                }
                val color = 1.0 - colorError / count
                val brightness = 1.0 - brightnessError / count
                val edge = 1.0 - edgeError / count
                val score = (.4 * color + .35 * brightness + .25 * edge).coerceIn(0.0, 1.0)
                val comparison = AutoMapOpenComparison(score, color, brightness, edge, dx, dy)
                if (best == null || comparison.score > best.score) best = comparison
            }
            return requireNotNull(best)
        }
    }
}


