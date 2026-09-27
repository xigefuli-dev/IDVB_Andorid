package com.idvb.android.recognize.side

import com.idvb.android.recognize.gate.GateDetection
import com.idvb.android.recognize.gate.ScreenRect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Android/OpenCV port of desktop SideEntranceScanPipeline at commit 4654a90. */
class SideEntranceScanPipeline(
    config: SideEntranceScanConfig,
) {
    private val config = config.normalized()

    fun runScan(
        capturedGrayFrame: Mat,
        inputs: List<SideEntranceScanInput>,
        detectedGates: List<GateDetection>,
        viewportBounds: ScreenRect,
        topK: Int = 5,
        progress: ((Double) -> Unit)? = null,
    ): List<SideEntranceScanCandidate> {
        require(!capturedGrayFrame.empty()) { "侧门扫描帧为空" }
        require(capturedGrayFrame.channels() == 1) { "侧门扫描帧必须是单通道灰度图" }
        if (inputs.isEmpty()) return emptyList()

        val maskedFrame = capturedGrayFrame.clone()
        // Scan-local, managed results only. All branches observe the same masked pixels.
        val refinements = ConcurrentHashMap<RefinementKey, SideEntranceScanCandidate>()
        val reused = AtomicInteger()
        try {
            maskDetectedGates(maskedFrame, detectedGates, viewportBounds)
            val totalBranches = max(1, detectedGates.size + 1)
            val associated = mutableListOf<SideEntranceScanCandidate>()

            detectedGates.forEachIndexed { gateIndex, gate ->
                if (!gate.screenBounds.isValid) return@forEachIndexed
                val branch = runSingleGateScan(
                    maskedFrame,
                    inputs,
                    inputs.size,
                    gate,
                    viewportBounds,
                    refinements,
                    reused,
                ) { value -> progress?.invoke((gateIndex + value) / totalBranches) }
                branch.forEach { candidate ->
                    candidate.associatedGate = gate
                    candidate.associatedGateIndex = gateIndex
                    candidate.gateAssociationKind = SideEntranceGateAssociationKind.DETECTED_GATE
                    associated += candidate
                }
            }

            val inputByKey = inputs.associateBy { it.map.id to it.floorKey }
            val collapsed = associated
                .groupBy { it.map.id to it.floorKey }
                .map { (_, candidates) ->
                    candidates.sortedWith(
                        compareBy<SideEntranceScanCandidate> { it.gateSpatialResidualPixels }
                            .thenByDescending { it.matchScore },
                    ).first()
                }
                .toMutableList()

            val associatedKeys = collapsed.mapTo(mutableSetOf()) { it.map.id to it.floorKey }
            val rescueInputs = inputs.filterNot { (it.map.id to it.floorKey) in associatedKeys }
            if (rescueInputs.isNotEmpty()) {
                val rescued = runSingleGateScan(
                    maskedFrame,
                    rescueInputs,
                    rescueInputs.size,
                    detectedGate = null,
                    viewportBounds = viewportBounds,
                    refinements = refinements,
                    reused = reused,
                ) { value -> progress?.invoke((detectedGates.size + value) / totalBranches) }
                rescued.forEach { candidate ->
                    candidate.associatedGate = null
                    candidate.associatedGateIndex = -1
                    candidate.gateSpatialResidualPixels = Double.POSITIVE_INFINITY
                    candidate.gateAssociationKind = if (detectedGates.isEmpty()) {
                        SideEntranceGateAssociationKind.NONE
                    } else SideEntranceGateAssociationKind.TEMPLATE_ONLY_RESCUE
                    collapsed += candidate
                }
            }

            collapsed.sortByDescending { it.matchScore }
            calculateMargins(collapsed)
            collapsed.forEach { candidate ->
                candidate.disposition = SideEntranceCandidateDisposition.NEEDS_VERIFICATION
                candidate.rejectionReason = SideEntranceRejectionReason.NONE
                candidate.rejectionDetail = ""
                inputByKey[candidate.map.id to candidate.floorKey]?.let { input ->
                    classifyTemplateEvidence(candidate, input, candidate.associatedGate, viewportBounds)
                } ?: reject(candidate, SideEntranceRejectionReason.INVALID_FEATURE_DATA, "侧门特征元数据已丢失。")
            }
            return collapsed.filter { it.disposition != SideEntranceCandidateDisposition.REJECTED }
                .take(max(1, topK))
        } finally {
            android.util.Log.i("IDVB-Scan", "side reusedRefinements=${reused.get()}")
            maskedFrame.release()
        }
    }

    private fun runSingleGateScan(
        grayFrame: Mat,
        inputs: List<SideEntranceScanInput>,
        topK: Int,
        detectedGate: GateDetection?,
        viewportBounds: ScreenRect,
        refinements: ConcurrentHashMap<RefinementKey, SideEntranceScanCandidate>,
        reused: AtomicInteger,
        progress: ((Double) -> Unit)? = null,
    ): List<SideEntranceScanCandidate> {
        val valid = inputs.filter { !it.featureTemplate.empty() && it.featureTemplate.channels() == 1 }
        if (valid.isEmpty()) return emptyList()
        val searchBounds = buildSearchBounds(grayFrame, valid, detectedGate, viewportBounds)
        val constrained = grayFrame.submat(searchBounds)
        val coarseFrame = Mat()
        try {
            val factor = config.coarsePyramidFactor
            Imgproc.resize(
                constrained,
                coarseFrame,
                Size(max(1, constrained.cols() / factor).toDouble(), max(1, constrained.rows() / factor).toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )

            val coarseAt = System.nanoTime()
            val coarseCompleted = AtomicInteger()
            val coarse = parallelMap(valid, config.scanParallelism) { input ->
                val peak = findCoarsePeak(coarseFrame, input.featureTemplate, factor)
                progress?.invoke(.7 * coarseCompleted.incrementAndGet() / valid.size)
                peak?.let { CoarseResult(input, it) }
            }.filterNotNull()
                .filter { it.peak.score >= config.coarseScorePruneThreshold }
                .sortedByDescending { it.peak.score }

            val refineAt = System.nanoTime()
            val refinedCompleted = AtomicInteger()
            val refined = parallelMap(coarse, config.scanParallelism) { item ->
                val candidate = refine(constrained, item.input, item.peak, factor, searchBounds, refinements, reused)
                progress?.invoke(.7 + .3 * refinedCompleted.incrementAndGet() / max(1, coarse.size))
                candidate
            }.filterNotNull().toMutableList()

            android.util.Log.i("IDVB-Scan", "side branch=${if (detectedGate == null) "rescue" else "gate"}" +
                " inputs=${valid.size} bounds=$searchBounds coarseMs=${(refineAt - coarseAt) / 1e6}" +
                " refineMs=${(System.nanoTime() - refineAt) / 1e6} refined=${refined.size}")
            if (searchBounds.x != 0 || searchBounds.y != 0) {
                refined.forEach { candidate ->
                    candidate.matchLocation = candidate.matchLocation.copy(
                        x = candidate.matchLocation.x + searchBounds.x,
                        y = candidate.matchLocation.y + searchBounds.y,
                    )
                }
            }

            refined.sortByDescending { it.matchScore }
            calculateMargins(refined)
            refined.forEach { candidate ->
                val input = valid.first { it.map.id == candidate.map.id && it.floorKey == candidate.floorKey }
                classifyTemplateEvidence(candidate, input, detectedGate, viewportBounds)
            }
            return refined.filter { it.disposition != SideEntranceCandidateDisposition.REJECTED }
                .take(max(1, topK))
        } finally {
            coarseFrame.release()
            constrained.release()
        }
    }

    private fun buildSearchBounds(
        frame: Mat,
        inputs: List<SideEntranceScanInput>,
        gate: GateDetection?,
        viewport: ScreenRect,
    ): Rect {
        if (gate == null || !viewport.isValid || !gate.screenBounds.isValid) {
            return Rect(0, 0, frame.cols(), frame.rows())
        }
        val gateX = gate.screenBounds.centerX - viewport.x
        val gateY = gate.screenBounds.centerY - viewport.y
        val largestExtent = inputs.maxOf { max(it.featureTemplate.cols(), it.featureTemplate.rows()) }
        val radius = ceil(largestExtent * config.maximumScale + config.maximumGateSpatialResidualPixels * 2.0).toInt()
        val left = (floor(gateX).toInt() - radius).coerceIn(0, max(0, frame.cols() - 1))
        val top = (floor(gateY).toInt() - radius).coerceIn(0, max(0, frame.rows() - 1))
        val right = (ceil(gateX).toInt() + radius).coerceIn(left + 1, frame.cols())
        val bottom = (ceil(gateY).toInt() + radius).coerceIn(top + 1, frame.rows())
        return Rect(left, top, right - left, bottom - top)
    }

    private data class CoarsePeak(val scale: Double, val x: Int, val y: Int, val score: Double)
    private data class CoarseResult(val input: SideEntranceScanInput, val peak: CoarsePeak)
    private data class RefinementKey(
        val input: SideEntranceScanInput, val scale: Double,
        val x: Int, val y: Int, val width: Int, val height: Int,
    )

    private fun findCoarsePeak(coarseFrame: Mat, template: Mat, factor: Int): CoarsePeak? {
        var scale = config.minimumScale
        var best: CoarsePeak? = null
        var bestScore = Double.NEGATIVE_INFINITY
        while (scale <= config.maximumScale) {
            val width = roundedInt(template.cols() * scale / factor)
            val height = roundedInt(template.rows() * scale / factor)
            if (width >= 8 && height >= 8 && width < coarseFrame.cols() && height < coarseFrame.rows()) {
                val scaled = Mat()
                val response = Mat()
                try {
                    Imgproc.resize(template, scaled, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.matchTemplate(coarseFrame, scaled, response, Imgproc.TM_CCOEFF_NORMED)
                    val peak = Core.minMaxLoc(response)
                    if (peak.maxVal.isFinite() && peak.maxVal > bestScore) {
                        bestScore = peak.maxVal
                        best = CoarsePeak(scale, peak.maxLoc.x.toInt(), peak.maxLoc.y.toInt(), peak.maxVal)
                    }
                } finally {
                    scaled.release()
                    response.release()
                }
            }
            scale *= 1.0 + config.coarseScaleStep
        }
        return best
    }

    private fun refine(
        grayFrame: Mat,
        input: SideEntranceScanInput,
        peak: CoarsePeak,
        factor: Int,
        searchBounds: Rect,
        refinements: ConcurrentHashMap<RefinementKey, SideEntranceScanCandidate>,
        reused: AtomicInteger,
    ): SideEntranceScanCandidate? {
        val refineStep = config.coarseScaleStep / 4.0
        val maximumScale = peak.scale * (1.0 + config.refineStepsPerSide * refineStep)
        val window = buildRefineWindow(grayFrame, input.featureTemplate, peak, maximumScale, factor) ?: return null
        val key = RefinementKey(input, peak.scale, window.x + searchBounds.x,
            window.y + searchBounds.y, window.width, window.height)
        refinements[key]?.let { stored ->
            reused.incrementAndGet()
            // Candidates are mutable during gate classification. Never share those mutations.
            return stored.copy(matchLocation = stored.matchLocation.copy(
                x = stored.matchLocation.x - searchBounds.x, y = stored.matchLocation.y - searchBounds.y))
        }
        val searchRegion = grayFrame.submat(window)
        try {
            val origin = Point(window.x.toDouble(), window.y.toDouble())
            var best = evaluate(searchRegion, origin, input, peak.scale)
            for (index in -config.refineStepsPerSide..config.refineStepsPerSide) {
                if (index == 0) continue
                val scale = peak.scale * (1.0 + index * refineStep)
                if (scale < config.minimumScale || scale > config.maximumScale) continue
                val candidate = evaluate(searchRegion, origin, input, scale)
                if (candidate != null && (best == null || candidate.matchScore > best.matchScore)) best = candidate
            }
            best?.let { candidate ->
                refinements[key] = candidate.copy(matchLocation = candidate.matchLocation.copy(
                    x = candidate.matchLocation.x + searchBounds.x, y = candidate.matchLocation.y + searchBounds.y))
            }
            return best
        } finally {
            searchRegion.release()
        }
    }

    private fun buildRefineWindow(frame: Mat, template: Mat, peak: CoarsePeak, maximumScale: Double, factor: Int): Rect? {
        val widest = roundedInt(template.cols() * maximumScale)
        val tallest = roundedInt(template.rows() * maximumScale)
        if (widest >= frame.cols() || tallest >= frame.rows()) return null
        val margin = factor * 4
        var width = min(widest + margin * 2, frame.cols())
        var height = min(tallest + margin * 2, frame.rows())
        val left = (peak.x * factor - margin).coerceIn(0, frame.cols() - width)
        val top = (peak.y * factor - margin).coerceIn(0, frame.rows() - height)
        return Rect(left, top, width, height)
    }

    private fun evaluate(
        searchRegion: Mat,
        origin: Point,
        input: SideEntranceScanInput,
        scale: Double,
    ): SideEntranceScanCandidate? {
        val width = roundedInt(input.featureTemplate.cols() * scale)
        val height = roundedInt(input.featureTemplate.rows() * scale)
        if (width < 8 || height < 8 || width >= searchRegion.cols() || height >= searchRegion.rows()) return null
        val scaled = Mat()
        val response = Mat()
        try {
            Imgproc.resize(
                input.featureTemplate,
                scaled,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                if (scale >= 1.0) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA,
            )
            Imgproc.matchTemplate(searchRegion, scaled, response, Imgproc.TM_CCOEFF_NORMED)
            val peak = Core.minMaxLoc(response)
            if (!peak.maxVal.isFinite()) return null
            return SideEntranceScanCandidate(
                map = input.map,
                floorKey = input.floorKey,
                matchScore = peak.maxVal,
                matchScale = scale,
                matchLocation = ScreenRect(
                    origin.x + peak.maxLoc.x,
                    origin.y + peak.maxLoc.y,
                    width.toDouble(),
                    height.toDouble(),
                ),
            )
        } finally {
            scaled.release()
            response.release()
        }
    }

    private fun calculateMargins(candidates: List<SideEntranceScanCandidate>) {
        candidates.forEachIndexed { index, candidate ->
            val previous = if (index > 0) candidates[index - 1].matchScore - candidate.matchScore else Double.POSITIVE_INFINITY
            val next = if (index + 1 < candidates.size) candidate.matchScore - candidates[index + 1].matchScore else Double.POSITIVE_INFINITY
            candidate.templateMargin = if (candidates.size == 1) candidate.matchScore else min(previous, next)
        }
    }

    private fun classifyTemplateEvidence(
        candidate: SideEntranceScanCandidate,
        input: SideEntranceScanInput,
        detectedGate: GateDetection?,
        viewport: ScreenRect,
    ) {
        if (candidate.matchScore < config.minimumReferenceSimilarity) {
            reject(candidate, SideEntranceRejectionReason.WEAK_TEMPLATE_SIMILARITY, "模板相似度低于参考门槛。")
            return
        }
        if (detectedGate != null && viewport.isValid) {
            candidate.gateSpatialResidualPixels = calculateGateResidual(candidate, input, detectedGate, viewport)
            if (!candidate.gateSpatialResidualPixels.isFinite() || candidate.gateSpatialResidualPixels > config.maximumGateSpatialResidualPixels) {
                reject(candidate, SideEntranceRejectionReason.GATE_SPATIAL_MISMATCH, "模板门位置与检测门不一致。")
                return
            }
        }
        val tolerance = config.scaleBoundaryTolerance
        if (candidate.matchScale <= config.minimumScale * (1.0 + tolerance) ||
            candidate.matchScale >= config.maximumScale * (1.0 - tolerance)
        ) {
            candidate.rejectionReason = SideEntranceRejectionReason.SCALE_AT_SEARCH_BOUNDARY
            candidate.rejectionDetail = "最佳缩放落在搜索边界，不能作为可靠身份依据。"
            return
        }
        if (candidate.templateMargin < config.minimumTemplateMargin) {
            candidate.rejectionReason = SideEntranceRejectionReason.AMBIGUOUS_TEMPLATE_RANKING
            candidate.rejectionDetail = "模板身份分离度不足。"
        }
    }

    private fun calculateGateResidual(
        candidate: SideEntranceScanCandidate,
        input: SideEntranceScanInput,
        gate: GateDetection,
        viewport: ScreenRect,
    ): Double {
        if (input.recognitionWidth <= 0 || input.recognitionHeight <= 0 || candidate.matchScale <= 0.0) return Double.POSITIVE_INFINITY
        val anchor = input.sideEntranceBounds
        val anchorCenterX = (anchor.x + anchor.width / 2.0) * input.recognitionWidth
        val anchorCenterY = (anchor.y + anchor.height / 2.0) * input.recognitionHeight
        val featureOriginX = input.featureCenterX - candidate.matchLocation.width / candidate.matchScale / 2.0
        val featureOriginY = input.featureCenterY - candidate.matchLocation.height / candidate.matchScale / 2.0
        val predictedX = candidate.matchLocation.x + (anchorCenterX - featureOriginX) * candidate.matchScale
        val predictedY = candidate.matchLocation.y + (anchorCenterY - featureOriginY) * candidate.matchScale
        val detectedX = gate.screenBounds.centerX - viewport.x
        val detectedY = gate.screenBounds.centerY - viewport.y
        return hypot(predictedX - detectedX, predictedY - detectedY)
    }

    private fun reject(candidate: SideEntranceScanCandidate, reason: SideEntranceRejectionReason, detail: String) {
        candidate.disposition = SideEntranceCandidateDisposition.REJECTED
        candidate.rejectionReason = reason
        candidate.rejectionDetail = detail
    }

    companion object {
        internal fun maskDetectedGates(frame: Mat, gates: List<GateDetection>, viewport: ScreenRect) {
            if (!viewport.isValid || gates.isEmpty()) return
            val mean = Core.mean(frame).`val`[0]
            val frameBounds = Rect(0, 0, frame.cols(), frame.rows())
            gates.forEach { gate ->
                if (!gate.screenBounds.isValid) return@forEach
                val requested = Rect(
                    floor(gate.screenBounds.x - viewport.x).toInt(),
                    floor(gate.screenBounds.y - viewport.y).toInt(),
                    ceil(gate.screenBounds.width).toInt(),
                    ceil(gate.screenBounds.height).toInt(),
                )
                val local = intersect(requested, frameBounds)
                if (local.width > 0 && local.height > 0) Imgproc.rectangle(frame, local, Scalar(mean), -1)
            }
        }

        private fun intersect(left: Rect, right: Rect): Rect {
            val x1 = max(left.x, right.x)
            val y1 = max(left.y, right.y)
            val x2 = min(left.x + left.width, right.x + right.width)
            val y2 = min(left.y + left.height, right.y + right.height)
            return Rect(x1, y1, max(0, x2 - x1), max(0, y2 - y1))
        }

        private fun roundedInt(value: Double): Int = round(value).toInt()

        private fun <T, R> parallelMap(source: List<T>, parallelism: Int, block: (T) -> R): List<R> {
            if (source.isEmpty()) return emptyList()
            if (parallelism <= 1 || source.size == 1) return source.map(block)
            val executor = Executors.newFixedThreadPool(min(parallelism, source.size))
            return try {
                source.map { item -> executor.submit(Callable { block(item) }) }.map { it.get() }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
