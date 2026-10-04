package com.idvb.android.recognize.side

import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.vpsg.VpsgAlignmentTuning
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.FutureTask
import kotlin.math.*

/** Gate-anchored retrieval and full-resolution identity evidence, ported from desktop
 * SideEntranceScanPipeline.Part2/Part3 and ScanIdentityEvidence. Scores only propose poses. */
internal object SparseGateSearch {
    data class Pixel(val x: Int, val y: Int)
    data class Pose(val scale: Double, val x: Double, val y: Double, val score: Double = 0.0, val gate: Int = 0)
    data class Evidence(val support: Double, val mean: Double, val longest: Double,
        val spatialConflict: Boolean, val total: Int, val cells: Int,
        val conflictDetail: String = "", val reverseSupport: Double = 0.0,
        val reversePoints: Int = 0, val reverseEligiblePoints: Int = 0,
        val reverseRequired: Boolean = false) {
        val rejectionCode get() = when {
            total < 80 -> "insufficient-live-points"
            support < .88 -> "visible-support"
            spatialConflict -> "spatial-support-conflict"
            longest >= 30 -> "longest-conflict"
            reverseRequired && reversePoints < VpsgAlignmentTuning.MIN_TESTED_POINTS -> "insufficient-visible-reference-points"
            reverseRequired && reverseSupport < VpsgAlignmentTuning.MIN_REVERSE_SUPPORT -> "visible-reference-conflict"
            else -> ""
        }
        val supported get() = rejectionCode.isEmpty()
        val bidirectionalSupport get() = if (reverseRequired) min(support,reverseSupport) else support
        val cost get() = mean + (1 - bidirectionalSupport) * 5
    }
    class Index(val width: Int, val height: Int, private val distances: ByteArray,
        private val excluded: ByteArray? = null, val edgePositions: IntArray = intArrayOf()) {
        companion object {
            private val decoded = DoubleArray(256) { value ->
                if (value <= 128) value/16.0 else (value-112)/2.0
            }
        }
        val bytes get() = distances.size + (excluded?.size ?: 0) + edgePositions.size*4
        fun copyEdgesTo(target: Mat) {
            target.create(height,width,CvType.CV_8UC1)
            val bytes=ByteArray(width*height)
            for (position in edgePositions) bytes[position]=255.toByte()
            target.put(0,0,bytes)
        }
        fun observable(x: Double, y: Double): Boolean {
            if (x < 0 || y < 0 || x >= width || y >= height) return true
            return excluded?.get(y.toInt()*width+x.toInt())?.toInt() == 0 || excluded == null
        }
        private fun decode(at: Int): Double {
            val value = distances[at].toInt() and 255
            return decoded[value]
        }
        fun distance(x: Double, y: Double, scale: Double): Double {
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width - 1 || y >= height - 1) return 50.0
            val ix = x.toInt(); val iy = y.toInt(); val fx = x - ix; val fy = y - iy
            val at = iy * width + ix
            return ((decode(at) * (1-fx) + decode(at+1)*fx)*(1-fy) +
                (decode(at+width)*(1-fx) + decode(at+width+1)*fx)*fy)*scale
        }
        fun score(points: List<Pixel>, scale: Double, x: Double, y: Double,
            mustBeat: Double = Double.NEGATIVE_INFINITY): Double {
            var sum = 0.0
            val inverseScale = 1.0/scale
            val minimumTotal = mustBeat * points.size - 1e-9
            for (i in points.indices) {
                val p = points[i]
                val d = distance((p.x-x)*inverseScale, (p.y-y)*inverseScale, scale)
                sum += if (d <= .8) 1.0 else if (d <= 2.5) .7 else if (d <= 5.5) .4 else 0.0
                // Exact upper bound, not a weaker acceptance threshold. Even if
                // every remaining point scores 1, this pose cannot replace best.
                if ((i and 15) == 15 && sum + points.size - i - 1 < minimumTotal)
                    return Double.NEGATIVE_INFINITY
            }
            return if (points.isEmpty()) 0.0 else sum/points.size
        }
    }
    private val cache = LinkedHashMap<String, Index>(32, .75f, true)
    @Volatile var kernelPreparationMilliseconds = 0.0
        private set
    // ART otherwise compiles the interpolation/scoring loop during the first
    // real scan. Exercise this small, input-independent kernel once in the
    // background preparation phase; it produces no map evidence or result.
    private val kernelPreparation = FutureTask {
        val started = System.nanoTime()
        val index = Index(64,64,ByteArray(64*64) { ((it%64)*4).toByte() })
        val points = List(128) { Pixel(8+it%40,8+it/4%40) }
        var checksum = 0.0
        repeat(12_000) { step ->
            checksum += index.score(points,.8+(step%9)*.05,(step%7).toDouble(),(step%5).toDouble())
        }
        repeat(4) {
            val seed = Pose(1.0,0.0,0.0)
            search(index,points,32.0,32.0,32.0,32.0,.5,2.0,0)
            expand(index,seed,points,32.0,32.0,32.0,32.0,42.0,.5,2.0)
            refine(index,seed,points,points,emptyList(),64,64,32.0,32.0,32.0,32.0,.5,2.0)
        }
        check(checksum.isFinite())
        kernelPreparationMilliseconds = (System.nanoTime()-started)/1e6
    }
    fun prepareKernel() { kernelPreparation.run(); kernelPreparation.get() }
    private val building = HashMap<Pair<Long,String>,FutureTask<Index>>()
    private var retained = 0L
    private var epoch = 0L
    val retainedBytes: Long get() = synchronized(cache) { retained }
    fun clear() = synchronized(cache) { cache.clear(); retained = 0L; epoch++ }
    private fun key(file: File, generation: String) =
        "${file.canonicalPath}|$generation|${file.length()}|${file.lastModified()}"
    fun isCached(file: File, generation: String): Boolean {
        val key = key(file,generation)
        return synchronized(cache) { cache.containsKey(key) }
    }
    fun load(file: File, generation: String, create: (() -> Index)? = null): Index {
        val key = key(file,generation)
        val (buildKey,task) = synchronized(cache) {
            cache[key]?.let { return it }
            val buildKey = epoch to key
            buildKey to building.getOrPut(buildKey) { FutureTask {
                val index = if (create != null) create() else {
                    val line = CvImages.loadGray(file)
                    try { build(line) } finally { line.release() }
                }
                check(key(file,generation) == key) { "Reference changed during index build" }
                synchronized(cache) {
                    if (epoch == buildKey.first && index.bytes <= 64L*1024*1024) {
                        // Different generations may finish out of order. Retain by
                        // exact key; the bounded LRU, not an old builder, evicts peers.
                        while (retained+index.bytes > 64L*1024*1024 && cache.isNotEmpty()) {
                            val oldest = cache.entries.first()
                            retained -= oldest.value.bytes; cache.remove(oldest.key)
                        }
                        cache[key] = index; retained += index.bytes
                    }
                }
                index
            } }
        }
        try {
            task.run() // FutureTask runs at most once, including foreground/preload races.
            return task.get()
        } finally {
            synchronized(cache) { if (building[buildKey] === task && task.isDone) building.remove(buildKey) }
        }
    }
    fun build(line: Mat, excluded: Mat? = null): Index {
        require(!line.empty() && line.channels() == 1)
        require(excluded == null || excluded.type() == CvType.CV_8UC1 && excluded.size() == line.size())
        val inverse = Mat(); val distances = Mat(); val encoded = Mat(); val coarse = Mat(); val mask = Mat()
        try {
            Imgproc.threshold(line, inverse, 128.0, 255.0, Imgproc.THRESH_BINARY_INV)
            require(Core.countNonZero(inverse) < inverse.total()) { "Empty structural line layer" }
            Imgproc.distanceTransform(inverse, distances, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
            distances.convertTo(encoded, CvType.CV_8UC1, 16.0)
            distances.convertTo(coarse, CvType.CV_8UC1, 2.0, 112.0)
            Core.compare(distances, Scalar(8.0), mask, Core.CMP_GT)
            coarse.copyTo(encoded, mask)
            return Index(line.cols(), line.rows(), ByteArray(line.cols()*line.rows()).also { encoded.get(0,0,it) },
                excluded?.let { source -> ByteArray(line.cols()*line.rows()).also { source.get(0,0,it) } },
                ByteArray(line.cols()*line.rows()).also { line.get(0,0,it) }.let { bytes ->
                    val positions=IntArray(bytes.count { (it.toInt() and 255) > 128 }); var at=0
                    for (i in bytes.indices) if ((bytes[i].toInt() and 255) > 128) positions[at++]=i
                    positions
                })
        } finally { inverse.release(); distances.release(); encoded.release(); coarse.release(); mask.release() }
    }
    /** Reverse evidence is restricted to independently revealed, valid live pixels.
     * Fog, occlusion and masked icons never become missing-reference-wall evidence. */
    class ReverseObservation(live: VpsgLiveExtractor.Observation) {
        val width = live.edges.cols(); val height = live.edges.rows()
        val domain = ByteArray(width*height).also { requireNotNull(live.revealed).get(0,0,it) }
        private val distances: FloatArray
        init {
            val inverse = Mat(); val distance = Mat()
            try {
                Core.bitwise_not(live.edges,inverse)
                Imgproc.distanceTransform(inverse,distance,Imgproc.DIST_L2,Imgproc.DIST_MASK_PRECISE)
                distances = FloatArray(width*height).also { distance.get(0,0,it) }
            } finally { inverse.release(); distance.release() }
        }
        fun distance(x: Double,y: Double): Double {
            if (x < 0 || y < 0 || x >= width-1 || y >= height-1) return 50.0
            val ix=x.toInt(); val iy=y.toInt(); val fx=x-ix; val fy=y-iy; val at=iy*width+ix
            return (distances[at]*(1-fx)+distances[at+1]*fx)*(1-fy) +
                (distances[at+width]*(1-fx)+distances[at+width+1]*fx)*fy
        }
    }
    private fun reverseEvidence(index: Index,pose: Pose,live: ReverseObservation,evidence: Evidence): Evidence {
        val eligible=IntArray(index.edgePositions.size); var count=0
        for (position in index.edgePositions) {
            val rx=position%index.width; val ry=position/index.width
            if (!index.observable(rx.toDouble(),ry.toDouble())) continue
            val x=rx*pose.scale+pose.x; val y=ry*pose.scale+pose.y
            val ix=x.roundToInt(); val iy=y.roundToInt()
            if (ix !in 0 until live.width || iy !in 0 until live.height ||
                (live.domain[iy*live.width+ix].toInt() and 255) <= 128) continue
            eligible[count++]=position
        }
        val tested=min(2048,count); var hits=0
        for (i in 0 until tested) {
            val position=eligible[i*count/tested]
            val x=(position%index.width)*pose.scale+pose.x
            val y=(position/index.width)*pose.scale+pose.y
            if (live.distance(x,y) <= VpsgAlignmentTuning.DISTANCE_TOLERANCE) hits++
        }
        return evidence.copy(reverseSupport=if (tested==0) 0.0 else hits.toDouble()/tested,
            reversePoints=tested,reverseEligiblePoints=count,reverseRequired=true)
    }
    fun pixels(edges: Mat): List<Pixel> {
        val width = edges.cols()
        val bytes = ByteArray(width*edges.rows()).also { edges.get(0,0,it) }
        return buildList { for (i in bytes.indices) if (bytes[i].toInt() != 0) add(Pixel(i%width,i/width)) }
    }
    fun sample(points: List<Pixel>, width: Int, height: Int, maximum: Int): List<Pixel> {
        val cells = Array(16) { ArrayList<Pixel>() }
        for (p in points) cells[min(3,p.x*4/width)+4*min(3,p.y*4/height)] += p
        val active = cells.filter { it.isNotEmpty() }
        if (active.isEmpty()) return emptyList()
        val quotas = IntArray(active.size)
        var left = min(maximum,points.size)
        while (left > 0) for (i in active.indices) if (left > 0 && quotas[i] < active[i].size) { quotas[i]++; left-- }
        return buildList { for (i in active.indices) for (j in 0 until quotas[i]) add(active[i][j*active[i].size/quotas[i]]) }
    }
    fun contours(edges: Mat): List<List<Pixel>> {
        val raw = ArrayList<MatOfPoint>(); val hierarchy = Mat(); val copy = edges.clone()
        try {
            Imgproc.findContours(copy, raw, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
            return raw.mapNotNull { contour ->
                val curve = MatOfPoint2f(*contour.toArray()); val poly = MatOfPoint2f()
                try {
                    if (Imgproc.arcLength(curve,false) < 30) null else {
                        Imgproc.approxPolyDP(curve,poly,1.5,true)
                        poly.toArray().map { Pixel(it.x.roundToInt(),it.y.roundToInt()) }
                    }
                } finally { curve.release(); poly.release() }
            }
        } finally { raw.forEach { it.release() }; hierarchy.release(); copy.release() }
    }
    fun verify(index: Index, pose: Pose, points: List<Pixel>, contours: List<List<Pixel>>, width: Int, height: Int,
        acceptanceOnly: Boolean = false, reverse: ReverseObservation? = null): Evidence {
        if (!pose.scale.isFinite() || pose.scale <= 0 || !pose.x.isFinite() || !pose.y.isFinite() || width <= 0 || height <= 0)
            return Evidence(0.0,50.0,0.0,true,0,0,"invalid-transform")
        var hits = 0; var sum = 0.0; var evaluated = 0
        val totals = IntArray(16); val supported = IntArray(16)
        fun distance(x: Int,y: Int) = index.distance((x-pose.x)/pose.scale,(y-pose.y)/pose.scale,pose.scale)
        for ((pointIndex,p) in points.withIndex()) {
            if (!index.observable((p.x-pose.x)/pose.scale,(p.y-pose.y)/pose.scale)) continue
            evaluated++
            val d = distance(p.x,p.y); sum += d
            val cell = min(3,p.x*4/width)+4*min(3,p.y*4/height); totals[cell]++
            if (d <= 5.5) { hits++; supported[cell]++ }
            if (acceptanceOnly && (pointIndex and 31) == 31) {
                val remaining = points.size-pointIndex-1
                // Excluded reference pixels can only reduce the remaining hits.
                // Even the all-observable/all-supported upper bound cannot pass.
                if (hits+remaining < .88*(evaluated+remaining))
                    return Evidence(hits.toDouble()/evaluated,sum/evaluated,0.0,true,evaluated,0,"support-upper-bound")
            }
        }
        val spatial = totals.indices.any { totals[it] >= 30 && supported[it] < totals[it]*.70 }
        if (acceptanceOnly && (spatial || contours.isEmpty() || evaluated < points.size*.5))
            return Evidence(if (evaluated == 0) 0.0 else hits.toDouble()/evaluated,
                if (evaluated == 0) 50.0 else sum/evaluated,0.0,true,evaluated,0,"spatial-support-conflict")
        var longest = 0.0
        var conflictDetail = ""
        for (contour in contours) {
            for (i in contour.indices) {
                val a = contour[i]; val b = contour[(i+1)%contour.size]
                val dx = b.x-a.x; val dy = b.y-a.y; val length = hypot(dx.toDouble(),dy.toDouble())
                val steps = max(abs(dx),abs(dy)); if (steps == 0) continue
                var run = 0.0
                for (step in 0..steps) {
                    val x = round(a.x+dx*step.toDouble()/steps).toInt()
                    val y = round(a.y+dy*step.toDouble()/steps).toInt()
                    if (!index.observable((x-pose.x)/pose.scale,(y-pose.y)/pose.scale)) { run = 0.0; continue }
                    val d = distance(x,y)
                    run = if (d > 5.5) run + if (step == 0) 0.0 else length/steps else 0.0
                    if (run > longest) { longest = run; conflictDetail = "${a.x},${a.y}->${b.x},${b.y}" }
                }
            }
            if (longest >= 30) break
        }
        val evidence = Evidence(if (evaluated == 0) 0.0 else hits.toDouble()/evaluated,
            if (evaluated == 0) 50.0 else sum/evaluated, longest,
            spatial || contours.isEmpty() || evaluated < points.size * .5, evaluated, totals.indices.count { totals[it] >= 30 && supported[it] >= totals[it]*.70 },
            conflictDetail + " cells=" + totals.indices.filter { totals[it] >= 30 && supported[it] < totals[it]*.70 }
                .joinToString { "$it:${supported[it]}/${totals[it]}" })
        return if (reverse == null) evidence else reverseEvidence(index,pose,reverse,evidence)
    }
    fun search(index: Index, points: List<Pixel>, ax: Double, ay: Double, gx: Double, gy: Double,
        minimum: Double, maximum: Double, gate: Int): List<Pose> {
        val retained = ArrayList<Pose>()
        // Balanced includes the complete Fast search, preserving its basins.
        for ((step,fine,count) in listOf(Triple(.08,.02,1), Triple(.04,.01,2))) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.search-pass")
            val scales = sortedSetOf(minimum,maximum,1.0.coerceIn(minimum,maximum))
            var scale = minimum
            while (scale <= maximum) { scales += scale; scale *= 1+step }
            val peaks = ArrayList<Pose>()
            for (s in scales) for (dx in -3..3 step 3) for (dy in -3..3 step 3) {
                com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.search-pose")
                val x = gx+dx-ax*s; val y = gy+dy-ay*s
                val pose = Pose(s,x,y,index.score(points,s,x,y),gate)
                val duplicate = peaks.indexOfFirst { abs(ln(it.scale/s)) < step*2 }
                if (duplicate >= 0 && peaks[duplicate].score >= pose.score) continue
                if (duplicate >= 0) peaks.removeAt(duplicate)
                peaks += pose; peaks.sortByDescending { it.score }
                if (peaks.size > count) peaks.removeAt(peaks.lastIndex)
            }
            for (seed in peaks) {
                com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.refine-seed")
                var best = seed
                val n = ceil(step/fine).toInt()
                for (i in -n..n) {
                    val s = seed.scale*(1+i*fine); if (s !in minimum..maximum) continue
                    for (dx in -3..3) for (dy in -3..3) {
                        val x = gx+dx-ax*s; val y = gy+dy-ay*s; val score = index.score(points,s,x,y,best.score)
                        if (score > best.score) best = Pose(s,x,y,score,gate)
                    }
                }
                retained += best
            }
        }
        val unique = ArrayList<Pose>()
        for (pose in retained.sortedByDescending { it.score }) {
            if (unique.none { abs(ln(it.scale/pose.scale)) < .01 }) unique += pose
            if (unique.size == 2) break
        }
        return unique
    }
    fun refine(index: Index, seed: Pose, sample: List<Pixel>, dense: List<Pixel>, contours: List<List<Pixel>>,
        width: Int, height: Int, ax: Double, ay: Double, gx: Double, gy: Double,
        minimum: Double, maximum: Double, maximumGateResidual: Double = 42.0,
        reverse: ReverseObservation? = null): Pair<Pose,Evidence>? {
        data class Proposal(val pose: Pose, val hits: Int, val distance: Double)
        val proposals = ArrayList<Proposal>()
        val ordering = compareByDescending<Proposal> { it.hits }.thenBy { it.distance }
        for (step in -15..15) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.refine-scale")
            val s = seed.scale*(1+step*.001); if (s !in minimum..maximum) continue
            val inverseScale = 1.0/s
            val scaleProposals = ArrayList<Proposal>()
            for (dx in -3..3) offsets@ for (dy in -3..3) {
                val x = seed.x+ax*(seed.scale-s)+dx; val y = seed.y+ay*(seed.scale-s)+dy
                if (hypot(x+ax*s-gx,y+ay*s-gy) > maximumGateResidual) continue
                var hits = 0; var sum = 0.0
                for (i in sample.indices) {
                    val p = sample[i]
                    val d = index.distance((p.x-x)*inverseScale,(p.y-y)*inverseScale,s); sum += d; if (d <= 5.5) hits++
                    if ((i and 15) == 15 && scaleProposals.size == 8) {
                        val worst = scaleProposals.last()
                        val upperHits = hits+sample.size-i-1
                        if (upperHits < worst.hits || upperHits == worst.hits && sum > worst.distance)
                            continue@offsets
                    }
                }
                if (scaleProposals.size == 8) {
                    val worst = scaleProposals.last()
                    // Stable ordering would immediately discard this proposal;
                    // avoid allocating a pose and sorting the list for it.
                    if (hits < worst.hits || hits == worst.hits && sum >= worst.distance) continue@offsets
                }
                scaleProposals += Proposal(Pose(s,x,y,seed.score,seed.gate),hits,sum)
                scaleProposals.sortWith(ordering)
                if (scaleProposals.size > 8) scaleProposals.removeAt(8)
            }
            proposals += scaleProposals
        }
        for (p in proposals.sortedWith(compareByDescending<Proposal> { it.hits }.thenBy { it.distance })) {
            val evidence = verify(index,p.pose,dense,contours,width,height,acceptanceOnly=true,reverse=reverse)
            if (evidence.supported) return p.pose to evidence
        }
        return null
    }

    /** Android gate boxes can differ from the authored anchor by more than desktop's
     * +/-3 pixels. Expand proposal search within the existing gate residual limit;
     * this never relaxes the dense verifier or the final registration checks. */
    fun expand(index: Index, seed: Pose, points: List<Pixel>, ax: Double, ay: Double,
        gx: Double, gy: Double, radius: Double, minimum: Double, maximum: Double): Pose {
        val coarse = if (points.size <= 64) points else List(64) { points[it*points.size/64] }
        var best = seed.copy(score=index.score(coarse,seed.scale,seed.x,seed.y))
        val extent = ceil(radius).toInt()
        for (step in -6..6) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.verify-scale")
            val scale = seed.scale*(1+step*.005)
            if (scale !in minimum..maximum) continue
            for (dx in -extent..extent step 6) for (dy in -extent..extent step 6) {
                if (hypot(dx.toDouble(),dy.toDouble()) > radius) continue
                val x = gx+dx-ax*scale; val y = gy+dy-ay*scale
                val score = index.score(coarse,scale,x,y,best.score)
                if (score > best.score) best = Pose(scale,x,y,score,seed.gate)
            }
        }
        val center = best
        best = best.copy(score=index.score(points,best.scale,best.x,best.y))
        for (step in -5..5) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.final-scale")
            val scale = center.scale*(1+step*.001)
            if (scale !in minimum..maximum) continue
            // Preserve the anchor residual of this basin as the scale changes.
            val cx = center.x+ax*(center.scale-scale); val cy = center.y+ay*(center.scale-scale)
            for (dx in -3..3) for (dy in -3..3) {
                val x = cx+dx; val y = cy+dy
                if (hypot(x+ax*scale-gx,y+ay*scale-gy) > radius) continue
                val score = index.score(points,scale,x,y,best.score)
                if (score > best.score) best = Pose(scale,x,y,score,seed.gate)
            }
        }
        return best
    }
}
