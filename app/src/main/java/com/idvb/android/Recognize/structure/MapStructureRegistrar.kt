package com.idvb.android.recognize.structure

import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.gate.ScreenRect
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Independent full-map structure registration port. Side-entrance pixels are
 * used only to seed scale/translation; every score below is computed from the
 * full reference and live structure masks.
 */
internal class MapStructureRegistrar(
    private val tuning: StructureRegistrationTuning = StructureRegistrationTuning(),
) {
    fun register(
        reference: StructureFeatures,
        live: StructureFeatures,
        viewportBounds: ScreenRect,
        seedScale: Double,
        seedOffsetX: Double,
        seedOffsetY: Double,
        validMapBounds: NormalizedRect?,
        allowStrongSeedEarlyExit: Boolean = false,
    ): StructureRegistrationResult {
        if (!viewportBounds.isValid || !seedScale.isFinite() || seedScale <= .05 ||
            reference.edges.empty() || live.edges.empty() ||
            live.knownDomain?.let { it.empty() || it.type() != CvType.CV_8UC1 || it.size() != live.edges.size() } == true
        ) return reject(StructureRejectionReason.INVALID_INPUT, "结构配准输入或侧门位姿种子无效。")

        // Same reciprocal-scale policy as desktop global recovery: for a
        // sub-1 screen scale, shrink the immutable reference rather than
        // enlarging live edge geometry.
        val referenceScale = if (seedScale < 1.0) seedScale else 1.0
        val matchingReference = resizeFeatures(reference, referenceScale)
        try {
            val distance = createDistanceMap(matchingReference.edges)
            try {
                val baseline = seedScale / referenceScale
                val hypotheses = buildScaleHypotheses(baseline)
                val localCandidates = mutableListOf<StructureCandidate>()
                var queryEvidence: QueryEvidence? = null
                for (matchingScale in hypotheses) {
                    createQuery(live, matchingScale).use { query ->
                        queryEvidence = chooseLargerEvidence(queryEvidence, query)
                        if (!query.usable(tuning) || !fits(query, distance)) return@use
                        val expected = Point(
                            (viewportBounds.x + query.bounds.x * matchingScale - seedOffsetX) / matchingScale,
                            (viewportBounds.y + query.bounds.y * matchingScale - seedOffsetY) / matchingScale,
                        )
                        collectLocalCandidates(
                            query,
                            matchingReference,
                            distance,
                            matchingScale,
                            referenceScale,
                            viewportBounds,
                            validMapBounds,
                            expected,
                            localCandidates,
                            allowStrongSeedEarlyExit,
                        )
                    }
                    if (allowStrongSeedEarlyExit && localCandidates.any(::isStrongAbsolute)) break
                }
                val local = finalizeResult(
                    localCandidates,
                    queryEvidence,
                    hypotheses.size,
                    usedGlobalRecovery = false,
                )
                if (local.accepted) return local

                val globalCandidates = mutableListOf<StructureCandidate>()
                hypotheses.forEach { matchingScale ->
                    createQuery(live, matchingScale).use { query ->
                        queryEvidence = chooseLargerEvidence(queryEvidence, query)
                        if (!query.usable(tuning) || !fits(query, distance)) return@use
                        collectGlobalCandidates(
                            query,
                            matchingReference,
                            distance,
                            matchingScale,
                            referenceScale,
                            viewportBounds,
                            validMapBounds,
                            globalCandidates,
                        )
                    }
                }
                val global = finalizeResult(
                    globalCandidates,
                    queryEvidence,
                    hypotheses.size,
                    usedGlobalRecovery = true,
                )
                return if (global.accepted ||
                    (!local.accepted && finiteCost(global.best) < finiteCost(local.best))
                ) global else local
            } finally {
                distance.release()
            }
        } finally {
            matchingReference.close()
        }
    }

    private data class QueryGeometry(
        val scale: Double,
        val structure: Mat,
        val edges: Mat,
        val bounds: Rect,
        val edgeCount: Int,
        val knownDomain: Mat?,
    ) : AutoCloseable {
        fun usable(tuning: StructureRegistrationTuning): Boolean =
            edgeCount >= tuning.minimumEdgePixels &&
                bounds.width >= tuning.minimumSpanPixels &&
                bounds.height >= tuning.minimumSpanPixels

        override fun close() {
            structure.release()
            edges.release()
            knownDomain?.release()
        }
    }

    private data class QueryEvidence(val edgeCount: Int, val bounds: Rect)

    private fun createQuery(live: StructureFeatures, scale: Double): QueryGeometry {
        val target = Size(
            max(1, (live.edges.cols() / scale).roundToInt()).toDouble(),
            max(1, (live.edges.rows() / scale).roundToInt()).toDouble(),
        )
        val structure = Mat()
        val edges = Mat()
        val knownDomain = live.knownDomain?.let { Mat() }
        try {
            Imgproc.resize(live.structureMask, structure, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
            Imgproc.resize(live.edges, edges, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
            if (knownDomain != null) Imgproc.resize(live.knownDomain!!, knownDomain, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
            val edgeCount = Core.countNonZero(edges)
            val points = Mat()
            val bounds = try {
                Core.findNonZero(edges, points)
                if (points.empty()) Rect() else Imgproc.boundingRect(points)
            } finally {
                points.release()
            }
            return QueryGeometry(scale, structure, edges, bounds, edgeCount, knownDomain)
        } catch (error: Throwable) {
            structure.release()
            edges.release()
            knownDomain?.release()
            throw error
        }
    }

    private fun collectLocalCandidates(
        query: QueryGeometry,
        reference: StructureFeatures,
        distance: Mat,
        scale: Double,
        referenceScale: Double,
        viewport: ScreenRect,
        validBounds: NormalizedRect?,
        expected: Point,
        output: MutableList<StructureCandidate>,
        allowStrongSeedEarlyExit: Boolean,
    ) {
        val scaleCandidateStart = output.size
        val scoreSize = Size(
            (distance.cols() - query.bounds.width + 1).toDouble(),
            (distance.rows() - query.bounds.height + 1).toDouble(),
        )
        if (scoreSize.width <= 0 || scoreSize.height <= 0) return
        val radius = max(tuning.minimumSpanPixels, ceil(tuning.restrictedSearchRadiusPixels / scale).toInt())
        val domain = centeredSearchRect(
            scoreSize,
            expected.x.roundToInt(),
            expected.y.roundToInt(),
            radius,
        )
        val direct = evaluate(
            query, reference, distance, scale, referenceScale, viewport, validBounds,
            expected.x.roundToInt().coerceIn(domain.x, domain.x + domain.width - 1),
            expected.y.roundToInt().coerceIn(domain.y, domain.y + domain.height - 1),
            usedGlobalSearch = false,
        )
        output += direct
        if (allowStrongSeedEarlyExit && isStrongAbsolute(direct)) return

        val template = query.edges.submat(query.bounds)
        val templateFloat = Mat()
        val referencePatch = distance.submat(
            Rect(
                domain.x,
                domain.y,
                query.bounds.width + domain.width - 1,
                query.bounds.height + domain.height - 1,
            ),
        )
        val scores = Mat()
        val coarsePatch = Mat()
        val coarseTemplate = Mat()
        try {
            val factor = max(1, ceil(max(template.cols(), template.rows()) / 240.0).toInt())
            val scorePatch: Mat
            val scoreTemplate: Mat
            if (factor == 1) {
                scorePatch = referencePatch
                scoreTemplate = template
            } else {
                Imgproc.resize(
                    referencePatch,
                    coarsePatch,
                    Size(
                        max(1, referencePatch.cols() / factor).toDouble(),
                        max(1, referencePatch.rows() / factor).toDouble(),
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                Imgproc.resize(
                    template,
                    coarseTemplate,
                    Size(
                        max(1, template.cols() / factor).toDouble(),
                        max(1, template.rows() / factor).toDouble(),
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                scorePatch = coarsePatch
                scoreTemplate = coarseTemplate
            }
            scoreTemplate.convertTo(templateFloat, CvType.CV_32FC1, 1.0 / 255.0)
            Imgproc.matchTemplate(scorePatch, templateFloat, scores, Imgproc.TM_CCORR)
            Core.multiply(scores, Scalar(1.0 / max(1, query.edgeCount)), scores)
            collectScorePeaks(
                scores,
                originX = domain.x,
                originY = domain.y,
                query = query,
                reference = reference,
                distance = distance,
                scale = scale,
                referenceScale = referenceScale,
                viewport = viewport,
                validBounds = validBounds,
                usedGlobalSearch = false,
                output = output,
                coordinateScaleX = referencePatch.cols().toDouble() / scorePatch.cols(),
                coordinateScaleY = referencePatch.rows().toDouble() / scorePatch.rows(),
                suppressionScale = factor.toDouble(),
            )
            output.subList(scaleCandidateStart, output.size).toList()
                .sortedBy(StructureCandidate::compositeCost)
                .take(2)
                .mapTo(output) { candidate ->
                    refineTranslation(
                        candidate,
                        query,
                        reference,
                        distance,
                        scale,
                        referenceScale,
                        viewport,
                        validBounds,
                    )
                }
        } finally {
            template.release()
            templateFloat.release()
            referencePatch.release()
            scores.release()
            coarsePatch.release()
            coarseTemplate.release()
        }
    }

    private fun collectGlobalCandidates(
        query: QueryGeometry,
        reference: StructureFeatures,
        distance: Mat,
        scale: Double,
        referenceScale: Double,
        viewport: ScreenRect,
        validBounds: NormalizedRect?,
        output: MutableList<StructureCandidate>,
    ) {
        val scaleCandidates = mutableListOf<StructureCandidate>()
        val template = query.edges.submat(query.bounds)
        val maximumDimension = max(distance.cols(), distance.rows())
        val desiredFactor = ceil(maximumDimension / 200.0).toInt().coerceAtLeast(1)
        val factor = if (distance.cols() / desiredFactor >= 40 && distance.rows() / desiredFactor >= 40 &&
            template.cols() / desiredFactor >= 16 && template.rows() / desiredFactor >= 16
        ) desiredFactor else 1
        val searchDistance = Mat()
        val searchTemplate = Mat()
        val templateFloat = Mat()
        val scores = Mat()
        try {
            if (factor == 1) {
                distance.copyTo(searchDistance)
                template.copyTo(searchTemplate)
            } else {
                Imgproc.resize(
                    distance,
                    searchDistance,
                    Size(max(1, distance.cols() / factor).toDouble(), max(1, distance.rows() / factor).toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                Imgproc.resize(
                    template,
                    searchTemplate,
                    Size(max(1, template.cols() / factor).toDouble(), max(1, template.rows() / factor).toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            }
            if (searchTemplate.cols() >= searchDistance.cols() || searchTemplate.rows() >= searchDistance.rows()) return
            searchTemplate.convertTo(templateFloat, CvType.CV_32FC1, 1.0 / 255.0)
            Imgproc.matchTemplate(searchDistance, templateFloat, scores, Imgproc.TM_CCORR)
            val suppression = max(
                1,
                max(tuning.minimumSpanPixels, min(query.bounds.width, query.bounds.height) / 8) / factor,
            )
            repeat(tuning.topCandidateCount) {
                val peak = Core.minMaxLoc(scores)
                if (!peak.minVal.isFinite()) return@repeat
                val referenceX = (peak.minLoc.x * distance.cols() / searchDistance.cols()).roundToInt()
                    .coerceIn(0, distance.cols() - query.bounds.width)
                val referenceY = (peak.minLoc.y * distance.rows() / searchDistance.rows()).roundToInt()
                    .coerceIn(0, distance.rows() - query.bounds.height)
                val candidate = evaluate(
                    query, reference, distance, scale, referenceScale, viewport, validBounds,
                    referenceX, referenceY, usedGlobalSearch = true,
                )
                scaleCandidates += candidate
                val left = max(0, peak.minLoc.x.toInt() - suppression)
                val top = max(0, peak.minLoc.y.toInt() - suppression)
                val right = min(scores.cols(), peak.minLoc.x.toInt() + suppression + 1)
                val bottom = min(scores.rows(), peak.minLoc.y.toInt() + suppression + 1)
                Imgproc.rectangle(scores, Rect(left, top, right - left, bottom - top), Scalar.all(Double.POSITIVE_INFINITY), -1)
            }
            output += scaleCandidates
            scaleCandidates.sortedBy(StructureCandidate::compositeCost)
                .take(2)
                .mapTo(output) { candidate ->
                    refineTranslation(
                        candidate,
                        query,
                        reference,
                        distance,
                        scale,
                        referenceScale,
                        viewport,
                        validBounds,
                    )
                }
        } finally {
            template.release()
            searchDistance.release()
            searchTemplate.release()
            templateFloat.release()
            scores.release()
        }
    }

    private fun collectScorePeaks(
        scores: Mat,
        originX: Int,
        originY: Int,
        query: QueryGeometry,
        reference: StructureFeatures,
        distance: Mat,
        scale: Double,
        referenceScale: Double,
        viewport: ScreenRect,
        validBounds: NormalizedRect?,
        usedGlobalSearch: Boolean,
        output: MutableList<StructureCandidate>,
        coordinateScaleX: Double = 1.0,
        coordinateScaleY: Double = 1.0,
        suppressionScale: Double = 1.0,
    ) {
        val suppression = max(
            1,
            (max(tuning.minimumSpanPixels, min(query.bounds.width, query.bounds.height) / 8) /
                suppressionScale).roundToInt(),
        )
        repeat(tuning.topCandidateCount) {
            val peak = Core.minMaxLoc(scores)
            if (!peak.minVal.isFinite()) return@repeat
            // Pyramid rounding can place the last score pixel one pixel beyond
            // the full-resolution patch domain. Keep the boundary hypothesis in
            // bounds rather than letting a rival map abort the whole scan.
            val x = (originX + (peak.minLoc.x * coordinateScaleX).roundToInt())
                .coerceIn(0, distance.cols() - query.bounds.width)
            val y = (originY + (peak.minLoc.y * coordinateScaleY).roundToInt())
                .coerceIn(0, distance.rows() - query.bounds.height)
            val candidate = evaluate(
                query, reference, distance, scale, referenceScale, viewport, validBounds,
                x, y, usedGlobalSearch,
            )
            output += candidate
            val left = max(0, peak.minLoc.x.toInt() - suppression)
            val top = max(0, peak.minLoc.y.toInt() - suppression)
            val right = min(scores.cols(), peak.minLoc.x.toInt() + suppression + 1)
            val bottom = min(scores.rows(), peak.minLoc.y.toInt() + suppression + 1)
            Imgproc.rectangle(scores, Rect(left, top, right - left, bottom - top), Scalar.all(Double.POSITIVE_INFINITY), -1)
        }
    }

    private fun refineTranslation(
        initial: StructureCandidate,
        query: QueryGeometry,
        reference: StructureFeatures,
        distance: Mat,
        scale: Double,
        referenceScale: Double,
        viewport: ScreenRect,
        validBounds: NormalizedRect?,
    ): StructureCandidate {
        var bestX = (initial.referenceX * referenceScale).roundToInt()
        var bestY = (initial.referenceY * referenceScale).roundToInt()
        var bestChamfer = chamferAt(query, distance, bestX, bestY)
        intArrayOf(8, 4, 2, 1).forEach { step ->
            var stepBestX = bestX
            var stepBestY = bestY
            var stepBestChamfer = bestChamfer
            for (dy in -step..step step step) for (dx in -step..step step step) {
                val x = bestX + dx
                val y = bestY + dy
                if (x < 0 || y < 0 || x + query.bounds.width > distance.cols() || y + query.bounds.height > distance.rows()) continue
                val chamfer = chamferAt(query, distance, x, y)
                if (chamfer < stepBestChamfer) {
                    stepBestX = x
                    stepBestY = y
                    stepBestChamfer = chamfer
                }
            }
            bestX = stepBestX
            bestY = stepBestY
            bestChamfer = stepBestChamfer
        }
        return evaluate(
            query,
            reference,
            distance,
            scale,
            referenceScale,
            viewport,
            validBounds,
            bestX,
            bestY,
            initial.usedGlobalSearch,
        )
    }

    private fun chamferAt(query: QueryGeometry, distance: Mat, referenceX: Int, referenceY: Int): Double {
        val queryEdges = query.edges.submat(query.bounds)
        val patch = distance.submat(Rect(referenceX, referenceY, query.bounds.width, query.bounds.height))
        return try {
            Core.mean(patch, queryEdges).`val`[0]
        } finally {
            queryEdges.release()
            patch.release()
        }
    }

    private fun evaluate(
        query: QueryGeometry,
        reference: StructureFeatures,
        distance: Mat,
        matchingScale: Double,
        referenceScale: Double,
        viewport: ScreenRect,
        validBounds: NormalizedRect?,
        referenceX: Int,
        referenceY: Int,
        usedGlobalSearch: Boolean,
    ): StructureCandidate {
        val patchRect = Rect(referenceX, referenceY, query.bounds.width, query.bounds.height)
        val queryEdges = query.edges.submat(query.bounds)
        val queryStructure = query.structure.submat(query.bounds)
        val distancePatch = distance.submat(patchRect)
        val referenceStructure = reference.structureMask.submat(patchRect)
        val queryKnown = query.knownDomain?.submat(query.bounds)
        try {
            val chamfer = Core.mean(distancePatch, queryEdges).`val`[0]
            val withinTolerance = Mat()
            val coveredEdges = Mat()
            val overlap = Mat()
            val knownReference = queryKnown?.let { Mat() }
            val knownOverlap = queryKnown?.let { Mat() }
            try {
                Core.compare(distancePatch, Scalar(tuning.edgeDistanceTolerancePixels), withinTolerance, Core.CMP_LE)
                Core.bitwise_and(withinTolerance, queryEdges, coveredEdges)
                val edgeCoverage = Core.countNonZero(coveredEdges) / query.edgeCount.toDouble().coerceAtLeast(1.0)
                Core.bitwise_and(referenceStructure, queryStructure, overlap)
                val overlapCount = Core.countNonZero(overlap)
                val queryStructureCount = Core.countNonZero(queryStructure)
                val referenceStructureCount = Core.countNonZero(referenceStructure)
                val occupancyCoverage = overlapCount / queryStructureCount.toDouble().coerceAtLeast(1.0)
                // The domain belongs to this resized query, not to one reference seed.
                // Every candidate projects that same query-local known mask onto its own patch.
                // Observed forward walls and occupancy retain their existing semantics.
                val referenceKnownCount: Int
                val referenceKnownOverlapCount: Int
                if (queryKnown == null) {
                    referenceKnownCount = referenceStructureCount
                    referenceKnownOverlapCount = overlapCount
                } else {
                    Core.bitwise_and(referenceStructure, queryKnown, knownReference!!)
                    Core.bitwise_and(overlap, queryKnown, knownOverlap!!)
                    referenceKnownCount = Core.countNonZero(knownReference)
                    referenceKnownOverlapCount = Core.countNonZero(knownOverlap)
                }
                val referenceCoverage = if (referenceKnownCount > 0)
                    referenceKnownOverlapCount / referenceKnownCount.toDouble() else 0.0
                val consistentPartitions = countConsistentPartitions(queryEdges, coveredEdges)
                val composite = chamfer +
                    (1.0 - edgeCoverage) * 4.0 +
                    (1.0 - occupancyCoverage) * 2.0 +
                    (1.0 - referenceCoverage) * 4.0 +
                    max(0, tuning.minimumConsistentPartitions - consistentPartitions) * .75
                val actualScale = matchingScale * referenceScale
                val originalReferenceX = referenceX / referenceScale
                val originalReferenceY = referenceY / referenceScale
                val offsetX = viewport.x + query.bounds.x * matchingScale - referenceX * matchingScale
                val offsetY = viewport.y + query.bounds.y * matchingScale - referenceY * matchingScale
                val isWithinBounds = isWithinValidBounds(
                    originalReferenceX,
                    originalReferenceY,
                    query.bounds.width / referenceScale,
                    query.bounds.height / referenceScale,
                    reference.edges.cols() / referenceScale,
                    reference.edges.rows() / referenceScale,
                    actualScale,
                    validBounds,
                )
                return StructureCandidate(
                    scale = actualScale,
                    referenceX = originalReferenceX.roundToInt(),
                    referenceY = originalReferenceY.roundToInt(),
                    offsetX = offsetX,
                    offsetY = offsetY,
                    chamferPixels = chamfer,
                    edgeCoverage = edgeCoverage,
                    occupancyCoverage = occupancyCoverage,
                    referenceCoverage = referenceCoverage,
                    consistentPartitions = consistentPartitions,
                    compositeCost = composite + if (isWithinBounds) 0.0 else 6.0,
                    isWithinValidBounds = isWithinBounds,
                    usedGlobalSearch = usedGlobalSearch,
                    referenceCoverageDomain = if (queryKnown == null) LEGACY_REFERENCE_COVERAGE_DOMAIN
                        else QUERY_KNOWN_REFERENCE_COVERAGE_DOMAIN,
                    referenceKnownPixels = referenceKnownCount,
                    referenceUnknownPixels = referenceStructureCount - referenceKnownCount,
                    referenceKnownOverlapPixels = referenceKnownOverlapCount,
                    queryKnownPixels = queryKnown?.let(Core::countNonZero) ?: query.bounds.width * query.bounds.height,
                )
            } finally {
                withinTolerance.release()
                coveredEdges.release()
                overlap.release()
                knownReference?.release()
                knownOverlap?.release()
            }
        } finally {
            queryEdges.release()
            queryStructure.release()
            distancePatch.release()
            referenceStructure.release()
            queryKnown?.release()
        }
    }

    private fun countConsistentPartitions(queryEdges: Mat, coveredEdges: Mat): Int {
        val halfWidth = queryEdges.cols() / 2
        val halfHeight = queryEdges.rows() / 2
        val partitions = arrayOf(
            Rect(0, 0, halfWidth, halfHeight),
            Rect(halfWidth, 0, queryEdges.cols() - halfWidth, halfHeight),
            Rect(0, halfHeight, halfWidth, queryEdges.rows() - halfHeight),
            Rect(halfWidth, halfHeight, queryEdges.cols() - halfWidth, queryEdges.rows() - halfHeight),
        )
        return partitions.count { rect ->
            val edges = queryEdges.submat(rect)
            val covered = coveredEdges.submat(rect)
            try {
                val count = Core.countNonZero(edges)
                count >= 12 && Core.countNonZero(covered) / count.toDouble() >= .45
            } finally {
                edges.release()
                covered.release()
            }
        }
    }

    private fun finalizeResult(
        candidates: List<StructureCandidate>,
        queryEvidence: QueryEvidence?,
        scaleHypothesisCount: Int,
        usedGlobalRecovery: Boolean,
    ): StructureRegistrationResult {
        if (queryEvidence == null || queryEvidence.edgeCount < tuning.minimumEdgePixels ||
            queryEvidence.bounds.width < tuning.minimumSpanPixels || queryEvidence.bounds.height < tuning.minimumSpanPixels
        ) return reject(
            StructureRejectionReason.INSUFFICIENT_STRUCTURE,
            "实时帧没有足够的独立地图结构（边缘至少 ${tuning.minimumEdgePixels}px，跨度至少 ${tuning.minimumSpanPixels}px）。",
            candidates,
            queryEvidence,
            scaleHypothesisCount,
            usedGlobalRecovery,
        )
        if (candidates.isEmpty()) return reject(
            StructureRejectionReason.NO_SEARCH_CANDIDATE,
            "实时结构在参考图中没有可评估的位置。",
            candidates,
            queryEvidence,
            scaleHypothesisCount,
            usedGlobalRecovery,
        )

        val distinct = distinctBasins(candidates)
        val absoluteValid = distinct.filter(::passesAbsolute).sortedBy(StructureCandidate::compositeCost)
        val ranked = if (absoluteValid.isNotEmpty()) absoluteValid else distinct.sortedBy(StructureCandidate::compositeCost)
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.compositeCost ?: Double.POSITIVE_INFINITY
        val margin = if (second.isInfinite()) 1.0 else
            ((second - best.compositeCost) / max(.01, second)).coerceIn(0.0, 1.0)
        val requiredMargin = tuning.minimumCandidateMargin * if (best.usedGlobalSearch) 1.25 else 1.0
        val reason = when {
            !best.isWithinValidBounds -> StructureRejectionReason.OUTSIDE_VALID_BOUNDS
            best.chamferPixels > tuning.maximumChamferPixels ||
                best.edgeCoverage < tuning.minimumEdgeCoverage ||
                best.occupancyCoverage < tuning.minimumOccupancyCoverage -> StructureRejectionReason.WEAK_ABSOLUTE_SCORE
            best.consistentPartitions < tuning.minimumConsistentPartitions -> StructureRejectionReason.INCONSISTENT_STRUCTURE
            margin < requiredMargin -> StructureRejectionReason.AMBIGUOUS_CANDIDATES
            else -> StructureRejectionReason.NONE
        }
        val accepted = reason == StructureRejectionReason.NONE && best.chamferPixels <= 3.0
        return StructureRegistrationResult(
            accepted = accepted,
            transform = if (accepted) StructureTransform(best.scale, best.offsetX, best.offsetY) else null,
            rejectionReason = reason,
            failureReason = failureText(reason, best, margin, requiredMargin),
            best = best,
            candidates = distinct.take(tuning.topCandidateCount),
            candidateMargin = margin,
            secondScore = second,
            queryEdgePixels = queryEvidence.edgeCount,
            queryBounds = queryEvidence.bounds,
            scaleHypothesisCount = scaleHypothesisCount,
            usedGlobalRecovery = usedGlobalRecovery,
        )
    }

    private fun passesAbsolute(candidate: StructureCandidate): Boolean =
        candidate.isWithinValidBounds &&
            candidate.chamferPixels <= tuning.maximumChamferPixels &&
            candidate.edgeCoverage >= tuning.minimumEdgeCoverage &&
            candidate.occupancyCoverage >= tuning.minimumOccupancyCoverage &&
            candidate.consistentPartitions >= tuning.minimumConsistentPartitions

    private fun isStrongAbsolute(candidate: StructureCandidate): Boolean =
        candidate.isWithinValidBounds &&
            candidate.chamferPixels <= tuning.maximumChamferPixels * .90 &&
            candidate.edgeCoverage >= tuning.minimumEdgeCoverage + .07 &&
            candidate.occupancyCoverage >= tuning.minimumOccupancyCoverage + .08 &&
            candidate.consistentPartitions >= max(3, tuning.minimumConsistentPartitions)

    private fun distinctBasins(source: List<StructureCandidate>): List<StructureCandidate> {
        val distinct = mutableListOf<StructureCandidate>()
        source.sortedBy(StructureCandidate::compositeCost).forEach { candidate ->
            val duplicate = distinct.any { existing ->
                val scaleTolerance = max(.001, max(existing.scale, candidate.scale) * max(.005, tuning.scaleSearchRadius))
                abs(existing.scale - candidate.scale) <= scaleTolerance &&
                    (hypot(existing.offsetX - candidate.offsetX, existing.offsetY - candidate.offsetY) < 14.0 ||
                        hypot(
                            (existing.referenceX - candidate.referenceX).toDouble(),
                            (existing.referenceY - candidate.referenceY).toDouble(),
                        ) < 14.0)
            }
            if (!duplicate) distinct += candidate
        }
        return distinct
    }

    private fun failureText(
        reason: StructureRejectionReason,
        best: StructureCandidate,
        margin: Double,
        requiredMargin: Double,
    ): String = when (reason) {
        StructureRejectionReason.NONE -> ""
        StructureRejectionReason.OUTSIDE_VALID_BOUNDS -> "最佳结构位姿超出地图有效范围。"
        StructureRejectionReason.WEAK_ABSOLUTE_SCORE ->
            "结构绝对证据不足：Chamfer %.2fpx，边缘覆盖 %.3f，占用覆盖 %.3f。".format(
                best.chamferPixels, best.edgeCoverage, best.occupancyCoverage,
            )
        StructureRejectionReason.INCONSISTENT_STRUCTURE ->
            "结构只在 ${best.consistentPartitions}/4 个分区保持一致。"
        StructureRejectionReason.AMBIGUOUS_CANDIDATES ->
            "结构候选间隔 %.3f，低于 %.3f。".format(margin, requiredMargin)
        else -> reason.name
    }

    private fun reject(
        reason: StructureRejectionReason,
        detail: String,
        candidates: List<StructureCandidate> = emptyList(),
        queryEvidence: QueryEvidence? = null,
        scaleHypothesisCount: Int = 0,
        usedGlobalRecovery: Boolean = false,
    ) = StructureRegistrationResult(
        accepted = false,
        rejectionReason = reason,
        failureReason = detail,
        candidates = candidates,
        queryEdgePixels = queryEvidence?.edgeCount ?: 0,
        queryBounds = queryEvidence?.bounds ?: Rect(),
        scaleHypothesisCount = scaleHypothesisCount,
        usedGlobalRecovery = usedGlobalRecovery,
    )

    private fun buildScaleHypotheses(baseline: Double): List<Double> {
        val count = ceil(tuning.scaleSearchRadius / tuning.scaleSearchStep).toInt().coerceIn(1, 7)
        val effectiveStep = tuning.scaleSearchRadius / count
        return (-count..count)
            .map { baseline * (1.0 + it * effectiveStep) }
            .filter { it > .05 }
            .distinctBy { (it * 1_000_000).roundToInt() }
            .sortedBy { abs(it - baseline) }
    }

    private fun createDistanceMap(edges: Mat): Mat {
        val inverse = Mat()
        val distance = Mat()
        try {
            Core.bitwise_not(edges, inverse)
            Imgproc.distanceTransform(inverse, distance, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
            Core.min(distance, Scalar(tuning.distanceClipPixels), distance)
            return distance
        } catch (error: Throwable) {
            distance.release()
            throw error
        } finally {
            inverse.release()
        }
    }

    private fun resizeFeatures(source: StructureFeatures, scale: Double): StructureFeatures {
        val structure = Mat()
        val edges = Mat()
        val knownDomain = source.knownDomain?.let { Mat() }
        try {
            if (abs(scale - 1.0) < 1e-9) {
                source.structureMask.copyTo(structure)
                source.edges.copyTo(edges)
                if (knownDomain != null) source.knownDomain!!.copyTo(knownDomain)
            } else {
                val target = Size(
                    max(1, (source.edges.cols() * scale).roundToInt()).toDouble(),
                    max(1, (source.edges.rows() * scale).roundToInt()).toDouble(),
                )
                Imgproc.resize(source.structureMask, structure, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
                Imgproc.resize(source.edges, edges, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
                if (knownDomain != null) Imgproc.resize(source.knownDomain!!, knownDomain, target, 0.0, 0.0, Imgproc.INTER_NEAREST)
            }
            return StructureFeatures(structure, edges, knownDomain)
        } catch (error: Throwable) {
            structure.release()
            edges.release()
            knownDomain?.release()
            throw error
        }
    }

    private fun fits(query: QueryGeometry, referenceDistance: Mat): Boolean =
        query.bounds.width > 0 && query.bounds.height > 0 &&
            query.bounds.width <= referenceDistance.cols() && query.bounds.height <= referenceDistance.rows()

    private fun centeredSearchRect(size: Size, centerX: Int, centerY: Int, radius: Int): Rect {
        val width = size.width.toInt()
        val height = size.height.toInt()
        val left = (centerX - radius).coerceIn(0, max(0, width - 1))
        val top = (centerY - radius).coerceIn(0, max(0, height - 1))
        val right = (centerX + radius + 1).coerceIn(left + 1, width)
        val bottom = (centerY + radius + 1).coerceIn(top + 1, height)
        return Rect(left, top, right - left, bottom - top)
    }

    private fun chooseLargerEvidence(current: QueryEvidence?, query: QueryGeometry): QueryEvidence {
        val next = QueryEvidence(query.edgeCount, Rect(query.bounds.x, query.bounds.y, query.bounds.width, query.bounds.height))
        return if (current == null || next.edgeCount > current.edgeCount) next else current
    }

    private fun isWithinValidBounds(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        referenceWidth: Double,
        referenceHeight: Double,
        actualScale: Double,
        normalized: NormalizedRect?,
    ): Boolean {
        val boundsX = (normalized?.x ?: 0.0) * referenceWidth
        val boundsY = (normalized?.y ?: 0.0) * referenceHeight
        val boundsWidth = (normalized?.width ?: 1.0) * referenceWidth
        val boundsHeight = (normalized?.height ?: 1.0) * referenceHeight
        val tolerance = 2.0 / actualScale
        return x >= boundsX - tolerance && y >= boundsY - tolerance &&
            x + width <= boundsX + boundsWidth + tolerance &&
            y + height <= boundsY + boundsHeight + tolerance
    }

    private fun finiteCost(candidate: StructureCandidate?): Double =
        candidate?.compositeCost?.takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY
}
