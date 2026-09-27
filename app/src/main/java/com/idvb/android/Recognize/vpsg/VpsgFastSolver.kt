package com.idvb.android.recognize.vpsg

import kotlin.math.*

/** Desktop VPSG3 S-B scale prior, T-3 bit-plane translation and centered local refinement.
 * No image-template correlation: each bit lane counts one translation of the sparse vote set.
 * Coordinates here are viewport-local: screen = reference * scale + offset.
 */
internal object VpsgFastSolver {
    data class Point(val x: Int, val y: Int)
    data class Pose(val scale: Double, val x: Double, val y: Double, val score: Double = 0.0)
    data class Peak(val pitch: Double, val ratio: Double)
    data class Index(val width: Int, val height: Int, val k3: LongArray, val k5: LongArray,
        val prior: Peak, val edgeCount: Int) {
        val wordsPerRow = (width + 63) / 64
        val bytes: Long get() = (k3.size + k5.size) * 8L
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
                val lag = i + 12
                var dot = 0.0
                for (x in 0 until centered.size - lag) dot += centered[x] * centered[x + lag]
                raw[i] = dot / variance
            }
            val sorted = DoubleArray(raw.size) { abs(raw[it]) }.also { it.sort() }
            median = if (sorted.isEmpty()) .01 else max(.01, sorted[sorted.size / 2])
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
    }

    fun scale(live: Correlation, reference: Index): Double? {
        if (reference.edgeCount < 300 || reference.prior.pitch <= 5 || reference.prior.ratio < 2) return null
        val peak = live.peak(reference.prior.pitch * .4, reference.prior.pitch * 2.4, true)
        if (peak.pitch <= 5 || min(peak.ratio, reference.prior.ratio) < 2) return null
        return (peak.pitch / reference.prior.pitch).takeIf { it in .4..2.4 }
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
    fun translate(points: List<Point>, index: Index, scale: Double): List<Pose> {
        if (points.isEmpty()) return emptyList()
        val scaled = points.take(150).map { Point(round(it.x / scale).toInt(), round(it.y / scale).toInt()) }
        val xs = scaled.map { it.x }.sorted(); val ys = scaled.map { it.y }.sorted()
        val misses = (scaled.size - ceil(.5 * scaled.size).toInt()).coerceIn(0, scaled.size - 1)
        val minX = -xs[misses] - 2; val maxX = index.width + 1 - xs[scaled.size - 1 - misses]
        val minY = -ys[misses] - 2; val maxY = index.height + 1 - ys[scaled.size - 1 - misses]
        if (maxX < minX || maxY < minY) return emptyList()
        val scores = scoreGrid(scaled, index, minX, maxX, minY, maxY)
        val columns = (maxX - minX) / 4 + 1
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
        return distinct.map { Pose(scale, -it.x * scale, -it.y * scale, it.hits.toDouble()) }
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

    fun refine(points: List<Point>, index: Index, seed: Pose, width: Int, height: Int): Pose {
        val cx = width / 2.0; val cy = height / 2.0
        var best = seed.copy(score = score(points, index, seed))
        fun probe(center: Pose, delta: Double, dx: Double, dy: Double) {
            val s = center.scale + delta
            if (s !in .35..2.5) return
            val pose = Pose(s, cx - (cx - center.x) / center.scale * s + dx,
                cy - (cy - center.y) / center.scale * s + dy)
            val value = score(points, index, pose, best.score)
            if (value > best.score) best = pose.copy(score = value)
        }
        for (ds in doubleArrayOf(-.020, -.015, 0.0, .015, .020))
            for (dx in -6..6 step 2) for (dy in -6..6 step 2) probe(seed, ds, dx.toDouble(), dy.toDouble())
        val coarse = best
        for (ds in doubleArrayOf(-.020, -.010, -.005, .005, .010, .020)) probe(coarse, ds, 0.0, 0.0)
        val fine = best
        for (ds in doubleArrayOf(-.005, 0.0, .005)) for (dx in doubleArrayOf(-1.5, 0.0, 1.5))
            for (dy in doubleArrayOf(-1.5, 0.0, 1.5)) probe(fine, ds, dx, dy)
        return best
    }
}
