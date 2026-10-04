package com.idvb.android.recognize.gate

import android.content.Context
import android.os.SystemClock
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Android/OpenCV port of desktop GateTemplateDetector at commit 4654a90. */
class GateTemplateDetector private constructor(
    gateSource: Mat,
) : AutoCloseable {
    private val gateSource = gateSource.clone()
    private var warmScale: Double? = null
    private var closed = false

    init {
        try {
            require(!this.gateSource.empty()) { "门图标资源为空" }
            val edges = GateTemplateImages.createEdges(this.gateSource)
            try {
                require(Core.countNonZero(edges) > 0) { "门图标资源无法生成有效的边缘模板" }
            } finally {
                edges.release()
            }
        } catch (error: Throwable) {
            this.gateSource.release()
            throw error
        }
    }

    companion object {
        fun fromSource(source: Mat): GateTemplateDetector = GateTemplateDetector(source)

        fun fromAssets(context: Context): GateTemplateDetector {
            val source = GateTemplateImages.loadAsset(context)
            return try {
                GateTemplateDetector(source)
            } finally {
                source.release()
            }
        }

        internal fun fullScales(clientWidth: Double, rememberedScale: Double? = null): List<Double> {
            val normalizedWidth = clientWidth.takeIf { it.isFinite() && it > 0.0 }
                ?: GateTemplateRules.REFERENCE_CLIENT_WIDTH
            val estimated = (GateTemplateRules.REFERENCE_SCALE * normalizedWidth /
                GateTemplateRules.REFERENCE_CLIENT_WIDTH).coerceIn(0.12, 1.5)
            val warm = rememberedScale
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let {
                    listOf(
                        it,
                        it * (1.0 - GateTemplateRules.WARM_SCALE_STEP),
                        it * (1.0 + GateTemplateRules.WARM_SCALE_STEP),
                        it * GateTemplateRules.WARM_SCALE_START,
                        it * GateTemplateRules.WARM_SCALE_MAXIMUM,
                    )
                }.orEmpty()
            val relative = listOf(1.0, .85, 1.15, .7, 1.35, .55, 1.65, 2.0, 2.4, 2.8)
                .map { estimated * it }
            return distinctScales(warm + relative)
        }

        internal fun warmScales(contextScale: Double?, rememberedScale: Double? = null): List<Double> {
            val warm = contextScale?.takeIf { it.isFinite() && it > 0.0 }
                ?: rememberedScale?.takeIf { it.isFinite() && it > 0.0 }
                ?: return emptyList()
            return distinctScales(listOf(warm * .85, warm * .90, warm * .95, warm, warm * 1.05, warm * 1.10, warm * 1.15))
        }

        internal fun confirmationScales(predictedScale: Double?): List<Double> {
            val scale = predictedScale?.takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
            return distinctScales(listOf(scale * .95, scale, scale * 1.05))
        }

        internal fun lockedScales(lockedScale: Double?): List<Double> {
            val scale = lockedScale?.takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
            return listOf(scale.coerceIn(.12, 1.5))
        }

        private fun distinctScales(values: List<Double>): List<Double> = values
            .map { it.coerceIn(.12, 1.5) }
            .distinctBy { round(it * 1000.0).toLong() }

        private fun roundedInt(value: Double): Int = round(value).toInt()

        internal fun intersectionOverUnion(left: ScreenRect, right: ScreenRect): Double {
            val intersectionLeft = max(left.x, right.x)
            val intersectionTop = max(left.y, right.y)
            val intersectionRight = min(left.x + left.width, right.x + right.width)
            val intersectionBottom = min(left.y + left.height, right.y + right.height)
            val width = max(0.0, intersectionRight - intersectionLeft)
            val height = max(0.0, intersectionBottom - intersectionTop)
            val intersection = width * height
            val union = left.width * left.height + right.width * right.height - intersection
            return if (union <= 0.0) 0.0 else intersection / union
        }

        internal fun clusterAcrossScales(raw: List<GateDetection>): List<List<GateDetection>> {
            val clusters = mutableListOf<MutableList<GateDetection>>()
            raw.sortedByDescending { it.score }.forEach { candidate ->
                val cluster = clusters.firstOrNull { members ->
                    members.any { intersectionOverUnion(candidate.screenBounds, it.screenBounds) >= GateTemplateRules.SPATIAL_CLUSTER_IOU_THRESHOLD }
                }
                if (cluster == null) clusters += mutableListOf(candidate) else cluster += candidate
            }
            return clusters
        }

        internal fun selectTopCandidates(clusters: List<List<GateDetection>>): List<GateDetection> {
            val selected = mutableListOf<GateDetection>()
            clusters.mapNotNull { it.maxByOrNull(GateDetection::score) }
                .sortedByDescending { it.score }
                .forEach { candidate ->
                    if (selected.none { intersectionOverUnion(it.screenBounds, candidate.screenBounds) >= GateTemplateRules.NMS_IOU_THRESHOLD }) {
                        selected += candidate
                    }
                    if (selected.size == GateTemplateRules.MAXIMUM_GATE_CANDIDATES) return selected
                }
            return selected
        }
    }

    val hasWarmScale: Boolean get() = warmScale?.let { it > 0.0 } == true
    val rememberedScale: Double? get() = warmScale

    fun rememberSuccessfulScale(scale: Double) {
        if (scale.isFinite() && scale > 0.0) warmScale = scale
    }

    fun resetSuccessfulScale() {
        warmScale = null
    }

    fun detect(
        liveMatchImage: Mat,
        viewportBounds: ScreenRect,
        clientWidth: Double = GateTemplateRules.REFERENCE_CLIENT_WIDTH,
        scoreThreshold: Double = GateTemplateRules.MATCH_THRESHOLD,
        searchContext: GateSearchContext = GateSearchContext(),
    ): GateDetectionResult {
        check(!closed) { "GateTemplateDetector 已关闭" }
        require(!liveMatchImage.empty()) { "门检测输入为空" }
        require(liveMatchImage.channels() == 1) { "门检测输入必须是单通道灰度图" }
        val threshold = scoreThreshold.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
            ?: GateTemplateRules.MATCH_THRESHOLD
        val started = SystemClock.elapsedRealtimeNanos()
        val raw = mutableListOf<GateDetection>()
        var scalesEvaluated = 0
        var regionsEvaluated = 0
        var matchCalls = 0
        var stopReason = GateSearchStopReason.COMPLETED
        var budgetExceeded = false

        fun elapsedMs(): Double = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        fun budgetExpired(): Boolean = searchContext.timeBudgetMilliseconds?.let { elapsedMs() >= it } == true

        if (searchContext.mode == GateSearchMode.LOCAL_CONFIRMATION_SEARCH && searchContext.predictedGateRegions.isNotEmpty()) {
            val scales = confirmationScales(searchContext.predictedScale)
            if (scales.isEmpty()) return emptyResult(searchContext.mode, GateSearchStopReason.NO_VALID_SCALE, elapsedMs())
            loop@ for (region in searchContext.predictedGateRegions) {
                if (!region.isValid) continue
                for (scale in scales) {
                    com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.gate.predicted-scale")
                    if (budgetExpired()) {
                        budgetExceeded = true
                        stopReason = GateSearchStopReason.BUDGET_EXCEEDED
                        break@loop
                    }
                    val width = max(12, roundedInt(gateSource.cols() * scale))
                    val height = max(12, roundedInt(gateSource.rows() * scale))
                    if (width >= liveMatchImage.cols() || height >= liveMatchImage.rows()) continue
                    val roi = buildConfirmationRoi(region, width, height, searchContext, liveMatchImage, viewportBounds)
                    if (roi.width < width || roi.height < height) continue
                    scalesEvaluated++
                    regionsEvaluated++
                    val match = matchBestInRoi(liveMatchImage, roi, scale, width, height)
                    matchCalls++
                    if (match.first >= threshold) {
                        raw += GateDetection(
                            score = match.first,
                            scale = scale,
                            screenBounds = ScreenRect(
                                viewportBounds.x + roi.x + match.second.x,
                                viewportBounds.y + roi.y + match.second.y,
                                width.toDouble(),
                                height.toDouble(),
                            ),
                        )
                    }
                }
            }
        } else {
            val scales = scalesFor(searchContext, clientWidth)
            if (scales.isEmpty()) return emptyResult(searchContext.mode, GateSearchStopReason.NO_VALID_SCALE, elapsedMs())
            for (scale in scales) {
                com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.gate.scale")
                if (budgetExpired()) {
                    budgetExceeded = true
                    stopReason = GateSearchStopReason.BUDGET_EXCEEDED
                    break
                }
                val width = max(12, roundedInt(gateSource.cols() * scale))
                val height = max(12, roundedInt(gateSource.rows() * scale))
                if (width >= liveMatchImage.cols() || height >= liveMatchImage.rows()) continue
                scalesEvaluated++
                regionsEvaluated++
                val scaleCandidates = matchScale(liveMatchImage, viewportBounds, scale, width, height, threshold)
                matchCalls++
                raw += scaleCandidates

                if (searchContext.allowDualGateEarlyExit && scaleCandidates.size >= 2) {
                    val top = scaleCandidates.sortedByDescending { it.score }.take(2)
                    if (top.all { it.score >= GateTemplateRules.EARLY_EXIT_SCORE_THRESHOLD } &&
                        intersectionOverUnion(top[0].screenBounds, top[1].screenBounds) < GateTemplateRules.SPATIAL_CLUSTER_IOU_THRESHOLD
                    ) {
                        stopReason = GateSearchStopReason.DUAL_GATE_EARLY_EXIT
                        break
                    }
                }
                if (stopReason != GateSearchStopReason.DUAL_GATE_EARLY_EXIT &&
                    searchContext.allowSingleGateEarlyExit && raw.isNotEmpty() &&
                    searchContext.mode in setOf(GateSearchMode.WARM_SCALE_SEARCH, GateSearchMode.FULL_SEARCH) &&
                    shouldSingleGateExit(searchContext, raw, scalesEvaluated)
                ) {
                    stopReason = GateSearchStopReason.SINGLE_GATE_WARM_EXIT
                    break
                }
            }
        }

        val selected = selectTopCandidates(clusterAcrossScales(raw))
        return GateDetectionResult(
            gates = selected,
            rawCandidates = raw.toList(),
            searchModeUsed = searchContext.mode,
            stopReason = stopReason,
            scalesEvaluated = scalesEvaluated,
            regionsEvaluated = regionsEvaluated,
            matchTemplateCalls = matchCalls,
            budgetExceeded = budgetExceeded,
            elapsedMilliseconds = elapsedMs(),
        )
    }

    private fun scalesFor(context: GateSearchContext, clientWidth: Double): List<Double> = when (context.mode) {
        GateSearchMode.WARM_SCALE_SEARCH -> warmScales(context.warmScale, warmScale)
        GateSearchMode.LOCAL_CONFIRMATION_SEARCH -> confirmationScales(context.predictedScale)
        GateSearchMode.LOCKED_SCALE -> lockedScales(context.lockedScale)
        GateSearchMode.FULL_SEARCH -> fullScales(clientWidth, warmScale)
    }

    private fun matchBestInRoi(frame: Mat, roi: Rect, scale: Double, width: Int, height: Int): Pair<Double, Point> {
        val scaledSource = Mat()
        val scaled = Mat()
        val roiMat = frame.submat(roi)
        val output = Mat()
        try {
            Imgproc.resize(gateSource, scaledSource, Size(width.toDouble(), height.toDouble()), 0.0, 0.0,
                if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            GateTemplateImages.createMatchImage(scaledSource).useNative { it.copyTo(scaled) }
            Imgproc.matchTemplate(roiMat, scaled, output, Imgproc.TM_CCOEFF_NORMED)
            val result = Core.minMaxLoc(output)
            return result.maxVal to result.maxLoc
        } finally {
            scaledSource.release()
            scaled.release()
            roiMat.release()
            output.release()
        }
    }

    private fun matchScale(
        frame: Mat,
        viewport: ScreenRect,
        scale: Double,
        width: Int,
        height: Int,
        threshold: Double,
    ): List<GateDetection> {
        val scaledSource = Mat()
        val scaled = Mat()
        val output = Mat()
        try {
            Imgproc.resize(gateSource, scaledSource, Size(width.toDouble(), height.toDouble()), 0.0, 0.0,
                if (scale < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            GateTemplateImages.createMatchImage(scaledSource).useNative { it.copyTo(scaled) }
            Imgproc.matchTemplate(frame, scaled, output, Imgproc.TM_CCOEFF_NORMED)
            return buildList {
                repeat(8) {
                    val peak = Core.minMaxLoc(output)
                    if (peak.maxVal < threshold) return@buildList
                    add(
                        GateDetection(
                            peak.maxVal,
                            scale,
                            ScreenRect(viewport.x + peak.maxLoc.x, viewport.y + peak.maxLoc.y, width.toDouble(), height.toDouble()),
                        ),
                    )
                    Imgproc.rectangle(output, suppressionRect(peak.maxLoc, scaled.size(), output.size()), Scalar.all(-1.0), -1)
                }
            }
        } finally {
            scaledSource.release()
            scaled.release()
            output.release()
        }
    }

    private fun shouldSingleGateExit(context: GateSearchContext, raw: List<GateDetection>, scalesEvaluated: Int): Boolean {
        if (context.mode == GateSearchMode.FULL_SEARCH && scalesEvaluated < GateTemplateRules.FULL_SEARCH_MIN_SCALES_BEFORE_SINGLE_GATE_EXIT) return false
        val clusters = clusterAcrossScales(raw)
        if (clusters.size == 1) {
            val best = clusters[0].maxByOrNull(GateDetection::score) ?: return false
            if (best.score < context.singleGateScoreThreshold) return false
            context.warmScale?.let {
                if (abs(best.scale / it - 1.0) > context.singleGateScaleTolerance) return false
            }
            return true
        }
        if (clusters.size >= 2 && context.mode == GateSearchMode.WARM_SCALE_SEARCH) {
            val ordered = clusters.mapNotNull { it.maxByOrNull(GateDetection::score) }.sortedByDescending { it.score }
            return ordered[0].score - ordered[1].score >= context.ambiguityScoreGap
        }
        return false
    }

    private fun buildConfirmationRoi(
        predicted: ScreenRect,
        templateWidth: Int,
        templateHeight: Int,
        context: GateSearchContext,
        matchImage: Mat,
        viewport: ScreenRect,
    ): Rect {
        val paddingX = max(context.localRoiMinimumPaddingPixels,
            roundedInt(templateWidth * context.localRoiTemplatePaddingFactor) + context.maximumExpectedMotionPixels)
        val paddingY = max(context.localRoiMinimumPaddingPixels,
            roundedInt(templateHeight * context.localRoiTemplatePaddingFactor) + context.maximumExpectedMotionPixels)
        val localCenterX = predicted.centerX - viewport.x
        val localCenterY = predicted.centerY - viewport.y
        val left = max(0, roundedInt(localCenterX - predicted.width / 2.0 - paddingX))
        val top = max(0, roundedInt(localCenterY - predicted.height / 2.0 - paddingY))
        val right = min(matchImage.cols(), roundedInt(localCenterX + predicted.width / 2.0 + paddingX))
        val bottom = min(matchImage.rows(), roundedInt(localCenterY + predicted.height / 2.0 + paddingY))
        return if (right <= left || bottom <= top) {
            Rect(0, 0, min(templateWidth, matchImage.cols()), min(templateHeight, matchImage.rows()))
        } else Rect(left, top, right - left, bottom - top)
    }

    private fun suppressionRect(location: Point, template: Size, output: Size): Rect {
        val left = max(0, location.x.toInt() - template.width.toInt() / 2)
        val top = max(0, location.y.toInt() - template.height.toInt() / 2)
        val right = min(output.width.toInt(), location.x.toInt() + template.width.toInt())
        val bottom = min(output.height.toInt(), location.y.toInt() + template.height.toInt())
        return Rect(left, top, max(1, right - left), max(1, bottom - top))
    }

    private fun emptyResult(mode: GateSearchMode, reason: GateSearchStopReason, elapsed: Double) =
        GateDetectionResult(searchModeUsed = mode, stopReason = reason, elapsedMilliseconds = elapsed)

    override fun close() {
        if (closed) return
        closed = true
        gateSource.release()
    }
}

private inline fun <T> Mat.useNative(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
