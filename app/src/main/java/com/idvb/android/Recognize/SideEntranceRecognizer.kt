package com.idvb.android.recognize

import android.graphics.Bitmap
import com.idvb.android.data.MapRepository
import com.idvb.android.graphics.decodeMapRegion
import com.idvb.android.idvm.MapRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 桌面版 SideEntranceScanPipeline 的移动端实现：侧门局部模板检索、局部结构复核、
 * 可靠/待验证分组，以及把未入选地图按目录顺序追加到末尾。
 */
class SideEntranceRecognizer(
    private val repository: MapRepository,
    private val classId: String? = null,
) : RecognitionRoute {
    companion object {
        const val FEATURE_RATIO = .12
        const val MIN_REFERENCE_SIMILARITY = .55
        const val MIN_VERIFICATION_SIMILARITY = .68
        const val MIN_TEMPLATE_MARGIN = .035
        const val STRICT_CHAMFER_LIMIT = 3.0
    }

    override fun recognize(frame: Bitmap): RecognitionResult {
        val smallFrame = GrayImage.fromBitmap(frame, maxDimension = 240)
        val catalog = repository.loadCatalog()
        val modeMaps = catalog.maps.filter { classId == null || it.classId == classId }
        val matches = modeMaps.mapNotNull { matchMap(smallFrame, it) }
            .sortedByDescending { it.templateScore }
            .toMutableList()

        matches.forEachIndexed { index, candidate ->
            val previous = if (index > 0) matches[index - 1].templateScore - candidate.templateScore else Double.POSITIVE_INFINITY
            val next = if (index + 1 < matches.size) candidate.templateScore - matches[index + 1].templateScore else Double.POSITIVE_INFINITY
            matches[index] = candidate.copy(templateMargin = if (matches.size == 1) candidate.templateScore else min(previous, next))
        }

        val eligible = matches.mapNotNull { candidate ->
            if (candidate.templateScore < MIN_REFERENCE_SIMILARITY) return@mapNotNull null
            val reliable = candidate.templateScore >= MIN_VERIFICATION_SIMILARITY &&
                candidate.templateMargin >= MIN_TEMPLATE_MARGIN &&
                candidate.chamferPixels <= STRICT_CHAMFER_LIMIT && candidate.edgeCoverage >= .42
            if (reliable) candidate.copy(
                disposition = CandidateDisposition.RELIABLE,
                evidenceLabel = "结构已验证 · Chamfer ${"%.2f".format(candidate.chamferPixels)}px · 边缘覆盖 ${(candidate.edgeCoverage * 100).toInt()}% · 模板相似度 ${(candidate.templateScore * 100).toInt()}%",
            ) else candidate.copy(
                disposition = CandidateDisposition.NEEDS_VERIFICATION,
                evidenceLabel = "仅供参考（未通过结构验证） · 模板相似度 ${(candidate.templateScore * 100).toInt()}%",
            )
        }

        val reliable = eligible.filter { it.disposition == CandidateDisposition.RELIABLE }
            .sortedWith(compareBy<RecognitionCandidate> { it.chamferPixels }
                .thenByDescending { it.edgeCoverage }
                .thenByDescending { it.occupancyCoverage }
                .thenByDescending { it.templateMargin }
                .thenByDescending { it.templateScore })
        val references = eligible.filter { it.disposition == CandidateDisposition.NEEDS_VERIFICATION }
            .sortedByDescending { it.templateScore }.take(5)
        val included = (reliable + references).mapTo(mutableSetOf()) { it.map.id }
        val catalogOnly = modeMaps.filterNot { it.id in included }.map { map ->
            RecognitionCandidate(
                map = map,
                floorKey = map.floors.minByOrNull { it.sortOrder }?.key.orEmpty(),
                disposition = CandidateDisposition.CATALOG_ONLY,
                evidenceLabel = "未进入本次识别候选",
            )
        }
        return RecognitionResult(frame, reliable + references + catalogOnly)
    }

    private fun matchMap(frame: GrayImage, map: MapRecord): RecognitionCandidate? {
        val floor = map.floors.minByOrNull { it.sortOrder } ?: return null
        val gate = repository.loadSideDoors(map.id, floor.key).firstOrNull() ?: return null
        val sourceFile = repository.floorImageFile(map.id, floor.imagePath)
        val region = repository.loadPreviewRegion(map.id, floor)
        val bitmap = decodeMapRegion(sourceFile, region, 900) ?: return null
        val mapGray = GrayImage.fromBitmap(bitmap, maxDimension = 900)
        bitmap.recycle()
        val featureW = max(12, (mapGray.width * FEATURE_RATIO).toInt())
        val featureH = max(12, (mapGray.height * FEATURE_RATIO).toInt())
        val cx = ((gate.x + gate.width / 2.0) * mapGray.width).toInt()
        val cy = ((gate.y + gate.height / 2.0) * mapGray.height).toInt()
        val template = mapGray.cropCentered(cx, cy, featureW, featureH)
        template.maskNormalizedGate(gate.x, gate.y, gate.width, gate.height, cx - featureW / 2, cy - featureH / 2, mapGray.width, mapGray.height)

        val expectedW = max(8, (frame.width * FEATURE_RATIO).toInt())
        val expectedH = max(8, (frame.height * FEATURE_RATIO).toInt())
        var best: Match? = null
        for (scale in doubleArrayOf(.70, .85, 1.0, 1.15, 1.30)) {
            val tw = max(8, (expectedW * scale).toInt())
            val th = max(8, (expectedH * scale).toInt())
            if (tw >= frame.width || th >= frame.height) continue
            val resized = template.resize(tw, th)
            val found = findBestNcc(frame, resized)
            if (best == null || found.score > best.score) best = found
        }
        val hit = best ?: return null
        val structure = structureMetrics(frame, hit.template, hit.x, hit.y)
        return RecognitionCandidate(
            map = map, floorKey = floor.key, disposition = CandidateDisposition.NEEDS_VERIFICATION,
            templateScore = hit.score.coerceIn(-1.0, 1.0),
            chamferPixels = structure.first, edgeCoverage = structure.second,
            occupancyCoverage = structure.third, evidenceLabel = "",
        )
    }

    private data class Match(val x: Int, val y: Int, val score: Double, val template: GrayImage)

    private fun findBestNcc(frame: GrayImage, template: GrayImage): Match {
        var best = -1.0
        var bestX = 0; var bestY = 0
        val positionStep = max(2, min(template.width, template.height) / 5)
        val pixelStep = max(1, min(template.width, template.height) / 8)
        val coords = buildList {
            for (y in 0 until template.height step pixelStep)
                for (x in 0 until template.width step pixelStep) add(x to y)
        }
        val templateMean = coords.sumOf { template[it.first, it.second].toDouble() } / coords.size
        var templateVar = 0.0
        coords.forEach { templateVar += (template[it.first, it.second] - templateMean) * (template[it.first, it.second] - templateMean) }
        for (y in 0..frame.height - template.height step positionStep) {
            for (x in 0..frame.width - template.width step positionStep) {
                val mean = coords.sumOf { frame[x + it.first, y + it.second].toDouble() } / coords.size
                var covariance = 0.0; var frameVar = 0.0
                coords.forEach {
                    val a = template[it.first, it.second] - templateMean
                    val b = frame[x + it.first, y + it.second] - mean
                    covariance += a * b; frameVar += b * b
                }
                val score = covariance / sqrt(max(1e-9, templateVar * frameVar))
                if (score > best) { best = score; bestX = x; bestY = y }
            }
        }
        return Match(bestX, bestY, best, template)
    }

    private fun structureMetrics(frame: GrayImage, template: GrayImage, ox: Int, oy: Int): Triple<Double, Double, Double> {
        val te = template.edges()
        val patch = frame.crop(ox, oy, template.width, template.height)
        val pe = patch.edges()
        var count = 0; var distanceSum = 0.0; var covered = 0
        for (y in 1 until template.height - 1) for (x in 1 until template.width - 1) {
            if (!te[x, y]) continue
            count++
            var nearest = 6.0
            for (dy in -5..5) for (dx in -5..5) {
                val px = x + dx; val py = y + dy
                if (px in 0 until pe.width && py in 0 until pe.height && pe[px, py]) nearest = min(nearest, sqrt((dx * dx + dy * dy).toDouble()))
            }
            distanceSum += nearest
            if (nearest <= 2.0) covered++
        }
        var occupancyMatches = 0
        for (i in template.pixels.indices) if ((template.pixels[i] < 128) == (patch.pixels[i] < 128)) occupancyMatches++
        return Triple(if (count == 0) 6.0 else distanceSum / count, if (count == 0) 0.0 else covered.toDouble() / count, occupancyMatches.toDouble() / template.pixels.size)
    }
}

