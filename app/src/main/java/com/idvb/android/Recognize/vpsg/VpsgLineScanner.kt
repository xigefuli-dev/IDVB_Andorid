package com.idvb.android.recognize.vpsg

import android.graphics.Bitmap
import android.util.Log
import com.idvb.android.data.MapRepository
import com.idvb.android.data.ResolvedPrebuiltStructureLine
import com.idvb.android.alignment.AlignmentTransform
import com.idvb.android.alignment.AlignmentResult
import com.idvb.android.alignment.AlignmentEvidence
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.measure
import com.idvb.android.alignment.AlignmentGate
import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.RecognitionCandidate
import com.idvb.android.recognize.RecognitionResult
import com.idvb.android.recognize.VpsgScanDiagnostics
import com.idvb.android.recognize.VpsgFloorTiming
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.gate.ScreenRect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Uses Desktop's prebuilt VPSG lines to locate and verify a map without a visible side door. */
internal class VpsgLineScanner(private val repository: MapRepository,
    private val parallelism: Int = min(3, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)) {
    companion object { const val ROUTE = "vpsg3-bitset-lines-v2" }
    private data class Floor(val map: MapRecord, val record: FloorRecord, val file: java.io.File,
        val proposalFile: java.io.File = file, val structuralFile: java.io.File = file,
        val precisionFile: java.io.File = proposalFile)
    private data class Pose(val scale: Double, val offsetX: Double, val offsetY: Double,
        val coarse: Double)
    private data class Fit(val floor: Floor, val pose: Pose, val support: Double,
        val meanDistance: Double, val passingCells: Int, val spatialConflict: Boolean,
        val longestConflict: Double, val testedPoints: Int,
        val referenceCoverage: Double = 0.0, val referencePoints: Int = 0, val poseUnique: Boolean = false,
        val cellTotals: IntArray = intArrayOf(), val cellHits: IntArray = intArrayOf(),
        val maximumScale: Double = VpsgAlignmentTuning.MAX_SCALE) {
        val score: Double get() = support - min(.15, meanDistance / 50.0)
        val bidirectionalSupport: Double get() = if (referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS)
            min(support, referenceCoverage) else support
        val qualified: Boolean get() = poseUnique && pose.scale in VpsgAlignmentTuning.MIN_SCALE..maximumScale &&
            testedPoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS && support >= VpsgAlignmentTuning.MIN_SUPPORT &&
            !spatialConflict && longestConflict < VpsgAlignmentTuning.MAX_CONFLICT_LENGTH &&
            referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS && referenceCoverage >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT

        fun event(stage: String, final: Boolean = false) = AlignmentLogEvent(stage,
            if (final) if (qualified) "accepted" else "rejected" else "pose-evidence",
            measurements = mapOf("scale" to pose.scale, "offsetX" to pose.offsetX, "offsetY" to pose.offsetY,
                "support" to support, "meanDistance" to meanDistance, "score" to score,
                "passingCells" to passingCells.toDouble(), "longestConflict" to longestConflict,
                "testedPoints" to testedPoints.toDouble(), "referencePoints" to referencePoints.toDouble(),
                "reverseSupport" to referenceCoverage), thresholds = VpsgAlignmentTuning.thresholds + ("maximumScale" to maximumScale),
            gates = listOf(
                AlignmentGate("scale-min", pose.scale, ">=", VpsgAlignmentTuning.MIN_SCALE, pose.scale >= VpsgAlignmentTuning.MIN_SCALE),
                AlignmentGate("scale-max", pose.scale, "<=", maximumScale, pose.scale <= maximumScale),
                AlignmentGate("live-points", testedPoints.toDouble(), ">=", VpsgAlignmentTuning.MIN_TESTED_POINTS.toDouble(), testedPoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS),
                AlignmentGate("visible-support", support, ">=", VpsgAlignmentTuning.MIN_SUPPORT, support >= VpsgAlignmentTuning.MIN_SUPPORT),
                AlignmentGate("spatial-conflict", if (spatialConflict) 1.0 else 0.0, "==", 0.0, !spatialConflict),
                AlignmentGate("longest-conflict", longestConflict, "<", VpsgAlignmentTuning.MAX_CONFLICT_LENGTH, longestConflict < VpsgAlignmentTuning.MAX_CONFLICT_LENGTH),
            ) + if (final) listOf(
                AlignmentGate("unique-pose", if (poseUnique) 1.0 else 0.0, "==", 1.0, poseUnique),
                AlignmentGate("reverse-points", referencePoints.toDouble(), ">=", VpsgAlignmentTuning.MIN_TESTED_POINTS.toDouble(), referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS),
                AlignmentGate("reverse-support", referenceCoverage, ">=", VpsgAlignmentTuning.MIN_REVERSE_SUPPORT, referenceCoverage >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT),
            ) else emptyList(),
            series = mapOf("cellTotals" to cellTotals.map(Int::toDouble), "cellHits" to cellHits.map(Int::toDouble)))
    }
    private data class Pixel(val x: Int, val y: Int)
    private data class PoseKey(val scale: Double, val x: Double, val y: Double)
    /** Request-owned evidence only. Different source indices cannot share a verification. */
    private class PoseVerificationCache(val index: VpsgFastSolver.Index, pixels: List<Pixel>, contours: List<List<Pixel>>) {
        val points = IntArray(pixels.size * 2) { if (it and 1 == 0) pixels[it / 2].x else pixels[it / 2].y }
        val segments = IntArray(contours.filter { it.size >= 2 }.sumOf { it.size } * 4).also { data ->
            var at = 0
            for (contour in contours) if (contour.size >= 2) for (i in contour.indices) {
                val a = contour[i]; val b = contour[(i + 1) % contour.size]
                data[at++] = a.x; data[at++] = a.y; data[at++] = b.x; data[at++] = b.y
            }
        }
        val fits = mutableMapOf<PoseKey, Pair<Fit, String>>()
    }

    private class FloorProfile {
        var prepare = 0L; var translation = 0L; var refinement = 0L; var verification = 0L
        var alternatives = 0L
        var seeds = 0; var refined = 0
        var outcome = "FAILED"
        fun snapshot(floor: Floor, elapsed: Long) = VpsgFloorTiming(floor.map.id, floor.record.key,
            elapsed / 1e6, prepare / 1e6, translation / 1e6, refinement / 1e6, verification / 1e6,
            seeds, refined, outcome)
    }

    private class DistanceIndex(val width: Int, val height: Int, private val values: FloatArray?,
        private val preparedNative: java.nio.ByteBuffer? = null) {
        val nativeBuffer: java.nio.ByteBuffer by lazy {
            preparedNative ?: VpsgNativeKernel.direct(requireNotNull(values))
        }
        private val nativeFloats by lazy { nativeBuffer.asFloatBuffer() }
        constructor(distance: Mat) : this(distance.cols(), distance.rows(),
            if (VpsgNativeKernel.available) null else FloatArray(distance.cols() * distance.rows()).also { distance.get(0, 0, it) },
            if (VpsgNativeKernel.available) VpsgNativeKernel.borrowBuffer(distance.dataAddr(), distance.total() * 4) else null) {
            require(distance.isContinuous) { "Distance map must remain continuous and alive for the owning request" }
        }
        fun at(x: Double, y: Double, scale: Double): Double {
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width - 1 || y >= height - 1)
                return 50.0
            val ix = x.toInt(); val iy = y.toInt()
            val fx = x - ix; val fy = y - iy
            fun value(at: Int) = values?.get(at) ?: nativeFloats.get(at)
            val top = value(iy * width + ix) * (1 - fx) + value(iy * width + ix + 1) * fx
            val bottom = value((iy + 1) * width + ix) * (1 - fx) +
                value((iy + 1) * width + ix + 1) * fx
            return (top * (1 - fy) + bottom * fy) * scale
        }
    }

    /** Immutable row-major edge coordinates, shared by every candidate of this floor. */
    private class ReferenceEdges(val width: Int, val positions: IntArray, val nativePositions: java.nio.ByteBuffer?)

    fun recognize(frame: Bitmap, viewport: ScreenRect, maps: List<MapRecord>,
        progress: ((Double) -> Unit)? = null): RecognitionResult? {
        val started = System.nanoTime()
        val timings = ArrayList<VpsgFloorTiming>()
        progress?.invoke(0.0)
        val classes = repository.loadCatalog().classes.associateBy { it.id }
        fun scanFloor(map: MapRecord): FloorRecord? {
            val selected = classes[map.classId]?.scanFloorKey
            return if (selected == null) map.floors.minByOrNull(FloorRecord::sortOrder)
                else map.floors.firstOrNull { it.key == selected }
        }
        val scanFloors = maps.mapNotNull { map -> scanFloor(map)?.let { map to it } }
        val floors = scanFloors.mapNotNull { (map, floor) ->
            repository.loadRecognitionAssets(map.id, floor).prebuiltStructureLine
                ?.let { Floor(map, floor, it.file) }
        }
        progress?.invoke(.12)
        if (floors.isEmpty()) return null
        val color = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(color).use { live ->
                progress?.invoke(.28)
                val livePixels = sparsePixels(live.edges, 2048)
                if (livePixels.size < 100) return null
                val liveBounds = bounds(livePixels)
                if (liveBounds.width < 100 || liveBounds.height < 75) return null
                val width = live.proposal.cols(); val height = live.proposal.rows()
                val proposalBytes = ByteArray(width * height).also { live.proposal.get(0, 0, it) }
                val votes = VpsgPreparedIndex.sample(proposalBytes, width)
                val correlation = VpsgFastSolver.Correlation(VpsgPreparedIndex.projection(proposalBytes, width, height))
                val contours = contours(live.edges)
                if (contours.isEmpty()) return null
                val liveDistance = distanceMap(live.edges)
                val results = try {
                    val liveIndex = DistanceIndex(liveDistance)
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(min(parallelism, floors.size).coerceAtLeast(1))
                    val tasks = floors.map { floor -> executor.submit(java.util.concurrent.Callable {
                        val floorStarted = System.nanoTime()
                        val profile = FloorProfile()
                        val fit = runCatching {
                            matchFloor(floor, live, livePixels, votes, correlation, contours,
                                liveIndex, viewport, profile)
                        }.onFailure { Log.w("IDVB-Scan", "VPSG floor failed: ${floor.map.id}", it) }.getOrNull()
                        fit to profile.snapshot(floor, System.nanoTime() - floorStarted)
                    }) }
                    try {
                        // Consume in catalog order so scores, ties and diagnostics stay deterministic.
                        tasks.mapIndexedNotNull { index, task ->
                            val (fit, timing) = task.get()
                            timings += timing
                            progress?.invoke(.28 + .68 * (index + 1) / floors.size)
                            fit
                        }.sortedByDescending(Fit::score)
                    } finally {
                        // Even if a callback fails, no worker may outlive the shared immutable Mats.
                        tasks.forEach { task -> runCatching { task.get() } }
                        executor.shutdown()
                    }
                } finally { liveDistance.release() }
                // Keep diagnostics even when all scale priors reject this observation.
                val best = results.firstOrNull { it.qualified }
                // Every scan floor must be evaluated before an identity can be unique.
                val complete = floors.size == maps.size && results.size == floors.size
                val groups = repository.loadCatalog().variantGroups
                val plausible = best?.let { accepted -> results.filter {
                    it.qualified || it.score >= accepted.score - .09 && it.support >= .70
                } }.orEmpty()
                val familyUnique = best != null && com.idvb.android.recognize.sameScanIdentityFamily(
                    best.floor.map.id, best.floor.map.classId, plausible.map { it.floor.map.id }, groups)
                val competitor = best?.let { accepted ->
                    plausible.firstOrNull { it.floor.map.id != accepted.floor.map.id }
                }
                val unique = best != null && complete && familyUnique
                Log.i("IDVB-Scan", "vpsg totalMs=${(System.nanoTime() - started) / 1_000_000}" +
                    " eligible=${maps.size} ready=${floors.size} evaluated=${results.size}" +
                    " livePoints=${livePixels.size} unique=$unique")
                val margin = if (best == null) 0.0 else
                    competitor?.let { best.score - it.score } ?: if (complete) 1.0 else 0.0
                val rankedFits = (results.take(8) + listOfNotNull(best)).distinct()
                    .sortedByDescending(Fit::score)
                val ranked = rankedFits.map { fit ->
                    RecognitionCandidate(
                        map = fit.floor.map,
                        floorKey = fit.floor.record.key,
                        disposition = if (unique && fit === best) CandidateDisposition.RELIABLE
                            else CandidateDisposition.NEEDS_VERIFICATION,
                        templateScore = fit.score,
                        templateMargin = if (fit === best) margin else 0.0,
                        chamferPixels = fit.meanDistance,
                        edgeCoverage = fit.support,
                        referenceCoverage = fit.referenceCoverage,
                        consistentStructurePartitions = fit.passingCells,
                        structureCompositeCost = 1.0 - fit.score,
                        structureScale = fit.pose.scale,
                        structureOffsetX = fit.pose.offsetX,
                        structureOffsetY = fit.pose.offsetY,
                        usedStructureGlobalRecovery = true,
                        matchScale = fit.pose.scale,
                        evidenceLabel = "VPSG 预制线图 · 可见结构支持 ${(fit.support * 100).toInt()}%" +
                            " · 反向支持 ${(fit.referenceCoverage * 100).toInt()}%" +
                            " · 均距 ${"%.1f".format(fit.meanDistance)}px · 分区 ${fit.passingCells}/16" +
                            if (fit === best && !complete) " · 部分地图结构尚未排除，需进一步确认" else "",
                    )
                }
                val included = ranked.mapTo(HashSet()) { it.map.id }
                return RecognitionResult(frame, ranked + maps.filterNot { it.id in included }.map { map ->
                    RecognitionCandidate(map, scanFloor(map)?.key.orEmpty(),
                        CandidateDisposition.CATALOG_ONLY, evidenceLabel = "本次线图扫描未确认")
                }, viewport, route = ROUTE, vpsgDiagnostics = VpsgScanDiagnostics(
                    eligibleFloorCount = maps.size,
                    readyFloorCount = floors.size,
                    evaluatedFloorCount = timings.size,
                    visibleEdgePoints = livePixels.size,
                    elapsedMilliseconds = (System.nanoTime() - started) / 1_000_000.0,
                    identityUnique = unique,
                    algorithm = ROUTE,
                    floorTimings = timings,
                ))
            }
        } finally { color.release() }
    }

    /** Selected-floor alignment deliberately bypasses class scan-floor and identity ranking. */
    fun alignSelected(frame: Bitmap, viewport: ScreenRect, map: MapRecord, floor: FloorRecord,
        lines: ResolvedPrebuiltStructureLine, log: AlignmentLogSink = AlignmentLogSink.NONE,
        proposalLines: ResolvedPrebuiltStructureLine = lines,
        structuralLines: ResolvedPrebuiltStructureLine = lines,
        precisionLines: ResolvedPrebuiltStructureLine = proposalLines): AlignmentResult.Aligned? {
        require(frame.width.toDouble() == viewport.width && frame.height.toDouble() == viewport.height)
        com.idvb.android.recognize.cv.OpenCvRuntime.requireAvailable()
        log.emit(AlignmentLogEvent("vpsg.configuration", "$ROUTE-selected-v10", thresholds =
            VpsgAlignmentTuning.thresholds.filterKeys { it != "minimumLiveWidth" && it != "minimumLiveHeight" } +
                ("maximumScale" to VpsgAlignmentTuning.MAX_SELECTED_SCALE) +
                ("opencvThreadLimit" to com.idvb.android.recognize.cv.OpenCvRuntime.THREAD_LIMIT.toDouble()),
            labels = mapOf("searchBackend" to VpsgNativeKernel.backend,
                "opencvThreadPolicy" to "process-wide-mobile-limit; established-before-opencv-consumers"),
            measurements = mapOf("viewportX" to viewport.x, "viewportY" to viewport.y,
                "opencvThreads" to Core.getNumThreads().toDouble(),
                "viewportWidth" to viewport.width, "viewportHeight" to viewport.height)))
        val color = log.measure("vpsg.bitmap-to-bgr") { CvImages.bitmapToBgr(frame) }
        try {
            log.measure("vpsg.extract.total") { VpsgLiveExtractor.extract(color, log, visibilityScopedReverse = true) }.use { live ->
                val pixels = log.measure("vpsg.observation.sample-edges") {
                    VpsgObservationIndex(live.edges).sample(2048, integerStride = true).map { Pixel(it.x, it.y) }
                }
                val precisionPixels = log.measure("vpsg.observation.sample-precision-walls") {
                    VpsgObservationIndex(live.wallEdges ?: live.edges).sample(2048, integerStride = true).map { Pixel(it.x, it.y) }
                }
                live.wallEdges?.let { VpsgMaskEvidence.attach(log, "precision-wall-edges.gray8", it) }
                log.emit(AlignmentLogEvent("vpsg.observation.precision-domain", series = mapOf(
                    "sampledWallsXY" to precisionPixels.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }),
                    labels = mapOf("policy" to "semantic-wall-only-precision; full-observed-walls-and-source-proven-grates-still-required-at-final-gates")))
                log.emit(AlignmentLogEvent("observation", measurements = mapOf("edgePoints" to pixels.size.toDouble()),
                    gates = listOf(AlignmentGate("minimum-live-points", pixels.size.toDouble(), ">=",
                        VpsgAlignmentTuning.MIN_LIVE_POINTS.toDouble(), pixels.size >= VpsgAlignmentTuning.MIN_LIVE_POINTS)),
                    series = mapOf("sampledEdgesXY" to DoubleArray(pixels.size * 2) {
                        if (it and 1 == 0) pixels[it / 2].x.toDouble() else pixels[it / 2].y.toDouble()
                    }.asList())))
                for ((name, mat) in listOf("observed-edges.gray8" to live.edges, "valid-mask.gray8" to live.valid,
                    "proposal.gray8" to live.proposal, "revealed-domain.gray8" to requireNotNull(live.revealed))) {
                    VpsgMaskEvidence.attach(log, name, mat)
                }
                if (pixels.size < VpsgAlignmentTuning.MIN_LIVE_POINTS) return null
                val liveBounds = bounds(pixels)
                log.emit(AlignmentLogEvent("vpsg.observation.bounds", measurements = mapOf(
                    "left" to liveBounds.x.toDouble(), "top" to liveBounds.y.toDouble(),
                    "width" to liveBounds.width.toDouble(), "height" to liveBounds.height.toDouble()),
                    thresholds = mapOf("compactWidth" to VpsgAlignmentTuning.MIN_LIVE_WIDTH.toDouble(),
                        "compactHeight" to VpsgAlignmentTuning.MIN_LIVE_HEIGHT.toDouble()),
                    labels = mapOf("policy" to "selected-floor-span-is-not-a-rejection-gate")))
                // Pixel extent changes with zoom. For a known floor, compact observations must
                // prove structural support and uniqueness instead of failing before any search.
                val compact = liveBounds.width < VpsgAlignmentTuning.MIN_LIVE_WIDTH ||
                    liveBounds.height < VpsgAlignmentTuning.MIN_LIVE_HEIGHT
                val width = live.proposal.cols(); val height = live.proposal.rows()
                val proposal = log.measure("vpsg.observation.proposal-index") { VpsgObservationIndex(live.proposal) }
                val votes = log.measure("vpsg.observation.sample-votes") { proposal.sample(150) }
                val (projection, verticalProjection) = log.measure("vpsg.observation.projection") { proposal.projections() }
                val correlation = log.measure("vpsg.observation.autocorrelation") { VpsgFastSolver.Correlation(projection) }
                val vertical = log.measure("vpsg.observation.vertical-autocorrelation") {
                    log.emit(AlignmentLogEvent("vpsg.observation.vertical-projection", series = mapOf("projection" to verticalProjection.asList())))
                    VpsgFastSolver.Correlation(verticalProjection)
                }
                log.emit(AlignmentLogEvent("vpsg.observation.votes", series = mapOf("projection" to projection.toList(),
                    "votesXY" to votes.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) })))
                val contours = log.measure("vpsg.observation.contours") { contours(live.edges) }
                log.emit(AlignmentLogEvent("vpsg.observation.contour-gate", gates = listOf(
                    AlignmentGate("contour-count", contours.size.toDouble(), ">", 0.0, contours.isNotEmpty())),
                    thresholds = mapOf("minimumArcLength" to 30.0, "approximationEpsilon" to 1.5),
                    series = contours.mapIndexed { i, points -> "contour${i}XY" to points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) } }.toMap()))
                if (contours.isEmpty()) return null
                val distance = log.measure("vpsg.observation.distance-map") {
                    VpsgObservationDistance.create(live.edges, requireNotNull(live.revealed), log)
                }
                try {
                    val profile = FloorProfile()
                    val liveIndex = log.measure("vpsg.observation.distance-index") { DistanceIndex(distance) }
                    val fit = log.measure("vpsg.solve.total") {
                        matchFloor(Floor(map, floor, lines.file, proposalLines.file, structuralLines.file, precisionLines.file), live, pixels, votes, correlation,
                            contours, liveIndex, viewport, profile, precision = true, log = log,
                            verifyAllSeeds = compact, secondaryCorrelation = vertical, precisionPixels = precisionPixels)
                    }
                    Log.i("IDVB-Align", "Vpsg map=${map.id} floor=${floor.key} outcome=${profile.outcome}" +
                        " support=${fit?.support} reverse=${fit?.referenceCoverage} distance=${fit?.meanDistance}")
                    val measurements = buildMap {
                        put("prepareMs", profile.prepare / 1e6)
                        put("translationMs", profile.translation / 1e6)
                        put("refinementMs", profile.refinement / 1e6)
                        put("verificationMs", profile.verification / 1e6)
                        put("alternativeSearchMs", profile.alternatives / 1e6)
                        put("seeds", profile.seeds.toDouble())
                        fit?.let {
                            put("visibleSupport", it.support); put("referenceSupport", it.referenceCoverage)
                            put("meanResidualPixels", it.meanDistance)
                            put("scale", it.pose.scale); put("offsetX", it.pose.offsetX); put("offsetY", it.pose.offsetY)
                        }
                    }
                    log.emit(AlignmentLogEvent("verification", profile.outcome, measurements))
                    if (fit?.qualified != true) return null
                    return AlignmentResult.Aligned(
                        AlignmentTransform(fit.pose.scale, fit.pose.offsetX, fit.pose.offsetY, lines.width, lines.height),
                        AlignmentEvidence(fit.support, fit.referenceCoverage, fit.meanDistance, measurements))
                } finally { distance.release() }
            }
        } finally { color.release() }
    }

    private fun matchFloor(floor: Floor, live: VpsgLiveExtractor.Observation,
        livePixels: List<Pixel>, votes: List<VpsgFastSolver.Point>, correlation: VpsgFastSolver.Correlation,
        contours: List<List<Pixel>>, liveIndex: DistanceIndex, viewport: ScreenRect, profile: FloorProfile,
        precision: Boolean = false, log: AlignmentLogSink = AlignmentLogSink.NONE,
        verifyAllSeeds: Boolean = false, forcedScale: Double? = null,
        secondaryCorrelation: VpsgFastSolver.Correlation? = null,
        verificationCache: PoseVerificationCache? = null, precisionPixels: List<Pixel> = livePixels): Fit? {
        val stageStarted = System.nanoTime()
        val generation = "${floor.map.mapVersion}|${floor.record.prebuiltStructureLine}"
        val prepared = log.measure("vpsg.prepare-index.total") { VpsgPreparedIndex.load(floor.proposalFile, generation, log) }
        val verificationPrepared = if (floor.file == floor.proposalFile) prepared else
            log.measure("vpsg.prepare-verification-index.total") { VpsgPreparedIndex.load(floor.file, generation, log) }
        val structuralPrepared = when (floor.structuralFile) {
            floor.file -> verificationPrepared
            floor.proposalFile -> prepared
            else -> log.measure("vpsg.prepare-structural-index.total") { VpsgPreparedIndex.load(floor.structuralFile, generation, log) }
        }
        val evidenceCache = verificationCache?.takeIf { it.index === verificationPrepared }
            ?: PoseVerificationCache(verificationPrepared, livePixels, contours)
        val maximumScale = if (precision) VpsgAlignmentTuning.MAX_SELECTED_SCALE else VpsgAlignmentTuning.MAX_SCALE
        profile.prepare = System.nanoTime() - stageStarted
        val translationStarted = System.nanoTime()
        val scale = forcedScale ?: log.measure("vpsg.scale.total") {
            VpsgFastSolver.scale(correlation, prepared, log, maximumScale, preferFundamental = precision)
                ?: if (precision && secondaryCorrelation != null) {
                    val projection = DoubleArray(prepared.height)
                    for (position in requireNotNull(prepared.edgePositions)) {
                        AlignmentCancellation.checkpoint("vpsg.scale.vertical-reference-projection")
                        projection[position / prepared.width]++
                    }
                    val prior = VpsgFastSolver.Correlation(projection).peak()
                    val branch = object : AlignmentLogSink {
                        override val enabled get() = log.enabled
                        override fun record(event: AlignmentLogEvent) = log.emit(event.copy(labels = event.labels + ("scaleAxis" to "vertical")))
                    }
                    log.emit(AlignmentLogEvent("vpsg.scale.axis-recovery", "horizontal-prior-unavailable; measure-vertical",
                        series = mapOf("referenceVerticalProjection" to projection.asList()),
                        measurements = mapOf("referenceVerticalPitch" to prior.pitch, "referenceVerticalRatio" to prior.ratio),
                        labels = mapOf("policy" to "same-axis-live-and-reference-periods; candidate-only; unchanged-final-structural-and-unique-pose-gates")))
                    VpsgFastSolver.scale(secondaryCorrelation, prepared.copy(prior = prior), branch, maximumScale, preferFundamental = true)
                } else null
        }
        if (scale == null) {
            profile.outcome = "SCALE_UNRESOLVED"
            return null
        }
        val width = live.edges.cols(); val height = live.edges.rows()
        // Refinement can merge translation peaks. Retain an actual scored basin beyond
        // both candidates' maximum movement, so its distinctness survives refinement.
        val refinementMovement = kotlin.math.sqrt(2.0) * 7.5 + .045 / scale * hypot(width / 2.0, height / 2.0)
        val minimumRivalDistance = if (precision) VpsgAlignmentTuning.RIVAL_DISTANCE + 2 * refinementMovement else 10.0
        log.emit(AlignmentLogEvent("vpsg.translation.rival-domain", measurements = mapOf(
            "maximumRefinementMovement" to refinementMovement, "minimumSeedDistance" to minimumRivalDistance),
            thresholds = mapOf("coarseFineTranslationRadius" to 7.5, "cumulativeScaleRadius" to .045,
                "finalDistinctDistance" to VpsgAlignmentTuning.RIVAL_DISTANCE),
            labels = mapOf("policy" to "retain-highest-scored-real-basin-beyond-two-refinement-movement-bounds; original-leading-candidates-preserved")))
        val seeds = log.measure("vpsg.translation.total") { VpsgFastSolver.translate(votes, prepared, scale, log, minimumRivalDistance) }
        profile.translation = System.nanoTime() - translationStarted
        profile.seeds = seeds.size
        if (seeds.isEmpty()) { profile.outcome = "NO_TRANSLATION"; return null }
        val pivotX = width / 2.0
        val pivotY = height / 2.0
        log.emit(AlignmentLogEvent("vpsg.refine.pivot", measurements = mapOf("centerX" to pivotX,
            "centerY" to pivotY, "viewportWidth" to width.toDouble(), "viewportHeight" to height.toDouble()),
            labels = mapOf("policy" to "viewport-centre; observed-centroid-recovery-only-after-insufficient-measured-support")))
        val first = seeds.first()
        val selected = (if (verifyAllSeeds) seeds else listOf(first) + seeds.drop(1).filter {
            hypot(it.x - first.x, it.y - first.y) >= VpsgAlignmentTuning.RIVAL_DISTANCE
        }.take(2)).toMutableList()
        if (precision && !verifyAllSeeds) seeds.drop(1).firstOrNull {
            hypot(it.x - first.x, it.y - first.y) >= minimumRivalDistance
        }?.let { if (it !in selected) selected += it }
        log.emit(AlignmentLogEvent("vpsg.verify.candidate-policy", measurements = mapOf(
            "availableSeeds" to seeds.size.toDouble(), "selectedSeeds" to selected.size.toDouble()),
            labels = mapOf("policy" to if (verifyAllSeeds) "compact-observation-all-seeds" else "leading-distinct-seeds")))
        fun refine(seed: VpsgFastSolver.Pose, cx: Double = pivotX, cy: Double = pivotY): VpsgFastSolver.Pose {
            val start = System.nanoTime()
            try { return log.measure("vpsg.refine.discrete.total") {
                VpsgFastSolver.refine(votes, prepared, seed, width, height, log, if (precision) maximumScale else 2.5, cx, cy)
            } }
            finally { profile.refinement += System.nanoTime() - start; profile.refined++ }
        }
        val refined = selected.map { refine(it) }
        val verificationStarted = System.nanoTime()
                val index = DistanceIndex(verificationPrepared.width, verificationPrepared.height, verificationPrepared.distance,
                    if (VpsgNativeKernel.available) verificationPrepared.nativeDistance else null)
                // Precision must not punish real interior boundaries omitted by proposal geometry.
                // Search stays on the sparse wall model; refinement uses complete measured source evidence.
                val precisionPrepared = when (floor.precisionFile) {
                    floor.file -> verificationPrepared
                    floor.proposalFile -> prepared
                    else -> log.measure("vpsg.prepare-precision-index.total") { VpsgPreparedIndex.load(floor.precisionFile, generation, log) }
                }
                val precisionIndex = if (precisionPrepared === verificationPrepared) index else
                    DistanceIndex(precisionPrepared.width, precisionPrepared.height, precisionPrepared.distance,
                        if (VpsgNativeKernel.available) precisionPrepared.nativeDistance else null)
                val referenceEdges = ReferenceEdges(structuralPrepared.width, requireNotNull(structuralPrepared.edgePositions),
                    if (VpsgNativeKernel.available) structuralPrepared.nativeEdges else null)
                log.emit(AlignmentLogEvent("vpsg.verify.reference-index", "shared-prepared-immutable-index",
                    measurements = mapOf("edges" to referenceEdges.positions.size.toDouble(), "bytes" to structuralPrepared.bytes.toDouble(),
                        "forwardEdges" to requireNotNull(verificationPrepared.edgePositions).size.toDouble(),
                        "forwardBytes" to verificationPrepared.bytes.toDouble()),
                    labels = mapOf("verificationArtifact" to "reference.png", "proposalArtifact" to "reference-proposal.png",
                        "precisionArtifact" to if (floor.precisionFile == floor.proposalFile) "reference-proposal.png" else "reference-precision.png",
                        "reverseArtifact" to if (floor.structuralFile == floor.file) "reference.png" else "reference-structural.png",
                        "reversePolicy" to "required-walls-only; source-appearance-can-explain-live-edges-but-is-not-a-required-visible-wall")))
                fun verifyPose(pose: VpsgFastSolver.Pose): Fit {
                    AlignmentCancellation.checkpoint("vpsg.verify.cached-pose")
                    val key = PoseKey(pose.scale, pose.x, pose.y)
                    evidenceCache.fits[key]?.let { (fit, origin) ->
                        log.emit(AlignmentLogEvent("vpsg.verify.pose-cache", "exact-pose-hit",
                            measurements = mapOf("scale" to pose.scale, "viewportX" to pose.x, "viewportY" to pose.y),
                            labels = mapOf("evidenceId" to origin, "scope" to "same-request-and-identical-reference-index; all-forward-reverse-evidence-reused")))
                        return fit
                    }
                    val evidenceId = "pose-${evidenceCache.fits.size}"
                    log.emit(AlignmentLogEvent("vpsg.verify.pose-cache", "miss", labels = mapOf("evidenceId" to evidenceId)))
                    var fit = log.measure("vpsg.verify.pose") { verify(floor,
                        Pose(pose.scale, viewport.x + pose.x, viewport.y + pose.y, pose.score),
                        live, livePixels, contours, index, viewport, log, evidenceCache).copy(maximumScale = maximumScale) }
                    if (precision && fit.support >= VpsgAlignmentTuning.MIN_SUPPORT && !fit.spatialConflict &&
                        fit.longestConflict < VpsgAlignmentTuning.MAX_CONFLICT_LENGTH) {
                        val (coverage, count) = log.measure("vpsg.verify.candidate-reverse") {
                            referenceCoverage(referenceEdges, live, liveIndex, fit.pose, viewport, log)
                        }
                        fit = fit.copy(referenceCoverage = coverage, referencePoints = count)
                    }
                    if (log.enabled) log.emit(fit.event("vpsg.verify.pose-evidence").copy(labels = mapOf("evidenceId" to evidenceId)))
                    evidenceCache.fits[key] = fit to evidenceId
                    return fit
                }
                val fits = refined.map(::verifyPose).toMutableList()
                if (precision && fits.none { it.bidirectionalSupport >= .90 && !it.spatialConflict &&
                    it.longestConflict < VpsgAlignmentTuning.MAX_CONFLICT_LENGTH &&
                    it.referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS }) {
                    val cx = votes.sumOf { it.x.toDouble() } / votes.size
                    val cy = votes.sumOf { it.y.toDouble() } / votes.size
                    log.emit(AlignmentLogEvent("vpsg.refine.centroid-recovery", measurements = mapOf(
                        "centerX" to cx, "centerY" to cy), thresholds = mapOf("strongBidirectionalSupport" to .90),
                        labels = mapOf("policy" to "same-seeds-and-full-gates; recover-off-centre-scale-drift-before-global-retranslation")))
                    for (seed in selected) fits += verifyPose(refine(seed, cx, cy))
                }
                fun bestFit(): Fit = fits.filter { !precision || it.referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS &&
                    it.referenceCoverage >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT }
                    .maxByOrNull { if (precision) it.bidirectionalSupport - min(.15, it.meanDistance / 50.0) else it.score } ?: fits.maxBy(Fit::score)
                var best = bestFit()
                var displacementAnchor = best
                val measuredDisplacements = java.util.IdentityHashMap<Fit, Double>()
                fun displacement(other: Fit): Double {
                    if (other === best) return 0.0
                    if (!precision) return hypot(other.pose.offsetX - best.pose.offsetX, other.pose.offsetY - best.pose.offsetY)
                    if (displacementAnchor !== best) { measuredDisplacements.clear(); displacementAnchor = best }
                    measuredDisplacements[other]?.let { return it }
                    // Scale changes move the reference origin even when every visible wall stays
                    // in the same basin. Compare poses on the observed geometry, not at (0,0).
                    val measured = observedDisplacement(best, other, livePixels, viewport)
                    measuredDisplacements[other] = measured
                    return measured
                }
                fun rival() = fits.filter { displacement(it) >= VpsgAlignmentTuning.RIVAL_DISTANCE }.maxByOrNull(Fit::support)
                // Desktop retains the scored pool. If refinement merges all competing peaks,
                // refine the remainder rather than inventing a uniqueness margin.
                var extraRefinement = 0L
                if (rival() == null && best.support >= .70) {
                    val before = profile.refinement
                    for (seed in seeds) if (seed !in selected) fits += verifyPose(refine(seed))
                    extraRefinement = profile.refinement - before
                    best = bestFit()
                }
                if (precision) {
                    // Compare every structurally viable candidate at the same
                    // photometric precision. Comparing a refined winner with an
                    // unrefined candidate can turn scale quantization into a rival.
                    val count = min(256, precisionPixels.size)
                    val points = (0 until count).map {
                        val pixel = precisionPixels[it * precisionPixels.size / count]
                        VpsgFastSolver.Point(pixel.x, pixel.y)
                    }
                    log.measure("vpsg.refine.precision.candidates.total") {
                        for (candidate in fits.indices) {
                            AlignmentCancellation.checkpoint("vpsg.refine.precision.candidate")
                            val original = fits[candidate]
                            if (!original.copy(poseUnique = true).qualified) continue
                            val branch = object : AlignmentLogSink {
                                override val enabled get() = log.enabled
                                override fun record(event: AlignmentLogEvent) = log.emit(event.copy(
                                    labels = event.labels + ("precisionCandidate" to candidate.toString())))
                            }
                            val seed = VpsgFastSolver.Pose(original.pose.scale,
                                original.pose.offsetX - viewport.x, original.pose.offsetY - viewport.y)
                            branch.emit(AlignmentLogEvent("vpsg.refine.precision.seed", measurements = mapOf(
                                "coarseScale" to seed.scale, "pitchScale" to scale, "scale" to seed.scale,
                                "viewportX" to seed.x, "viewportY" to seed.y), labels = mapOf(
                                "policy" to "verified-structural-candidate; pitch-is-proposal-only; uniqueness-after-equal-precision")))
                            val pose = VpsgPrecisionRefiner.refine(points, seed, width, height, precisionIndex::at, branch,
                                if (VpsgNativeKernel.available) precisionIndex.nativeBuffer else null,
                                precisionIndex.width, precisionIndex.height) ?: continue
                            val checked = verifyPose(pose)
                            val committed = checked.copy(poseUnique = true).qualified
                            if (committed) fits[candidate] = checked
                            branch.emit(checked.event("vpsg.refine.precision-recheck").copy(labels = mapOf(
                                "precisionCommitted" to committed.toString(),
                                "commitPolicy" to "converged-observable-source-photometric-minimum; unchanged-structural-gates; uniqueness-deferred-until-all-candidates-compared")))
                        }
                    }
                    best = bestFit()
                }
                val rawRivals = fits.filter { displacement(it) >= VpsgAlignmentTuning.RIVAL_DISTANCE }
                // A forward-only tie is not ambiguity when the rival predicts walls in known
                // empty floor. Keep unknown/unsampled rivals; only measured contradictions exclude one.
                val viableRivals = rawRivals.filterNot { precision &&
                    (it.spatialConflict || it.longestConflict >= VpsgAlignmentTuning.MAX_CONFLICT_LENGTH ||
                        it.referencePoints >= VpsgAlignmentTuning.MIN_TESTED_POINTS && it.referenceCoverage < VpsgAlignmentTuning.MIN_REVERSE_SUPPORT) }
                fun uniquenessSupport(fit: Fit) = if (precision) fit.bidirectionalSupport else fit.support
                val runnerUp = viableRivals.maxByOrNull(::uniquenessSupport)
                val uniquePose = rawRivals.isNotEmpty() && (runnerUp == null || uniquenessSupport(best) - uniquenessSupport(runnerUp) >= VpsgAlignmentTuning.MIN_POSE_MARGIN)
                if (log.enabled) log.emit(AlignmentLogEvent("vpsg.verify.uniqueness", measurements = mapOf(
                    "winnerSupport" to best.support, "runnerUpSupport" to (runnerUp?.support ?: Double.NaN),
                    "winnerUniquenessSupport" to uniquenessSupport(best), "runnerUpUniquenessSupport" to (runnerUp?.let(::uniquenessSupport) ?: Double.NaN),
                    "margin" to (runnerUp?.let { uniquenessSupport(best) - uniquenessSupport(it) } ?: Double.NaN),
                    "distinctRivals" to rawRivals.size.toDouble(), "contradictedRivals" to (rawRivals.size - viableRivals.size).toDouble()),
                    gates = listOf(if (runnerUp != null) AlignmentGate("pose-margin", uniquenessSupport(best) - uniquenessSupport(runnerUp),
                        ">=", VpsgAlignmentTuning.MIN_POSE_MARGIN, uniquePose) else AlignmentGate("distinct-rivals-all-contradicted",
                        (rawRivals.size - viableRivals.size).toDouble(), "==", rawRivals.size.toDouble(), uniquePose)),
                    series = mapOf("candidateScaleXYSupportDisplacement" to fits.flatMap {
                        listOf(it.pose.scale, it.pose.offsetX, it.pose.offsetY, it.support, displacement(it)) },
                        "candidateReverseSupportAndCount" to fits.flatMap { listOf(it.referenceCoverage, it.referencePoints.toDouble()) }),
                    thresholds = mapOf("distinctDistancePixels" to VpsgAlignmentTuning.RIVAL_DISTANCE),
                    labels = mapOf("runnerUpPresent" to (runnerUp != null).toString(),
                        "scoreDomain" to if (precision) "minimum-of-measured-forward-and-reverse-support; unmeasured-rival-keeps-forward-upper-bound" else "forward-support",
                        "distanceDomain" to if (precision) "maximum-displacement-on-observed-points" else "reference-origin")))
                val (coverage, tested) = if (best.referencePoints > 0) best.referenceCoverage to best.referencePoints
                    else log.measure("vpsg.verify.reverse") { referenceCoverage(referenceEdges, live, liveIndex, best.pose, viewport, log) }
                var result = best.copy(referenceCoverage = coverage, referencePoints = tested, poseUnique = uniquePose)
                if (log.enabled) log.emit(result.event("vpsg.verify.final", final = true))
                profile.verification = System.nanoTime() - verificationStarted - extraRefinement
                profile.outcome = if (result.qualified) "VERIFIED" else "STRUCTURE_UNRESOLVED"
                if (precision && (!result.qualified || result.bidirectionalSupport < .90) && forcedScale == null) {
                    val alternateStarted = System.nanoTime()
                    val proposals = listOfNotNull(correlation, secondaryCorrelation).flatMap {
                        it.alternatives(prepared.prior.pitch * VpsgAlignmentTuning.MIN_SCALE,
                            prepared.prior.pitch * maximumScale)
                    }.sortedByDescending { it.ratio }.map { it.pitch / prepared.prior.pitch }
                    // Autocorrelation on a partially revealed room can bias the pitch by
                    // about 3%. Discrete refinement couples scale to the viewport centre
                    // with only a +/-6 px translation window; it cannot reliably recover
                    // an off-centre observation. Re-run translation in the nearby scale
                    // band before rejecting it, using the same final structural gates.
                    val nearby = listOf(.02, -.02, .04, -.04).map { scale + it }
                        .filter { it in VpsgAlignmentTuning.MIN_SCALE..maximumScale }
                    val distant = mutableListOf<Double>()
                    for (proposal in proposals) if (kotlin.math.abs(proposal - scale) > .04 &&
                        distant.none { kotlin.math.abs(it - proposal) <= .04 }) distant += proposal
                    val alternatives = nearby + distant
                    log.emit(AlignmentLogEvent("vpsg.scale.alternatives", "primary-mode-requires-search",
                        measurements = mapOf("primaryBidirectionalSupport" to result.bidirectionalSupport),
                        thresholds = mapOf("strongPrimarySupport" to .90, "distinctScaleDistance" to .04,
                            "nearbyScaleStep" to .02, "nearbyScaleRadius" to .04),
                        series = mapOf("scales" to alternatives, "nearbyScales" to nearby),
                        labels = mapOf("policy" to "retranslate-nearby-then-distant; same-full-structural-gates")))
                    val accepted = mutableListOf<Fit>().apply { if (result.qualified) add(result) }
                    fun agrees(winner: Fit, other: Fit): Boolean =
                        winner.bidirectionalSupport - other.bidirectionalSupport >= VpsgAlignmentTuning.MIN_POSE_MARGIN ||
                            livePixels.all { point ->
                                AlignmentCancellation.checkpoint("vpsg.scale.consensus")
                                val x = viewport.x + point.x; val y = viewport.y + point.y
                                hypot((x - winner.pose.offsetX) / winner.pose.scale * other.pose.scale + other.pose.offsetX - x,
                                    (y - winner.pose.offsetY) / winner.pose.scale * other.pose.scale + other.pose.offsetY - y) < VpsgAlignmentTuning.RIVAL_DISTANCE
                            }
                    var attempts = 0
                    for ((attempt, alternative) in alternatives.withIndex()) {
                        AlignmentCancellation.checkpoint("vpsg.scale.alternative")
                        // Apply the existing strong-primary policy only after the whole
                        // nearby band has been checked, including its competing poses.
                        if (attempt == nearby.size) {
                            val winner = accepted.maxByOrNull { it.bidirectionalSupport }
                            val strong = winner != null && winner.bidirectionalSupport >= .90 && accepted.all { agrees(winner, it) }
                            log.emit(AlignmentLogEvent("vpsg.scale.recovery-decision",
                                if (strong) "strong-nearby-consensus; distant-modes-not-evaluated" else "continue-distant-modes",
                                measurements = mapOf("acceptedCandidates" to accepted.size.toDouble(),
                                    "bestBidirectionalSupport" to (winner?.bidirectionalSupport ?: 0.0)),
                                thresholds = mapOf("strongSupport" to .90),
                                series = mapOf("unevaluatedDistantScales" to if (strong) distant else emptyList()),
                                labels = mapOf("policy" to "same-strong-support-policy-as-primary; full-nearby-band-evaluated")))
                            if (strong) break
                        }
                        attempts++
                        // Separate every candidate's numeric events and artifacts in the package.
                        val branch = object : AlignmentLogSink {
                            override val enabled get() = log.enabled
                            override fun record(event: AlignmentLogEvent) = log.emit(event.copy(
                                labels = event.labels + ("scaleAttempt" to (attempt + 1).toString()) +
                                    ("scaleSearchKind" to if (attempt < nearby.size) "nearby-retranslation" else "distant-mode")))
                            override fun attach(name: String, bytes: () -> ByteArray) = log.attach("scale-${attempt + 1}-$name", bytes)
                            override fun attachOwned(name: String, bytes: () -> ByteArray) = log.attachOwned("scale-${attempt + 1}-$name", bytes)
                            override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) = log.attachDirect("scale-${attempt + 1}-$name", capture)
                        }
                val fit = matchFloor(floor, live, livePixels, votes, correlation, contours, liveIndex,
                            viewport, FloorProfile(), precision, branch, verifyAllSeeds, alternative,
                            verificationCache = evidenceCache, precisionPixels = precisionPixels)
                        if (fit?.qualified == true) accepted += fit
                    }
                    val bestAlternative = accepted.maxByOrNull { it.bidirectionalSupport - min(.15, it.meanDistance / 50.0) }
                    if (bestAlternative != null) {
                        val samePose = accepted.all { agrees(bestAlternative, it) }
                        result = bestAlternative.copy(poseUnique = samePose)
                        profile.outcome = if (result.qualified) "VERIFIED" else "SCALE_AMBIGUOUS"
                    }
                    log.emit(result.event("vpsg.verify.alternative-final", final = true))
                    profile.alternatives = System.nanoTime() - alternateStarted
                    log.emit(AlignmentLogEvent("vpsg.scale.alternatives.total",
                        if (attempts == alternatives.size) "all-candidates-complete" else "strong-nearby-consensus",
                        measurements = mapOf("attempts" to attempts.toDouble(), "plannedAttempts" to alternatives.size.toDouble()), durationNanos = profile.alternatives,
                        labels = mapOf("timing" to "independent-of-primary-phase-timings; nested-in-solve-total")))
                }
                return result
    }
    private fun observedDisplacement(anchor: Fit, other: Fit, pixels: List<Pixel>, viewport: ScreenRect): Double =
        pixels.maxOf { pixel ->
            val x = viewport.x + pixel.x; val y = viewport.y + pixel.y
            hypot((x - anchor.pose.offsetX) / anchor.pose.scale * other.pose.scale + other.pose.offsetX - x,
                (y - anchor.pose.offsetY) / anchor.pose.scale * other.pose.scale + other.pose.offsetY - y)
        }

    private fun verify(floor: Floor, pose: Pose, live: VpsgLiveExtractor.Observation,
        livePixels: List<Pixel>, contours: List<List<Pixel>>, index: DistanceIndex,
        viewport: ScreenRect, log: AlignmentLogSink = AlignmentLogSink.NONE,
        geometry: PoseVerificationCache? = null): Fit {
        val tx = pose.offsetX - viewport.x
        val ty = pose.offsetY - viewport.y
        if (VpsgNativeKernel.available) {
            val width = live.edges.cols(); val height = live.edges.rows()
            val points = requireNotNull(geometry).points
            val segments = geometry.segments
            val values = log.measure("vpsg.verify.forward-native.total") { VpsgNativeKernel.verifyForward(points, segments,
                index.nativeBuffer, index.width, index.height, width, height, pose.scale, tx, ty, log.enabled) }
            val totals = IntArray(16) { values[6 + it].toInt() }; val cellHits = IntArray(16) { values[22 + it].toInt() }
            val passing = (0..15).count { totals[it] >= VpsgAlignmentTuning.MIN_CELL_POINTS && cellHits[it] >= totals[it] * VpsgAlignmentTuning.MIN_CELL_SUPPORT }
            val conflict = (0..15).any { totals[it] >= VpsgAlignmentTuning.MIN_CELL_POINTS && cellHits[it] < totals[it] * VpsgAlignmentTuning.MIN_CELL_SUPPORT }
            for ((stage, position) in listOf("vpsg.verify.visible-support" to 4, "vpsg.verify.contour-conflict" to 5))
                log.emit(AlignmentLogEvent(stage, durationNanos = values[position].toLong(), labels = mapOf(
                    "costScope" to "independent-native-phase; nested-in-forward-native.total", "timestampScope" to "adapter-receipt; phase-end-not-sampled")))
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.verify.residual-data", measurements = mapOf(
                "scale" to pose.scale, "offsetX" to pose.offsetX, "offsetY" to pose.offsetY),
                labels = mapOf("contourCheckExecuted" to (values[3] == 1.0).toString(), "backend" to VpsgNativeKernel.backend),
                series = mapOf("sampledEdgesDistances" to values.asList().subList(38, 38 + livePixels.size),
                    "contourSegmentsX0Y0X1Y1MaxConflict" to values.asList().subList(38 + livePixels.size, values.size))))
            return Fit(floor, pose, values[0] / livePixels.size, values[1] / livePixels.size,
                passing, conflict, values[2], livePixels.size, cellTotals = totals, cellHits = cellHits)
        }
        fun distance(x: Int, y: Int) = index.at((x - tx) / pose.scale, (y - ty) / pose.scale,
            pose.scale)
        var hits = 0
        var sum = 0.0
        val width = live.edges.cols(); val height = live.edges.rows()
        val totals = IntArray(16)
        val cellHits = IntArray(16)
        val distances = if (log.enabled) DoubleArray(livePixels.size) else null
        var pointIndex = 0
        log.measure("vpsg.verify.visible-support") { for ((pixelIndex, pixel) in livePixels.withIndex()) {
            if (pixelIndex and 63 == 0) AlignmentCancellation.checkpoint("vpsg.verify.visible-point-block")
            val d = distance(pixel.x, pixel.y)
            distances?.set(pointIndex++, d)
            val cell = min(3, pixel.x * 4 / width) +
                4 * min(3, pixel.y * 4 / height)
            totals[cell]++
            sum += d
            if (d <= VpsgAlignmentTuning.DISTANCE_TOLERANCE) { hits++; cellHits[cell]++ }
        } }
        val passingCells = (0..15).count { totals[it] >= VpsgAlignmentTuning.MIN_CELL_POINTS && cellHits[it] >= totals[it] * VpsgAlignmentTuning.MIN_CELL_SUPPORT }
        val spatialConflict = (0..15).any { totals[it] >= VpsgAlignmentTuning.MIN_CELL_POINTS && cellHits[it] < totals[it] * VpsgAlignmentTuning.MIN_CELL_SUPPORT }
        var longest = 0.0
        val checkContours = hits / livePixels.size.toDouble() >= VpsgAlignmentTuning.MIN_SUPPORT && !spatialConflict
        val segments = if (log.enabled) ArrayList<Double>() else null
        log.measure("vpsg.verify.contour-conflict") { if (checkContours) {
            for (contour in contours) {
                AlignmentCancellation.checkpoint("vpsg.verify.contour")
                if (contour.size < 2) continue
                for (i in contour.indices) {
                    val a = contour[i]
                    val b = contour[(i + 1) % contour.size]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val steps = max(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    if (steps == 0) continue
                    val stride = hypot(dx.toDouble(), dy.toDouble()) / steps
                    var run = 0.0
                    var segmentMax = 0.0
                    for (step in 0..steps) {
                        if (step and 63 == 0) AlignmentCancellation.checkpoint("vpsg.verify.contour-segment")
                        val x = (a.x + dx * step.toDouble() / steps).roundToInt()
                        val y = (a.y + dy * step.toDouble() / steps).roundToInt()
                        if (distance(x, y) > VpsgAlignmentTuning.DISTANCE_TOLERANCE) run += if (step == 0) 0.0 else stride
                        else run = 0.0
                        longest = max(longest, run)
                        segmentMax = max(segmentMax, run)
                    }
                    segments?.addAll(listOf(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(), segmentMax))
                }
            }
        } }
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.verify.residual-data", measurements = mapOf(
            "scale" to pose.scale, "offsetX" to pose.offsetX, "offsetY" to pose.offsetY),
            labels = mapOf("contourCheckExecuted" to checkContours.toString()),
            series = mapOf("sampledEdgesDistances" to (distances?.asList() ?: emptyList()), "contourSegmentsX0Y0X1Y1MaxConflict" to segments.orEmpty())))
        return Fit(floor, pose, hits / livePixels.size.toDouble(), sum / livePixels.size,
            passingCells, spatialConflict, longest, livePixels.size, cellTotals = totals, cellHits = cellHits)
    }

    /** Reject a dense reference that happens to contain every observed line plus many absent ones. */
    private fun referenceCoverage(reference: ReferenceEdges, live: VpsgLiveExtractor.Observation,
        liveDistance: DistanceIndex, pose: Pose, viewport: ScreenRect, log: AlignmentLogSink = AlignmentLogSink.NONE): Pair<Double, Int> {
        val total = reference.positions.size
        if (total == 0) return 0.0 to 0
        if (VpsgNativeKernel.available) {
            val domain = live.visibilityBounds
            val values = VpsgNativeKernel.reverseCoverage(requireNotNull(reference.nativePositions), total, reference.width,
                live.nativeVisibility, live.valid.cols(), live.valid.rows(), liveDistance.nativeBuffer,
                pose.scale, pose.offsetX - viewport.x, pose.offsetY - viewport.y, live.revealed == null, log.enabled,
                domain.x, domain.y, domain.width, domain.height)
            val tested = values[1].toInt()
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.verify.reverse-data", measurements = mapOf(
                "referenceEdges" to total.toDouble(), "eligible" to values[4], "sampled" to values[1],
                "tested" to values[1], "hits" to values[0], "outsideViewport" to values[2], "unknownOrExcluded" to values[3]),
                thresholds = mapOf("sampleLimit" to 2048.0),
                labels = mapOf("sampleDomain" to if (live.revealed != null) "uniform-row-major-after-visibility-classification" else "legacy-global-row-major-before-visibility",
                    "domainArtifact" to if (live.revealed != null) "revealed-domain.gray8" else "valid-mask.gray8", "backend" to VpsgNativeKernel.backend),
                series = mapOf("referenceXYObservedXYResidual" to values.asList().subList(5, values.size))))
            return (if (tested == 0) 0.0 else values[0] / tested) to tested
        }
        val legacyStep = max(1, (total + 2047) / 2048)
        val valid = live.visibilityBytes
        val width = live.valid.cols()
        val height = live.valid.rows()
        val tx = pose.offsetX - viewport.x
        val ty = pose.offsetY - viewport.y
        var seen = 0
        var tested = 0
        var hits = 0
        var outside = 0
        var unknown = 0
        val eligible = IntArray(total)
        var eligibleCount = 0
        val samples = if (log.enabled) DoubleArray(min(2048, total) * 5) else null
        var sampleValues = 0
        for (position in reference.positions) {
                if (seen and 255 == 0) AlignmentCancellation.checkpoint("vpsg.verify.reverse-edge-block")
                val x = position % reference.width; val y = position / reference.width
                seen++
                if (live.revealed == null && (seen - 1) % legacyStep != 0) continue
                val screenX = x * pose.scale + tx
                val screenY = y * pose.scale + ty
                val ix = screenX.roundToInt()
                val iy = screenY.roundToInt()
                if (ix !in 0 until width || iy !in 0 until height) { outside++; continue }
                if ((valid[iy * width + ix].toInt() and 255) <= 128) { unknown++; continue }
                eligible[eligibleCount++] = position
        }
        // Sample the actually testable domain. Sampling the whole reference first made a
        // small revealed room fail the point-count gate despite hundreds of visible walls.
        val count = min(2048, eligibleCount)
        for (i in 0 until count) {
                AlignmentCancellation.checkpoint("vpsg.verify.reverse-sample")
                val position = eligible[i * eligibleCount / count]
                val x = position % reference.width; val y = position / reference.width
                val screenX = x * pose.scale + tx
                val screenY = y * pose.scale + ty
                tested++
                val residual = liveDistance.at(screenX, screenY, 1.0)
                if (residual <= VpsgAlignmentTuning.DISTANCE_TOLERANCE) hits++
                if (samples != null) {
                    samples[sampleValues++] = x.toDouble(); samples[sampleValues++] = y.toDouble()
                    samples[sampleValues++] = screenX; samples[sampleValues++] = screenY
                    samples[sampleValues++] = residual
                }
        }
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.verify.reverse-data", measurements = mapOf(
            "referenceEdges" to total.toDouble(), "eligible" to eligibleCount.toDouble(), "sampled" to tested.toDouble(),
            "tested" to tested.toDouble(), "hits" to hits.toDouble(),
            "outsideViewport" to outside.toDouble(), "unknownOrExcluded" to unknown.toDouble()),
            thresholds = mapOf("sampleLimit" to 2048.0),
            labels = mapOf("sampleDomain" to if (live.revealed != null) "uniform-row-major-after-visibility-classification" else "legacy-global-row-major-before-visibility",
                "domainArtifact" to if (live.revealed != null) "revealed-domain.gray8" else "valid-mask.gray8"),
            series = mapOf("referenceXYObservedXYResidual" to (samples?.asList()?.subList(0, sampleValues) ?: emptyList()))))
        return (if (tested == 0) 0.0 else hits.toDouble() / tested) to tested
    }

    private fun contours(edges: Mat): List<List<Pixel>> {
        val source = edges.clone()
        val contourMats = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(source, contourMats, hierarchy, Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE)
            return contourMats.mapNotNull { contour ->
                val sourcePoints = MatOfPoint2f(*contour.toArray())
                val approximated = MatOfPoint2f()
                try {
                    if (Imgproc.arcLength(sourcePoints, false) < 30.0) null
                    else {
                        Imgproc.approxPolyDP(sourcePoints, approximated, 1.5, true)
                        approximated.toArray().map { Pixel(it.x.roundToInt(), it.y.roundToInt()) }
                    }
                } finally { sourcePoints.release(); approximated.release() }
            }
        } finally {
            source.release(); hierarchy.release(); contourMats.forEach(MatOfPoint::release)
        }
    }

    private fun distanceMap(binary: Mat): Mat {
        val inverted = Mat()
        val distance = Mat()
        try {
            Core.bitwise_not(binary, inverted)
            Imgproc.distanceTransform(inverted, distance, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
            return distance
        } catch (error: Throwable) { distance.release(); throw error }
        finally { inverted.release() }
    }

    private fun sparsePixels(binary: Mat, limit: Int): List<Pixel> {
        val total = Core.countNonZero(binary)
        if (total == 0) return emptyList()
        val step = max(1, (total + limit - 1) / limit)
        val row = ByteArray(binary.cols())
        val points = ArrayList<Pixel>(min(total, limit + 1))
        var seen = 0
        for (y in 0 until binary.rows()) {
            binary.get(y, 0, row)
            for (x in row.indices) if (row[x].toInt() and 255 > 128) {
                if (seen % step == 0 && points.size < limit) points += Pixel(x, y)
                seen++
            }
        }
        return points
    }

    private fun bounds(points: List<Pixel>): Rect {
        val left = points.minOf(Pixel::x)
        val top = points.minOf(Pixel::y)
        return Rect(left, top, points.maxOf(Pixel::x) - left + 1, points.maxOf(Pixel::y) - top + 1)
    }

}
