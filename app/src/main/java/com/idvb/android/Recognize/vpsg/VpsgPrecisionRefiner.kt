package com.idvb.android.recognize.vpsg

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.AlignmentCancellation

/** Desktop Vpsg3PrecisionRefiner's centered, quadrant-balanced distance refinement.
 * A bounded failure returns null, leaving the already verified discrete pose intact.
 */
internal object VpsgPrecisionRefiner {
    fun interface DistanceSampler { fun at(x: Double, y: Double, scale: Double): Double }
    fun refine(points: List<VpsgFastSolver.Point>, seed: VpsgFastSolver.Pose,
        width: Int, height: Int, distance: DistanceSampler,
        log: AlignmentLogSink = AlignmentLogSink.NONE, nativeDistance: java.nio.ByteBuffer? = null,
        referenceWidth: Int = 0, referenceHeight: Int = 0): VpsgFastSolver.Pose? {
        val started = System.nanoTime()
        val deadline = started + 40_000_000L
        var scale = seed.scale; var dx = 0.0; var dy = 0.0
        var best = Double.NaN; var initialLoss = Double.NaN
        var radius = 0.0; var rounds = 0
        var cx = Double.NaN; var cy = Double.NaN
        var left = 0; var top = 0; var right = 0; var bottom = 0
        var ds = seed.scale * .005; var dt = 1.0
        // Bound the correction in reference pixels so zoom/resolution cannot
        // truncate the same source-space displacement. Acceptance is rechecked
        // separately against all original structural/uniqueness gates.
        val maximumTranslation = 6.0 * max(1.0, seed.scale)
        val maximumRounds = 24
        val counts = IntArray(4)
        val probes = DoubleArray(if (log.enabled) (maximumRounds * 26 + 2 * 25) * 4 else 0)
        var probeValues = 0
        fun remember(s: Double, x: Double, y: Double, value: Double) {
            if (log.enabled) { probes[probeValues++] = s; probes[probeValues++] = x; probes[probeValues++] = y; probes[probeValues++] = value }
        }
        fun finish(reason: String, result: VpsgFastSolver.Pose? = null): VpsgFastSolver.Pose? {
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.refine.precision.result", reason,
                measurements = mapOf("seedScale" to seed.scale, "seedX" to seed.x, "seedY" to seed.y,
                    "scale" to scale, "centerDx" to dx, "centerDy" to dy, "initialLoss" to initialLoss,
                    "bestLoss" to best, "radius" to radius, "rounds" to rounds.toDouble(),
                    "scaleStep" to ds, "translationStep" to dt, "points" to points.size.toDouble(),
                    "pivotX" to cx, "pivotY" to cy, "observedLeft" to left.toDouble(),
                    "observedTop" to top.toDouble(), "observedRight" to right.toDouble(),
                    "observedBottom" to bottom.toDouble(), "viewportWidth" to width.toDouble(),
                    "viewportHeight" to height.toDouble()),
                thresholds = mapOf("budgetMs" to 40.0, "maximumRounds" to maximumRounds.toDouble(), "minimumPoints" to 35.0,
                    "minimumPartitionPoints" to 15.0, "minimumPartitions" to 2.0, "minimumRadius" to 80.0,
                    "distanceCap" to 6.0, "huberKnee" to 1.5, "maximumInitialLossExclusive" to 5.0,
                    "initialRelativeScaleStep" to .005, "initialTranslationStep" to 1.0,
                    "maximumRelativeScaleChange" to .03, "maximumTranslationChange" to maximumTranslation,
                    "maximumReferenceTranslationChange" to 6.0,
                    "translationConvergence" to .125, "farScaleConvergencePixels" to .25,
                    "improvementEpsilon" to 1e-9, "observabilityMargin" to 1e-4),
                series = mapOf("partitionCounts" to counts.map(Int::toDouble), "probesScaleDxDyLoss" to probes.asList().subList(0, probeValues)),
                labels = mapOf("calibrated" to (result != null).toString(),
                    "pivotDomain" to "observed-point-bounds; unseen-viewport-regions-do-not-center-scale-or-partitions",
                    "translationBoundUnits" to "six-reference-pixels-scaled-to-live-pixels; minimum-six-live-pixels"),
                durationNanos = System.nanoTime() - started))
            return result
        }
        if (!seed.scale.isFinite() || seed.scale <= 0 || points.size < 35) return finish("invalid-seed-or-insufficient-points")
        // Cropping or a resolution change moves the viewport center through
        // unobserved regions. Center scale and balance residuals on actual
        // evidence, keeping the same bounded scale/translation search.
        left = points.minOf { it.x }; right = points.maxOf { it.x }
        top = points.minOf { it.y }; bottom = points.maxOf { it.y }
        cx = (left + right) / 2.0; cy = (top + bottom) / 2.0
        val rcx = (cx - seed.x) / seed.scale; val rcy = (cy - seed.y) / seed.scale
        val partitions = points.map { (if (it.x < cx) 0 else 1) + (if (it.y < cy) 0 else 2) }
        partitions.forEach { counts[it]++ }
        radius = points.maxOf { hypot(it.x - cx, it.y - cy) }
        if (counts.count { it >= 15 } < 2 || radius < 80 ||
            min(counts[0] + counts[2], counts[1] + counts[3]) == 0 ||
            min(counts[0] + counts[1], counts[2] + counts[3]) == 0) return finish("insufficient-spatial-support")
        fun loss(scale: Double, dx: Double, dy: Double): Double {
            AlignmentCancellation.checkpoint("vpsg.refine.precision.loss")
            val sums = DoubleArray(4)
            points.forEachIndexed { i, point ->
                // The distance sampler penalizes out-of-reference points; support is never dropped.
                val d = min(6.0, distance.at(rcx + (point.x - cx - dx) / scale,
                    rcy + (point.y - cy - dy) / scale, scale))
                sums[partitions[i]] += if (d <= 1.5) .5 * d * d else 1.5 * (d - .75)
            }
            return (0..3).filter { counts[it] >= 15 }.map { sums[it] / counts[it] }.average()
        }
        val coordinates = IntArray(points.size * 2) { if (it and 1 == 0) points[it / 2].x else points[it / 2].y }
        fun losses(poses: List<DoubleArray>): DoubleArray = if (nativeDistance != null && VpsgNativeKernel.available)
            VpsgNativeKernel.precisionLosses(coordinates, DoubleArray(poses.size * 3) { poses[it / 3][it % 3] },
                nativeDistance, referenceWidth, referenceHeight, cx, cy, rcx, rcy)
            else DoubleArray(poses.size) { loss(poses[it][0], poses[it][1], poses[it][2]) }
        best = losses(listOf(doubleArrayOf(scale, dx, dy)))[0]
        initialLoss = best
        if (!best.isFinite() || best >= 5) return finish("out-of-reference")
        var converged = false
        for (round in 0 until maximumRounds) {
            rounds = round
            val roundStarted = System.nanoTime()
            if (roundStarted >= deadline) return finish("budget")
            if (dt <= .125 && ds / seed.scale * radius <= .25) { converged = true; break }
            var nextS = scale; var nextX = dx; var nextY = dy
            val poses = mutableListOf<DoubleArray>()
            for (si in -1..1) for (xi in -1..1) for (yi in -1..1) {
                if (System.nanoTime() >= deadline) return finish("budget")
                if (si == 0 && xi == 0 && yi == 0) continue
                val s = scale + si * ds; val x = dx + xi * dt; val y = dy + yi * dt
                if (abs(s / seed.scale - 1) > .030000001 || max(abs(x), abs(y)) > maximumTranslation + 1e-7) continue
                poses += doubleArrayOf(s, x, y)
            }
            val values = losses(poses)
            for ((i, pose) in poses.withIndex()) {
                if (System.nanoTime() >= deadline) return finish("budget")
                val (s, x, y) = pose
                val value = values[i]
                remember(s, x, y, value)
                if (value < best - 1e-9) { best = value; nextS = s; nextX = x; nextY = y }
            }
            if (nextS == scale && nextX == dx && nextY == dy) { ds /= 2; dt /= 2 }
            scale = nextS; dx = nextX; dy = nextY
            if (log.enabled) log.emit(AlignmentLogEvent("vpsg.refine.precision.round", measurements = mapOf(
                "round" to round.toDouble(), "scale" to scale, "centerDx" to dx, "centerDy" to dy,
                "bestLoss" to best, "scaleStep" to ds, "translationStep" to dt),
                durationNanos = System.nanoTime() - roundStarted))
        }
        if (!converged && dt <= .125 && ds / seed.scale * radius <= .25) converged = true
        if (!converged) return finish("iterations")
        if (abs(scale / seed.scale - 1) >= .029999 || max(abs(dx), abs(dy)) >= maximumTranslation - 1e-6) return finish("boundary")
        // Reject scale ambiguity after allowing translation to compensate for a one-pixel scale change.
        for (sign in listOf(-1, 1)) {
            var competitor = Double.POSITIVE_INFINITY
            val poses = mutableListOf<DoubleArray>()
            for (xi in -2..2) for (yi in -2..2) {
                if (System.nanoTime() >= deadline) return finish("budget")
                val s = scale + sign * scale / radius; val x = dx + xi * .5; val y = dy + yi * .5
                poses += doubleArrayOf(s, x, y)
            }
            val values = losses(poses)
            for ((i, pose) in poses.withIndex()) {
                if (System.nanoTime() >= deadline) return finish("budget")
                val (s, x, y) = pose
                val value = values[i]
                remember(s, x, y, value)
                competitor = min(competitor, value)
            }
            log.emit(AlignmentLogEvent("vpsg.refine.precision.observability", measurements = mapOf(
                "sign" to sign.toDouble(), "competitorLoss" to competitor, "bestLoss" to best,
                "scalePerturbation" to scale / radius), thresholds = mapOf("minimumLossMargin" to 1e-4),
                labels = mapOf("passed" to (competitor > best + 1e-4).toString())))
            if (competitor <= best + 1e-4) return finish("scale-unobservable")
        }
        return finish("converged", VpsgFastSolver.Pose(scale, cx - rcx * scale + dx, cy - rcy * scale + dy))
    }
}
