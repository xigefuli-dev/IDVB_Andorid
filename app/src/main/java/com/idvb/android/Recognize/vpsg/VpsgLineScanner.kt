package com.idvb.android.recognize.vpsg

import android.graphics.Bitmap
import android.util.Log
import com.idvb.android.data.MapRepository
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
    private data class Floor(val map: MapRecord, val record: FloorRecord, val file: java.io.File)
    private data class Pose(val scale: Double, val offsetX: Double, val offsetY: Double,
        val coarse: Double)
    private data class Fit(val floor: Floor, val pose: Pose, val support: Double,
        val meanDistance: Double, val passingCells: Int, val spatialConflict: Boolean,
        val longestConflict: Double, val testedPoints: Int,
        val referenceCoverage: Double = 0.0, val referencePoints: Int = 0, val poseUnique: Boolean = false) {
        val score: Double get() = support - min(.15, meanDistance / 50.0)
        val qualified: Boolean get() = poseUnique && pose.scale in .4..2.4 && testedPoints >= 80 && support >= .88 &&
            !spatialConflict && longestConflict < 30.0 &&
            referencePoints >= 80 && referenceCoverage >= .65
    }
    private data class Pixel(val x: Int, val y: Int)

    private class FloorProfile {
        var prepare = 0L; var translation = 0L; var refinement = 0L; var verification = 0L
        var seeds = 0; var refined = 0
        var outcome = "FAILED"
        fun snapshot(floor: Floor, elapsed: Long) = VpsgFloorTiming(floor.map.id, floor.record.key,
            elapsed / 1e6, prepare / 1e6, translation / 1e6, refinement / 1e6, verification / 1e6,
            seeds, refined, outcome)
    }

    private class DistanceIndex(distance: Mat) {
        val width = distance.cols()
        val height = distance.rows()
        private val values = FloatArray(width * height).also { distance.get(0, 0, it) }
        fun at(x: Double, y: Double, scale: Double): Double {
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width - 1 || y >= height - 1)
                return 50.0
            val ix = x.toInt(); val iy = y.toInt()
            val fx = x - ix; val fy = y - iy
            val top = values[iy * width + ix] * (1 - fx) + values[iy * width + ix + 1] * fx
            val bottom = values[(iy + 1) * width + ix] * (1 - fx) +
                values[(iy + 1) * width + ix + 1] * fx
            return (top * (1 - fy) + bottom * fy) * scale
        }
    }

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

    private fun matchFloor(floor: Floor, live: VpsgLiveExtractor.Observation,
        livePixels: List<Pixel>, votes: List<VpsgFastSolver.Point>, correlation: VpsgFastSolver.Correlation,
        contours: List<List<Pixel>>, liveIndex: DistanceIndex, viewport: ScreenRect, profile: FloorProfile): Fit? {
        val stageStarted = System.nanoTime()
        val generation = "${floor.map.mapVersion}|${floor.record.prebuiltStructureLine}"
        val prepared = VpsgPreparedIndex.load(floor.file, generation)
        profile.prepare = System.nanoTime() - stageStarted
        val translationStarted = System.nanoTime()
        val scale = VpsgFastSolver.scale(correlation, prepared)
        if (scale == null) {
            profile.outcome = "SCALE_UNRESOLVED"
            return null
        }
        val seeds = VpsgFastSolver.translate(votes, prepared, scale)
        profile.translation = System.nanoTime() - translationStarted
        profile.seeds = seeds.size
        if (seeds.isEmpty()) { profile.outcome = "NO_TRANSLATION"; return null }
        val width = live.edges.cols(); val height = live.edges.rows()
        val first = seeds.first()
        val selected = (listOf(first) + seeds.drop(1).filter {
            hypot(it.x - first.x, it.y - first.y) >= 10
        }.take(2)).toMutableList()
        fun refine(seed: VpsgFastSolver.Pose): VpsgFastSolver.Pose {
            val start = System.nanoTime()
            try { return VpsgFastSolver.refine(votes, prepared, seed, width, height) }
            finally { profile.refinement += System.nanoTime() - start; profile.refined++ }
        }
        val refined = selected.map(::refine)
        val verificationStarted = System.nanoTime()
        val reference = CvImages.loadGray(floor.file)
        val binary = Mat()
        try {
            Imgproc.threshold(reference, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)
            val distance = distanceMap(binary)
            try {
                val index = DistanceIndex(distance)
                fun verifyPose(pose: VpsgFastSolver.Pose) = verify(floor,
                    Pose(pose.scale, viewport.x + pose.x, viewport.y + pose.y, pose.score),
                    live, livePixels, contours, index, viewport)
                val fits = refined.map(::verifyPose).toMutableList()
                var best = fits.maxBy { it.score }
                fun rival() = fits.filter { hypot(it.pose.offsetX - best.pose.offsetX,
                    it.pose.offsetY - best.pose.offsetY) >= 10 }.maxByOrNull(Fit::support)
                // Desktop retains the scored pool. If refinement merges all competing peaks,
                // refine the remainder rather than inventing a uniqueness margin.
                var extraRefinement = 0L
                if (rival() == null && best.support >= .70) {
                    val before = profile.refinement
                    for (seed in seeds) if (seed !in selected) fits += verifyPose(refine(seed))
                    extraRefinement = profile.refinement - before
                    best = fits.maxBy { it.score }
                }
                val runnerUp = rival()
                val uniquePose = runnerUp != null && best.support - runnerUp.support >= .09
                val (coverage, tested) = referenceCoverage(binary, live, liveIndex, best.pose, viewport)
                val result = best.copy(referenceCoverage = coverage, referencePoints = tested, poseUnique = uniquePose)
                profile.verification = System.nanoTime() - verificationStarted - extraRefinement
                profile.outcome = if (result.qualified) "VERIFIED" else "STRUCTURE_UNRESOLVED"
                return result
            } finally { distance.release() }
        } finally { binary.release(); reference.release() }
    }
    private fun verify(floor: Floor, pose: Pose, live: VpsgLiveExtractor.Observation,
        livePixels: List<Pixel>, contours: List<List<Pixel>>, index: DistanceIndex,
        viewport: ScreenRect): Fit {
        val tx = pose.offsetX - viewport.x
        val ty = pose.offsetY - viewport.y
        fun distance(x: Int, y: Int) = index.at((x - tx) / pose.scale, (y - ty) / pose.scale,
            pose.scale)
        var hits = 0
        var sum = 0.0
        val width = live.edges.cols(); val height = live.edges.rows()
        val totals = IntArray(16)
        val cellHits = IntArray(16)
        for (pixel in livePixels) {
            val d = distance(pixel.x, pixel.y)
            val cell = min(3, pixel.x * 4 / width) +
                4 * min(3, pixel.y * 4 / height)
            totals[cell]++
            sum += d
            if (d <= 5.5) { hits++; cellHits[cell]++ }
        }
        val passingCells = (0..15).count { totals[it] >= 30 && cellHits[it] >= totals[it] * .70 }
        val spatialConflict = (0..15).any { totals[it] >= 30 && cellHits[it] < totals[it] * .70 }
        var longest = 0.0
        if (hits / livePixels.size.toDouble() >= .88 && !spatialConflict) {
            for (contour in contours) {
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
                    for (step in 0..steps) {
                        val x = (a.x + dx * step.toDouble() / steps).roundToInt()
                        val y = (a.y + dy * step.toDouble() / steps).roundToInt()
                        if (distance(x, y) > 5.5) run += if (step == 0) 0.0 else stride
                        else run = 0.0
                        longest = max(longest, run)
                        if (longest >= 30.0) break
                    }
                    if (longest >= 30.0) break
                }
                if (longest >= 30.0) break
            }
        }
        return Fit(floor, pose, hits / livePixels.size.toDouble(), sum / livePixels.size,
            passingCells, spatialConflict, longest, livePixels.size)
    }

    /** Reject a dense reference that happens to contain every observed line plus many absent ones. */
    private fun referenceCoverage(reference: Mat, live: VpsgLiveExtractor.Observation,
        liveDistance: DistanceIndex, pose: Pose, viewport: ScreenRect): Pair<Double, Int> {
        val total = Core.countNonZero(reference)
        if (total == 0) return 0.0 to 0
        val step = max(1, (total + 2047) / 2048)
        val referenceRow = ByteArray(reference.cols())
        val valid = ByteArray(live.valid.cols() * live.valid.rows())
        live.valid.get(0, 0, valid)
        val width = live.valid.cols()
        val height = live.valid.rows()
        val tx = pose.offsetX - viewport.x
        val ty = pose.offsetY - viewport.y
        var seen = 0
        var tested = 0
        var hits = 0
        for (y in 0 until reference.rows()) {
            reference.get(y, 0, referenceRow)
            for (x in referenceRow.indices) {
                if ((referenceRow[x].toInt() and 255) <= 128) continue
                if (seen++ % step != 0) continue
                val screenX = x * pose.scale + tx
                val screenY = y * pose.scale + ty
                val ix = screenX.roundToInt()
                val iy = screenY.roundToInt()
                if (ix !in 0 until width || iy !in 0 until height ||
                    (valid[iy * width + ix].toInt() and 255) <= 128) continue
                tested++
                if (liveDistance.at(screenX, screenY, 1.0) <= 5.5) hits++
            }
        }
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
