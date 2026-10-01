package com.idvb.android.recognize.vpsg

import kotlin.math.*
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentGate
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.measure
import com.idvb.android.alignment.AlignmentCancellation

/** Desktop VPSG3 S-B scale prior, T-3 bit-plane translation and centered local refinement.
 * No image-template correlation: each bit lane counts one translation of the sparse vote set.
 * Coordinates here are viewport-local: screen = reference * scale + offset.
 */
internal object VpsgFastSolver {
    data class Point(val x: Int, val y: Int)
    data class Pose(val scale: Double, val x: Double, val y: Double, val score: Double = 0.0)
    data class Peak(val pitch: Double, val ratio: Double)
    data class Index(val width: Int, val height: Int, val k3: LongArray, val k5: LongArray,
        val prior: Peak, val edgeCount: Int,
        val distance: FloatArray? = null, val edgePositions: IntArray? = null) {
        val wordsPerRow = (width + 63) / 64
        val bytes: Long get() = (k3.size + k5.size) * 8L + (distance?.size ?: 0) * 4L + (edgePositions?.size ?: 0) * 4L
        fun hit(words: LongArray, x: Int, y: Int): Boolean =
            x >= 0 && y >= 0 && x < width && y < height &&
                (words[y * wordsPerRow + (x ushr 6)] ushr (x and 63) and 1L) != 0L
    }

    /** Compute once per observation, then apply each reference's supported pitch domain. */
    class Correlation(signal: DoubleArray) {
        private val raw: DoubleArray
        private val median: Double
        init {
            val centered = signal.copyOf()
            val mean = if (signal.isEmpty()) 0.0 else signal.sum() / signal.size
            var variance = 0.0
            for (i in centered.indices) { centered[i] -= mean; variance += centered[i] * centered[i] }
            raw = DoubleArray(max(0, signal.size / 2 - 12))
            if (variance > 1e-9) for (i in raw.indices) {
                AlignmentCancellation.checkpoint("vpsg.autocorrelation.lag")
                val lag = i + 12
                var dot = 0.0
                for (x in 0 until centered.size - lag) dot += centered[x] * centered[x + lag]
                raw[i] = dot / variance
            }
            val sorted = DoubleArray(raw.size) { abs(raw[it]) }.also { it.sort() }
            median = if (sorted.isEmpty()) .01 else max(.01, sorted[sorted.size / 2])
        }