private class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
    fun crop(x: Int, y: Int, w: Int, h: Int) = GrayImage(w, h, IntArray(w * h) { i -> this[x + i % w, y + i / w] })
    fun cropCentered(cx: Int, cy: Int, w: Int, h: Int): GrayImage {
        val result = IntArray(w * h)
        val fill = pixels.average().toInt()
        val left = cx - w / 2; val top = cy - h / 2
        for (y in 0 until h) for (x in 0 until w) {
            val sx = left + x; val sy = top + y
            result[y * w + x] = if (sx in 0 until width && sy in 0 until height) this[sx, sy] else fill
        }
        return GrayImage(w, h, result)
    }
    fun resize(w: Int, h: Int) = GrayImage(w, h, IntArray(w * h) { i ->
        this[(i % w) * width / w, (i / w) * height / h]
    })
    fun maskNormalizedGate(gx: Double, gy: Double, gw: Double, gh: Double, featureLeft: Int, featureTop: Int, mapW: Int, mapH: Int) {
        val mean = pixels.average().toInt()
        val left = (gx * mapW).toInt() - featureLeft; val top = (gy * mapH).toInt() - featureTop
        val right = ((gx + gw) * mapW).toInt() - featureLeft; val bottom = ((gy + gh) * mapH).toInt() - featureTop
        for (y in max(0, top) until min(height, bottom)) for (x in max(0, left) until min(width, right)) pixels[y * width + x] = mean
    }
    fun edges(): EdgeImage {
        val out = BooleanArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val gx = abs(this[x + 1, y] - this[x - 1, y]); val gy = abs(this[x, y + 1] - this[x, y - 1])
            out[y * width + x] = gx + gy > 70
        }
        return EdgeImage(width, height, out)
    }
    companion object {
        fun fromBitmap(source: Bitmap, maxDimension: Int): GrayImage {
            val scale = min(1.0, maxDimension.toDouble() / max(source.width, source.height))
            val w = max(1, (source.width * scale).toInt()); val h = max(1, (source.height * scale).toInt())
            val bitmap = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
            val argb = IntArray(w * h); bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            if (bitmap !== source) bitmap.recycle()
            return GrayImage(w, h, IntArray(argb.size) { i ->
                val c = argb[i]; (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
            })
        }
    }
}

private class EdgeImage(val width: Int, val height: Int, private val pixels: BooleanArray) {
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
}
