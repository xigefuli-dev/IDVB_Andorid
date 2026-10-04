package com.idvb.android.alignment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.idvb.android.recognize.cv.CvImages
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class FloorIndicatorTemplate(val name: String, val floorKey: String, val png: ByteArray)
data class FloorIndicatorResult(val winner: FloorIndicatorScore, val candidates: List<FloorIndicatorScore>)

/** Searches every template over the same position/scale domain. Dimensions only determine
 * where content can fit; the final race uses normalized content correlation alone. */
class FloorIndicatorRecognizer(private val templates: List<FloorIndicatorTemplate>) {
    fun recognize(frame: Bitmap, availableFloors: Set<String>, token: AlignmentCancellation,
        log: AlignmentLogSink = AlignmentLogSink.NONE): FloorIndicatorResult = token.run {
        val started = System.nanoTime()
        val scheduling = AlignmentScheduling.enter(log)
        try {
            require(frame.width > 0 && frame.height > 0 && templates.isNotEmpty())
            log.emit(AlignmentLogEvent("floor-indicator.configuration", FloorIndicatorPolicy.VERSION,
                measurements = mapOf("inputWidth" to frame.width.toDouble(), "inputHeight" to frame.height.toDouble()),
                labels = mapOf("score" to "TM_CCOEFF_NORMED grayscale", "decision" to "highest-content-similarity",
                    "threshold" to "none", "resolutionPrior" to "none", "structureEvidence" to "none",
                    "availableFloors" to availableFloors.sorted().joinToString(","))))
            retain(frame, "floor-indicator.png", log)
            val original = log.measure("floor-indicator.grayscale") { CvImages.bitmapToGray(frame) }
            val live = Mat()
            try {
                val ratio = minOf(1.0, MAX_WIDTH.toDouble() / frame.width, MAX_HEIGHT.toDouble() / frame.height)
                Imgproc.resize(original, live, Size(maxOf(1, (frame.width * ratio).roundToInt()).toDouble(),
                    maxOf(1, (frame.height * ratio).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                log.emit(AlignmentLogEvent("floor-indicator.search-domain",
                    measurements = mapOf("processingScale" to ratio, "searchWidth" to live.cols().toDouble(),
                        "searchHeight" to live.rows().toDouble(), "minimumTemplateHeight" to MIN_HEIGHT.toDouble(),
                        "coarseScaleStep" to SCALE_STEP),
                    labels = mapOf("positions" to "every-fitting-pixel", "scales" to "content-search-independent-of-screen-resolution")))
                val candidates = templates.map { template ->
                    token.throwIfCancelled("floor-indicator.template")
                    log.attach("floor-template-${template.name}") { template.png }
                    log.emit(AlignmentLogEvent("floor-indicator.template-source", labels = mapOf(
                        "template" to template.name, "floorKey" to template.floorKey,
                        "sha256" to AlignmentDiagnosticsStore.sha256(template.png))))
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(template.png, 0, template.png.size))
                    val reference = try { CvImages.bitmapToGray(decoded) } finally { decoded.recycle() }
                    try { log.measure("floor-indicator.template-search") {
                        search(live, reference, template, ratio, token, log)
                    } } finally { reference.release() }
                }
                val winner = requireNotNull(FloorIndicatorPolicy.winner(candidates, availableFloors)) {
                    "所选地图没有楼层指示器对应的楼层"
                }
                log.emit(AlignmentLogEvent("floor-indicator.selected", measurements = mapOf("similarity" to winner.similarity,
                    "scale" to winner.scale, "x" to winner.x, "y" to winner.y, "width" to winner.width, "height" to winner.height),
                    labels = mapOf("template" to winner.template, "floorKey" to winner.floorKey,
                        "policy" to "highest-content-similarity; always-select; independent-of-alignment")))
                FloorIndicatorResult(winner, candidates)
            } finally { live.release(); original.release() }
        } catch (error: Exception) {
            log.emit(AlignmentLogEvent("floor-indicator.exit", if (token.isCancelled) "cancelled" else "input-or-runtime-unavailable",
                measurements = if (token.isCancelled) mapOf("cancelResponseMs" to (System.nanoTime() - token.requestedAtNanos) / 1e6) else emptyMap(),
                labels = mapOf("reason" to (error.message ?: error.javaClass.simpleName),
                    "checkpoint" to ((error as? AlignmentCancelledException)?.stage ?: "native-call-or-worker"))))
            throw error
        } finally {
            scheduling.restore(log)
            val end = System.nanoTime()
            val diagnostics = (log as? AlignmentTrace)?.diagnosticNanosBetween(started, end) ?: 0L
            log.emit(AlignmentLogEvent("floor-indicator.total", durationNanos = end - started,
                measurements = mapOf("callWallMs" to (end - started) / 1e6,
                    "recognitionWorkMs" to (end - started - diagnostics) / 1e6, "nestedDiagnosticMs" to diagnostics / 1e6)))
        }
    }

    private fun search(live: Mat, reference: Mat, template: FloorIndicatorTemplate, ratio: Double,
        token: AlignmentCancellation, log: AlignmentLogSink): FloorIndicatorScore {
        val aspect = reference.cols().toDouble() / reference.rows()
        val maxHeight = minOf(live.rows(), (live.cols() / aspect).toInt()).coerceAtLeast(1)
        val minHeight = minOf(MIN_HEIGHT, maxHeight)
        val heights = sortedSetOf(minHeight, maxHeight)
        var height = minHeight.toDouble()
        while (height < maxHeight) {
            token.throwIfCancelled("floor-indicator.scale-domain")
            heights += height.roundToInt().coerceIn(minHeight, maxHeight)
            height *= SCALE_STEP
        }
        val scaled = Mat(); val scores = Mat()
        var best: FloorIndicatorScore? = null
        var bestHeight = minHeight
        val tested = mutableSetOf<Int>()
        try {
            fun test(h: Int) {
                if (!tested.add(h)) return
                token.throwIfCancelled("floor-indicator.match.before")
                val w = (h * aspect).roundToInt().coerceIn(1, live.cols())
                Imgproc.resize(reference, scaled, Size(w.toDouble(), h.toDouble()), 0.0, 0.0,
                    if (h < reference.rows()) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
                val began = System.nanoTime()
                Imgproc.matchTemplate(live, scaled, scores, Imgproc.TM_CCOEFF_NORMED)
                token.throwIfCancelled("floor-indicator.match.after")
                val peak = Core.minMaxLoc(scores)
                val score = if (peak.maxVal.isFinite()) peak.maxVal else -1.0
                val candidate = FloorIndicatorScore(template.name, template.floorKey, score,
                    h.toDouble() / reference.rows() / ratio, peak.maxLoc.x / ratio, peak.maxLoc.y / ratio, w / ratio, h / ratio)
                log.emit(AlignmentLogEvent("floor-indicator.match", durationNanos = System.nanoTime() - began,
                    measurements = mapOf("similarity" to score, "scale" to candidate.scale,
                        "x" to candidate.x, "y" to candidate.y, "width" to candidate.width, "height" to candidate.height),
                    labels = mapOf("template" to template.name, "floorKey" to template.floorKey)))
                if (best == null || score > best!!.similarity) { best = candidate; bestHeight = h }
            }
            heights.forEach(::test)
            // Refine each template equally around its own best scale; no size preference.
            val radius = maxOf(1, (bestHeight * (SCALE_STEP - 1)).roundToInt())
            val center = bestHeight
            for (h in maxOf(minHeight, center - radius)..minOf(maxHeight, center + radius)) test(h)
            return requireNotNull(best)
        } finally { scaled.release(); scores.release() }
    }

    companion object {
        // Preserve the normalized position/scale domain while reducing correlation work.
        // The raw ROI and processing scale remain in diagnostics for independent replay.
        private const val MAX_WIDTH = 256
        private const val MAX_HEIGHT = 96
        private const val MIN_HEIGHT = 6
        private const val SCALE_STEP = 1.15

        fun load(context: Context): FloorIndicatorRecognizer = FloorIndicatorRecognizer(listOf(
            "hard-1f.png" to "1f", "hard-2f.png" to "2f", "nightmare-b1f.png" to "b1f",
            "nightmare-1f.png" to "1f", "nightmare-2f.png" to "2f",
        ).map { (name, key) -> FloorIndicatorTemplate(name, key, context.assets.open("FloorIndicators/$name").use { it.readBytes() }) })

        private fun retain(bitmap: Bitmap, name: String, log: AlignmentLogSink) {
            if (log is AlignmentTrace && !log.captureArtifacts || !log.enabled) return
            val width = bitmap.width; val height = bitmap.height
            val pixels = log.measure("diagnostics.copy-floor-indicator") {
                IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            }
            log.attachOwned(name) {
                val owned = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                try { ByteArrayOutputStream().use { output ->
                    check(owned.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                } } finally { owned.recycle() }
            }
        }
    }
}