        fun logEvidence(log: AlignmentLogSink) {
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.scale.autocorrelation",
                measurements = mapOf("firstLag" to 12.0, "lagStep" to 1.0, "absoluteMedian" to median),
                thresholds = mapOf("minimumPeakExclusive" to .05, "harmonicStrength" to .80,
                    "harmonicIntegerTolerance" to .15), series = mapOf("correlationByLag" to raw.toList())))
        }

        fun peak(minPitch: Double = 12.0, maxPitch: Double = Double.POSITIVE_INFINITY,
            interpolate: Boolean = false): Peak {
            var best = -1
            var maxR = -1.0
            for (i in raw.indices) if (i + 12 >= minPitch && i + 12 <= maxPitch && raw[i] > maxR) {
                best = i; maxR = raw[i]
            }
            if (best < 0 || maxR <= .05) return Peak(0.0, 0.0)
            for (lag in max(13, ceil(minPitch).toInt()) until best + 11) {
                val i = lag - 12
                if (i <= 0 || i >= raw.size - 1 || lag > maxPitch) continue
                if (raw[i] > raw[i - 1] && raw[i] > raw[i + 1] && raw[i] >= .80 * maxR) {
                    val ratio = (best + 12.0) / lag
                    val k = round(ratio)
                    if (k >= 2 && abs(ratio - k) < .15) { best = i; maxR = raw[i]; break }
                }
            }
            var pitch = best + 12.0
            if (interpolate && best > 0 && best < raw.size - 1) {
                val a = raw[best - 1]; val b = raw[best]; val c = raw[best + 1]
                val denominator = 2 * (2 * b - a - c)
                if (b > a && b > c && denominator > 1e-9) {
                    val delta = (c - a) / denominator
                    if (abs(delta) <= .5) pitch += delta
                }
            }
            return Peak(pitch, maxR / median)
        }

        /** Retain distinct autocorrelation modes: a partially revealed map can make a
         * harmonic stronger than its fundamental. These are proposals, never acceptance. */
        fun alternatives(minPitch: Double, maxPitch: Double): List<Peak> =
            (1 until raw.size - 1).filter { i ->
                i + 12.0 in minPitch..maxPitch && raw[i] > .05 &&
                    raw[i] > raw[i - 1] && raw[i] > raw[i + 1] && raw[i] / median >= 2.0
            }.sortedByDescending { raw[it] }.take(4).map { i ->
                val denominator = 2 * (2 * raw[i] - raw[i - 1] - raw[i + 1])
                val delta = if (denominator > 1e-9) ((raw[i + 1] - raw[i - 1]) / denominator).coerceIn(-.5, .5) else 0.0
                Peak(i + 12.0 + delta, raw[i] / median)
            }
    }

    fun scale(live: Correlation, reference: Index, log: AlignmentLogSink = AlignmentLogSink.NONE,
        maximumScale: Double = VpsgAlignmentTuning.MAX_SCALE): Double? {
        val t = VpsgAlignmentTuning
        val referenceReady = reference.edgeCount >= t.MIN_REFERENCE_EDGES &&
            reference.prior.pitch > t.MIN_PITCH && reference.prior.ratio >= t.MIN_PITCH_RATIO
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.scale.reference", measurements = mapOf(
            "edgeCount" to reference.edgeCount.toDouble(), "pitch" to reference.prior.pitch,
            "ratio" to reference.prior.ratio), thresholds = t.thresholds + ("maximumScale" to maximumScale), gates = listOf(
                AlignmentGate("reference-edges", reference.edgeCount.toDouble(), ">=", t.MIN_REFERENCE_EDGES.toDouble(), reference.edgeCount >= t.MIN_REFERENCE_EDGES),
                AlignmentGate("reference-pitch", reference.prior.pitch, ">", t.MIN_PITCH, reference.prior.pitch > t.MIN_PITCH),
                AlignmentGate("reference-pitch-ratio", reference.prior.ratio, ">=", t.MIN_PITCH_RATIO, reference.prior.ratio >= t.MIN_PITCH_RATIO))))
        if (!referenceReady) return null
        live.logEvidence(log)
        val peak = log.measure("vpsg.scale.live-autocorrelation") {
            live.peak(reference.prior.pitch * t.MIN_SCALE, reference.prior.pitch * maximumScale, true)
        }
        val scale = peak.pitch / reference.prior.pitch
        val gates = listOf(
            AlignmentGate("live-pitch", peak.pitch, ">", t.MIN_PITCH, peak.pitch > t.MIN_PITCH),
            AlignmentGate("live-pitch-ratio", peak.ratio, ">=", t.MIN_PITCH_RATIO, peak.ratio >= t.MIN_PITCH_RATIO),
            AlignmentGate("minimum-scale", scale, ">=", t.MIN_SCALE, scale >= t.MIN_SCALE),
            AlignmentGate("maximum-scale", scale, "<=", maximumScale, scale <= maximumScale))
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.scale.result", measurements = mapOf(
            "livePitch" to peak.pitch, "liveRatio" to peak.ratio, "scale" to scale,
            "minimumSearchPitch" to reference.prior.pitch * t.MIN_SCALE,
            "maximumSearchPitch" to reference.prior.pitch * maximumScale), gates = gates))
        return scale.takeIf { gates.all(AlignmentGate::passed) }
    }

    /** Identical integer grid scores to scalar K3 membership; nine planes count up to 256 points. */
    fun scoreGrid(points: List<Point>, index: Index, minX: Int, maxX: Int,
        minY: Int, maxY: Int, stride: Int = 4): IntArray {
        require(points.size <= 256 && stride > 0)
        if (maxX < minX || maxY < minY) return IntArray(0)
        val columns = (maxX - minX) / stride + 1
        val rows = (maxY - minY) / stride + 1
        val scores = IntArray(Math.multiplyExact(columns, rows))
        val planes = LongArray(9)
        val words = index.k3
        val wordsPerRow = index.wordsPerRow
        for (dy in minY..maxY step stride) for (blockX in minX..maxX step 64) {
            AlignmentCancellation.checkpoint("vpsg.translation.score-grid.block")
            planes.fill(0)
            for (point in points) {
                val x = point.x + blockX; val y = point.y + dy
                if (y < 0 || y >= index.height || x >= index.width || x <= -64) continue
                var carry: Long
                if (x < 0) carry = words[y * wordsPerRow] shl -x
                else {
                    val wordX = x ushr 6; val shift = x and 63
                    carry = words[y * wordsPerRow + wordX] ushr shift
                    if (shift != 0 && wordX + 1 < wordsPerRow)
                        carry = carry or (words[y * wordsPerRow + wordX + 1] shl (64 - shift))
                }
                val remaining = index.width - x
                if (remaining < 64) carry = carry and ((1L shl remaining) - 1)
                var bit = 0
                while (carry != 0L) {
                    val next = planes[bit] and carry
                    planes[bit] = planes[bit] xor carry
                    carry = next; bit++
                }
            }
            val first = (stride - (blockX - minX) % stride) % stride
            for (lane in first..min(63, maxX - blockX) step stride) {
                var hits = 0
                for (bit in planes.indices) hits = hits or (((planes[bit] ushr lane) and 1L).toInt() shl bit)
                scores[(dy - minY) / stride * columns + (blockX + lane - minX) / stride] = hits
            }
        }
        return scores
    }

    private data class Vote(val x: Int, val y: Int, val hits: Int)
    fun translate(points: List<Point>, index: Index, scale: Double,
        log: AlignmentLogSink = AlignmentLogSink.NONE): List<Pose> {
        if (points.isEmpty()) return emptyList()
        val scaled = points.take(150).map { Point(round(it.x / scale).toInt(), round(it.y / scale).toInt()) }
        val xs = scaled.map { it.x }.sorted(); val ys = scaled.map { it.y }.sorted()
        val misses = (scaled.size - ceil(.5 * scaled.size).toInt()).coerceIn(0, scaled.size - 1)
        val minX = -xs[misses] - 2; val maxX = index.width + 1 - xs[scaled.size - 1 - misses]
        val minY = -ys[misses] - 2; val maxY = index.height + 1 - ys[scaled.size - 1 - misses]
        if (maxX < minX || maxY < minY) return emptyList()
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.translation.search", measurements = mapOf(
            "scale" to scale, "voteCount" to scaled.size.toDouble(), "minX" to minX.toDouble(),
            "maxX" to maxX.toDouble(), "minY" to minY.toDouble(), "maxY" to maxY.toDouble()),
            thresholds = mapOf("maximumVotes" to 150.0, "minimumConsensus" to .5, "gridStride" to 4.0,
                "poolCapacity" to 32.0, "minimumPoolHits" to 5.0, "polishRadius" to 2.0,
                "distinctRadius" to 2.0, "rivalDistance" to 10.0, "minimumRivalHits" to 3.0)))
        val scores = log.measure("vpsg.translation.score-grid") { scoreGrid(scaled, index, minX, maxX, minY, maxY) }
        val columns = (maxX - minX) / 4 + 1
        log.attachOwned("translation-scores.i32le") {
            java.nio.ByteBuffer.allocate(scores.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { buffer ->
                scores.forEach(buffer::putInt)
            }.array()
        }
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.translation.grid-layout", measurements = mapOf(
            "columns" to columns.toDouble(), "rows" to ((maxY - minY) / 4 + 1).toDouble(),
            "originX" to minX.toDouble(), "originY" to minY.toDouble(), "stride" to 4.0),
            labels = mapOf("artifact" to "translation-scores.i32le", "encoding" to "row-major signed int32 little-endian")))
        val pool = ArrayList<Vote>(32)
        for (i in scores.indices) {
            val hits = scores[i]
            if (hits < 5 || pool.size == 32 && hits <= pool.last().hits) continue
            val vote = Vote(minX + i % columns * 4, minY + i / columns * 4, hits)
            var at = pool.indexOfFirst { it.hits < hits }
            if (at < 0) at = pool.size
            pool.add(at, vote)
            if (pool.size > 32) pool.removeAt(32)
        }
        fun polish(vote: Vote): Vote {
            fun count(x: Int, y: Int) = scaled.count { index.hit(index.k3, it.x + x, it.y + y) }
            var best = vote.copy(hits = count(vote.x, vote.y))
            for (y in max(minY, vote.y - 2)..min(maxY, vote.y + 2))
                for (x in max(minX, vote.x - 2)..min(maxX, vote.x + 2)) {
                    val hits = count(x, y)
                    if (hits > best.hits) best = Vote(x, y, hits)
                }
            return best
        }
        val distinct = ArrayList<Vote>()
        for (v in pool.map(::polish).sortedByDescending { it.hits })
            if (distinct.none { abs(it.x - v.x) <= 2 && abs(it.y - v.y) <= 2 }) distinct += v
        if (distinct.isEmpty()) return emptyList()
        val best = distinct.first()
        // Keep a genuine distant rival even if all top-32 peaks occupy the same basin.
        if (distinct.none { hypot((it.x - best.x) * scale, (it.y - best.y) * scale) >= 10 }) {
            var rival: Vote? = null
            for (i in scores.indices) {
                val x = minX + i % columns * 4; val y = minY + i / columns * 4
                if (hypot((x - best.x) * scale, (y - best.y) * scale) < 10) continue
                if (scores[i] >= 3 && scores[i] > (rival?.hits ?: 0)) rival = Vote(x, y, scores[i])
            }
            rival?.let(::polish)?.let { v ->
                if (hypot((v.x - best.x) * scale, (v.y - best.y) * scale) >= 10) distinct += v
            }
        }
        val result = distinct.map { Pose(scale, -it.x * scale, -it.y * scale, it.hits.toDouble()) }
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.translation.candidates", measurements = mapOf(
            "evaluatedGridPositions" to scores.size.toDouble(), "poolSize" to pool.size.toDouble(),
            "candidateCount" to result.size.toDouble()), series = mapOf(
                "scale" to result.map { it.scale }, "offsetX" to result.map { it.x },
                "offsetY" to result.map { it.y }, "hits" to result.map { it.score })))
        return result
    }

    fun score(points: List<Point>, index: Index, pose: Pose, incumbent: Double = -1.0): Double {
        var h3 = 0; var h5 = 0
        val inv = 1 / pose.scale
        for (i in points.indices) {
            val q = points[i]
            val x = round((q.x - pose.x) * inv).toInt(); val y = round((q.y - pose.y) * inv).toInt()
            if (index.hit(index.k5, x, y)) { h5++; if (index.hit(index.k3, x, y)) h3++ }
            if (i and 15 == 15 && (h5 + 2.0 * h3 + 3.0 * (points.size - i - 1)) /
                (3.0 * points.size) <= incumbent) return -1.0
        }
        return (h5 + 2.0 * h3) / (3.0 * max(1, points.size))
    }

    fun refine(points: List<Point>, index: Index, seed: Pose, width: Int, height: Int,
        log: AlignmentLogSink = AlignmentLogSink.NONE, maximumScale: Double = 2.5): Pose {
        val cx = width / 2.0; val cy = height / 2.0
        var best = seed.copy(score = score(points, index, seed))
        // Keep the complete trace in primitive storage; boxing every probe on the
        // interaction thread creates thousands of short-lived lists per compact frame.
        val probes = if (log.enabled) DoubleArray((5 * 7 * 7 + 6 + 3 * 3 * 3) * 4) else null
        var probeValues = 0
        var evaluated = 0
        fun remember(s: Double, x: Double, y: Double, value: Double) {
            evaluated++
            if (probes != null) {
                probes[probeValues++] = s; probes[probeValues++] = x
                probes[probeValues++] = y; probes[probeValues++] = value
            }
            if (value > best.score) best = Pose(s, x, y, value)
        }
        fun probe(center: Pose, delta: Double, dx: Double, dy: Double) {
            AlignmentCancellation.checkpoint("vpsg.refine.discrete.probe")
            val s = center.scale + delta
            if (s !in .35..maximumScale) return
            val pose = Pose(s, cx - (cx - center.x) / center.scale * s + dx,
                cy - (cy - center.y) / center.scale * s + dy)
            val value = score(points, index, pose, best.score)
            remember(s, pose.x, pose.y, value)
        }
        // The 7 x 7 Cartesian translation grid has only seven distinct X and Y
        // coordinate vectors. Reuse the exact rounded coordinates, keeping every
        // probe, incumbent pruning, ordering and tie decision identical to scalar scoring.
        for (ds in doubleArrayOf(-.020, -.015, 0.0, .015, .020)) {
            AlignmentCancellation.checkpoint("vpsg.refine.discrete.scale")
            val s = seed.scale + ds
            if (s !in .35..maximumScale) continue
            val inv = 1 / s
            val bx = cx - (cx - seed.x) / seed.scale * s
            val by = cy - (cy - seed.y) / seed.scale * s
            val offsetsX = DoubleArray(7) { bx + (-6 + it * 2).toDouble() }
            val offsetsY = DoubleArray(7) { by + (-6 + it * 2).toDouble() }
            val xs = Array(7) { axis -> IntArray(points.size) { round((points[it].x - offsetsX[axis]) * inv).toInt() } }
            val ys = Array(7) { axis -> IntArray(points.size) { round((points[it].y - offsetsY[axis]) * inv).toInt() } }
            for (xi in 0..6) for (yi in 0..6) {
                AlignmentCancellation.checkpoint("vpsg.refine.discrete.probe")
                val value = scoreCoordinates(xs[xi], ys[yi], index, best.score)
                remember(s, offsetsX[xi], offsetsY[yi], value)
            }
        }
        val coarse = best
        for (ds in doubleArrayOf(-.020, -.010, -.005, .005, .010, .020)) probe(coarse, ds, 0.0, 0.0)
        val fine = best
        for (ds in doubleArrayOf(-.005, 0.0, .005)) for (dx in doubleArrayOf(-1.5, 0.0, 1.5))
            for (dy in doubleArrayOf(-1.5, 0.0, 1.5)) probe(fine, ds, dx, dy)
        if (log.enabled) log.emit(AlignmentLogEvent("vpsg.refine.discrete", measurements = mapOf(
            "seedScale" to seed.scale, "seedX" to seed.x, "seedY" to seed.y,
            "scale" to best.scale, "offsetX" to best.x, "offsetY" to best.y,
            "score" to best.score, "probes" to evaluated.toDouble()),
            thresholds = mapOf("minimumScale" to .35, "maximumScale" to maximumScale,
                "coarseTranslationRadius" to 6.0, "coarseTranslationStep" to 2.0,
                "fineTranslationStep" to 1.5),
            series = mapOf("coarseScaleDeltas" to listOf(-.020, -.015, 0.0, .015, .020),
                "axisScaleDeltas" to listOf(-.020, -.010, -.005, .005, .010, .020),
                "fineScaleDeltas" to listOf(-.005, 0.0, .005), "probesScaleXYScore" to probes!!.asList().subList(0, probeValues)),
            labels = mapOf("scoreNegativeOne" to "pruned-by-incumbent-upper-bound")))
        return best
    }

    private fun scoreCoordinates(xs: IntArray, ys: IntArray, index: Index, incumbent: Double): Double {
        var h3 = 0; var h5 = 0
        val width = index.width; val height = index.height; val stride = index.wordsPerRow
        val k3 = index.k3; val k5 = index.k5
        for (i in xs.indices) {
            val x = xs[i]; val y = ys[i]
            if (x >= 0 && y >= 0 && x < width && y < height) {
                val at = y * stride + (x ushr 6); val mask = 1L shl (x and 63)
                if (k5[at] and mask != 0L) { h5++; if (k3[at] and mask != 0L) h3++ }
            }
            if (i and 15 == 15 && (h5 + 2.0 * h3 + 3.0 * (xs.size - i - 1)) /
                (3.0 * xs.size) <= incumbent) return -1.0
        }
        return (h5 + 2.0 * h3) / (3.0 * max(1, xs.size))
    }
}
