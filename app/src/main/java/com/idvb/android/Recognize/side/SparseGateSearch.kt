package com.idvb.android.recognize.side

import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.vpsg.VpsgAlignmentTuning
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.emit
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
        excluded: ByteArray? = null, val edgePositions: IntArray = intArrayOf()) {
        companion object {
            private val decoded = DoubleArray(256) { value ->
                if (value <= 128) value/16.0 else (value-112)/2.0
            }
        }
        // The exclusion domain is binary. Retaining a byte per pixel doubled
        // the catalog's index footprint and evicted prepared peers on every scan.
        private val excludedBits = excluded?.let { source ->
            require(source.size == width*height)
            LongArray((source.size+63)/64).also { bits ->
                for (i in source.indices) if (source[i].toInt() != 0)
                    bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
            }
        }
        val bytes get() = distances.size + (excludedBits?.size ?: 0)*8 + edgePositions.size*4
        fun copyEdgesTo(target: Mat) {
            target.create(height,width,CvType.CV_8UC1)
            val bytes=ByteArray(width*height)
            for (position in edgePositions) bytes[position]=255.toByte()
            target.put(0,0,bytes)
        }
        fun observable(x: Double, y: Double): Boolean {
            if (x < 0 || y < 0 || x >= width || y >= height) return true
            val bits = excludedBits ?: return true
            val at = y.toInt()*width+x.toInt()
            return bits[at ushr 6] and (1L shl (at and 63)) == 0L
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
            mustBeat: Double = Double.NEGATIVE_INFINITY, evaluated: DoubleArray? = null): Double {
            var sum = 0.0
            val inverseScale = 1.0/scale
            val minimumTotal = mustBeat * points.size - 1e-9
            for (i in points.indices) {
                val p = points[i]
                val d = distance((p.x-x)*inverseScale, (p.y-y)*inverseScale, scale)
                sum += if (d <= .8) 1.0 else if (d <= 2.5) .7 else if (d <= 5.5) .4 else 0.0
                // Exact upper bound, not a weaker acceptance threshold. Even if
                // every remaining point scores 1, this pose cannot replace best.
                if ((i and 15) == 15 && sum + points.size - i - 1 < minimumTotal) {
                    evaluated?.let { it[0] = sum; it[1] = (i+1).toDouble() }
                    return Double.NEGATIVE_INFINITY
                }
            }
            evaluated?.let { it[0] = sum; it[1] = points.size.toDouble() }
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
    fun build(line: Mat, excluded: Mat? = null, requiredLine: Mat = line): Index {
        require(!line.empty() && line.channels() == 1)
        require(requiredLine.type() == CvType.CV_8UC1 && requiredLine.size() == line.size())
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
                // Source appearance explains observed contours in the forward distance
                // index. Only mandatory walls can be evidence of a missing live wall.
                ByteArray(line.cols()*line.rows()).also { requiredLine.get(0,0,it) }.let { bytes ->
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
        val firstRevealedRow = domain.indexOfFirst { (it.toInt() and 255) > 128 }.let { if (it < 0) height else it/width }
        val lastRevealedRow = domain.indexOfLast { (it.toInt() and 255) > 128 }.let { if (it < 0) -1 else it/width }
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
    private fun reverseEvidence(index: Index,pose: Pose,live: ReverseObservation,evidence: Evidence,
        eligible: IntArray = IntArray(index.edgePositions.size)): Evidence {
        var count=0
        // Prepared edge positions are row-major. Rows outside the captured
        // revealed domain cannot contribute to the existing exact predicate.
        // Pad rounding bounds by one reference row; preserve point/sample order.
        val firstRow = (ceil((live.firstRevealedRow-.5-pose.y)/pose.scale).toInt()-1).coerceIn(0,index.height)
        val endRow = (floor((live.lastRevealedRow+.5-pose.y)/pose.scale).toInt()+2).coerceIn(0,index.height)
        fun lowerBound(position: Int): Int {
            var left=0; var right=index.edgePositions.size
            while (left<right) {
                val middle=(left+right) ushr 1
                if (index.edgePositions[middle]<position) left=middle+1 else right=middle
            }
            return left
        }
        val first=lowerBound(firstRow*index.width); val end=lowerBound(endRow*index.width)
        for (i in first until end) {
            if (i and 1023 == 0) com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.reverse-reference-row")
            val position=index.edgePositions[i]
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
        acceptanceOnly: Boolean = false, reverse: ReverseObservation? = null, competitive: Evidence? = null): Evidence {
        if (!pose.scale.isFinite() || pose.scale <= 0 || !pose.x.isFinite() || !pose.y.isFinite() || width <= 0 || height <= 0)
            return Evidence(0.0,50.0,0.0,true,0,0,"invalid-transform")
        var hits = 0; var sum = 0.0; var evaluated = 0
        val supportBound = max(.88,competitive?.bidirectionalSupport ?: .88)
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
                if (hits+remaining < supportBound*(evaluated+remaining))
                    return Evidence(hits.toDouble()/evaluated,sum/evaluated,0.0,true,evaluated,0,
                        if (competitive == null) "support-upper-bound" else "incumbent-support-upper-bound")
            }
        }
        val support = if (evaluated == 0) 0.0 else hits.toDouble()/evaluated
        val mean = if (evaluated == 0) 50.0 else sum/evaluated
        // Reverse support cannot exceed forward support. A pose that cannot
        // improve the existing support/mean ordering needs no contour or reverse
        // pass. This is the same winner objective, including its strict tie rule.
        if (acceptanceOnly && competitive != null && (support < competitive.bidirectionalSupport ||
                support == competitive.bidirectionalSupport && mean >= competitive.mean))
            return Evidence(support,mean,0.0,true,evaluated,0,"incumbent-forward-dominance")
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
        minimum: Double, maximum: Double, gate: Int,
        log: com.idvb.android.alignment.AlignmentLogSink = com.idvb.android.alignment.AlignmentLogSink.NONE,
        maximumGateResidual: Double = 42.0): List<Pose> {
        val started = System.nanoTime()
        val coarseEvidence = if (log.enabled) ArrayList<Double>() else null
        val refinedEvidence = if (log.enabled) ArrayList<Double>() else null
        val evaluated = if (log.enabled) DoubleArray(2) else null
        var scoredPoints = 0.0
        var possiblePoints = 0.0
        fun remember(target: ArrayList<Double>?, step: Double, scale: Double, x: Double, y: Double, bound: Double) {
            if (target == null) return
            val values = requireNotNull(evaluated)
            val count = points.size.coerceAtLeast(1).toDouble()
            target.add(step); target.add(scale); target.add(x); target.add(y)
            target.add(values[0]/count); target.add((values[0]+points.size-values[1])/count)
            target.add(values[1]); target.add(bound.coerceAtLeast(0.0))
            scoredPoints += values[1]; possiblePoints += points.size
        }
        val retained = ArrayList<Pose>()
        // Balanced includes the complete Fast search, preserving its basins.
        // A local forward alias may beat the true scale and later fail reverse
        // verification. Preserve independent scale basins until the dense verifier.
        for ((step,fine,count) in listOf(Triple(.08,.02,1), Triple(.04,.01,4))) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.search-pass")
            val scales = sortedSetOf(minimum,maximum,1.0.coerceIn(minimum,maximum))
            var scale = minimum
            while (scale <= maximum) { scales += scale; scale *= 1+step }
            val peaks = ArrayList<Pose>()
            val extent = if (step == .08) 3 else (floor(maximumGateResidual/12)*12).toInt()
            val stride = if (step == .08) 3 else 12
            for (s in scales) for (dx in -extent..extent step stride) for (dy in -extent..extent step stride) {
                com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.search-pose")
                if (hypot(dx.toDouble(),dy.toDouble()) > maximumGateResidual) continue
                val x = gx+dx-ax*s; val y = gy+dy-ay*s
                val duplicate = peaks.indexOfFirst { abs(ln(it.scale/s)) < step*2 }
                val bound = if (duplicate >= 0) peaks[duplicate].score else
                    if (peaks.size == count) peaks.last().score else Double.NEGATIVE_INFINITY
                val score = index.score(points,s,x,y,bound,evaluated)
                remember(coarseEvidence,step,s,x,y,bound)
                if (score < bound) continue
                val pose = Pose(s,x,y,score,gate)
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
                        val x = seed.x+ax*(seed.scale-s)+dx
                        val y = seed.y+ay*(seed.scale-s)+dy
                        if (hypot(x+ax*s-gx,y+ay*s-gy) > maximumGateResidual) continue
                        val score = index.score(points,s,x,y,best.score,evaluated)
                        remember(refinedEvidence,step,s,x,y,best.score)
                        if (score > best.score) best = Pose(s,x,y,score,gate)
                    }
                }
                retained += best
            }
        }
        val unique = ArrayList<Pose>()
        for (pose in retained.sortedByDescending { it.score }) {
            if (unique.none { abs(ln(it.scale/pose.scale)) < .01 }) unique += pose
            if (unique.size == 4) break
        }
        if (log.enabled) log.emit(com.idvb.android.alignment.AlignmentLogEvent("scan.side.proposal-search",
            "gate-residual-domain-before-basin-selection; dense-and-formal-verification-required",
            durationNanos = System.nanoTime() - started,
            measurements = mapOf("minimumScale" to minimum,"maximumScale" to maximum,
                "gateX" to gx,"gateY" to gy,"anchorX" to ax,"anchorY" to ay,
                "samplePoints" to points.size.toDouble(),"gateIndex" to gate.toDouble(),
                "scoredPoints" to scoredPoints,"unprunedPoints" to possiblePoints),
            thresholds = mapOf("maximumBasins" to 4.0,"sameBasinLogScale" to .01,
                "maximumGateResidual" to maximumGateResidual),
            labels = mapOf("coarseColumns" to "step,scale,x,y,scoreLowerBound,scoreUpperBound,evaluatedPoints,incumbentScore",
                "refinedColumns" to "step,scale,x,y,scoreLowerBound,scoreUpperBound,evaluatedPoints,incumbentScore",
                "selectedColumns" to "scale,x,y,score,gate","scoringPolicy" to "unchanged-forward-128-spatial-sample-v1",
                "coarseOffsets" to "fast=-3,0,3; balanced=12-pixel-grid-inside-gate-residual-radius",
                "refineOffsets" to "seed-residual-preserved; -3..3",
                "passes" to "0.08/0.02/1;0.04/0.01/4",
                "pruning" to "exact-remaining-score-upper-bound-including-diagnostics; equal-bounds-are-full-scores; pruned-upper-bound-below-incumbent"),
            series = mapOf("coarse" to requireNotNull(coarseEvidence),
                "refined" to requireNotNull(refinedEvidence),
                "selected" to unique.flatMap { listOf(it.scale,it.x,it.y,it.score,it.gate.toDouble()) })))
        return unique
    }
    fun refine(index: Index, seed: Pose, sample: List<Pixel>, dense: List<Pixel>, contours: List<List<Pixel>>,
        width: Int, height: Int, ax: Double, ay: Double, gx: Double, gy: Double,
        minimum: Double, maximum: Double, maximumGateResidual: Double = 42.0,
        reverse: ReverseObservation? = null, log: AlignmentLogSink = AlignmentLogSink.NONE): Pair<Pose,Evidence>? {
        val started = System.nanoTime()
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
        val proposalNanos = System.nanoTime()-started
        var forwardNanos = 0L; var reverseNanos = 0L; var reversePasses = 0
        val scratch = if (reverse == null) null else IntArray(index.edgePositions.size)
        val recorded = if (log.enabled) ArrayList<Double>() else null
        var best: Pair<Pose,Evidence>? = null
        for (p in proposals.sortedWith(compareByDescending<Proposal> { it.hits }.thenBy { it.distance })) {
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.refine-verification")
            val incumbent = best?.second
            val forwardStarted = System.nanoTime()
            val forward = verify(index,p.pose,dense,contours,width,height,acceptanceOnly=true,competitive=incumbent)
            forwardNanos += System.nanoTime()-forwardStarted
            val evidence = if (forward.supported && reverse != null) {
                val reverseStarted = System.nanoTime()
                reversePasses++
                reverseEvidence(index,p.pose,reverse,forward,requireNotNull(scratch)).also {
                    reverseNanos += System.nanoTime()-reverseStarted
                }
            } else forward
            recorded?.apply {
                add(p.pose.scale); add(p.pose.x); add(p.pose.y); add(p.hits.toDouble()); add(p.distance)
                add(max(.88,incumbent?.bidirectionalSupport ?: .88)); add(evidence.support); add(evidence.mean)
                add(evidence.total.toDouble()); add(evidence.longest); add(evidence.reverseSupport); add(evidence.reversePoints.toDouble())
                add(evidence.reverseEligiblePoints.toDouble()); add(evidence.cells.toDouble()); add(if (evidence.spatialConflict) 1.0 else 0.0)
                add(when (evidence.conflictDetail) {
                    "incumbent-support-upper-bound" -> 1.0
                    "incumbent-forward-dominance" -> 2.0
                    else -> if (!forward.supported) 3.0 else if (!evidence.supported) 4.0 else 0.0
                })
            }
            if (evidence.supported && (best == null || evidence.bidirectionalSupport > best.second.bidirectionalSupport ||
                evidence.bidirectionalSupport == best.second.bidirectionalSupport && evidence.mean < best.second.mean))
                best = p.pose to evidence
        }
        if (log.enabled) log.emit(AlignmentLogEvent("scan.side.refine",if (best == null) "no-supported-refinement" else "supported",
            durationNanos=System.nanoTime()-started,
            measurements=mapOf("proposalMs" to proposalNanos/1e6,"forwardMs" to forwardNanos/1e6,"reverseMs" to reverseNanos/1e6,
                "proposals" to proposals.size.toDouble(),"reversePasses" to reversePasses.toDouble(),
                "seedScale" to seed.scale,"seedX" to seed.x,"seedY" to seed.y,
                "samplePoints" to sample.size.toDouble(),"densePoints" to dense.size.toDouble(),
                "reverseFirstRevealedRow" to (reverse?.firstRevealedRow ?: -1).toDouble(),
                "reverseLastRevealedRow" to (reverse?.lastRevealedRow ?: -1).toDouble(),
                "anchorX" to ax,"anchorY" to ay,"gateX" to gx,"gateY" to gy),
            thresholds=mapOf("minimumScale" to minimum,"maximumScale" to maximum,"maximumGateResidual" to maximumGateResidual,
                "minimumSupport" to .88,"scaleRadius" to .015,"scaleStep" to .001,"translationRadius" to 3.0,"proposalsPerScale" to 8.0),
            series=mapOf("candidates" to requireNotNull(recorded),"selected" to best?.let { listOf(it.first.scale,it.first.x,it.first.y,
                it.second.bidirectionalSupport,it.second.mean) }.orEmpty()),
            labels=mapOf("columns" to "scale,x,y,sampleHits,sampleDistance,incumbentSupport,forwardSupport,mean,evaluatedForwardPoints,longestConflict,reverseSupport,reversePoints,reverseEligiblePoints,supportedCells,spatialConflict,decision",
                "decisions" to "0=supported;1=remaining-forward-support-upper-bound;2=full-forward-dominated;3=forward-gates-rejected;4=reverse-gates-rejected",
                "gateThresholds" to "sparseGateScan.structuralThresholds; forward-distance=5.5; forward-support=0.88; cells=30/0.70; conflict-length<30; reverse-points>=80; reverse-support>=0.65",
                "reverseIteration" to "row-major-reference-edges; inverse-revealed-row-bounds-with-one-reference-row-padding; unchanged-observable-and-round-to-live-domain-predicate; unchanged-uniform-2048-sample-order",
                "pruning" to "unchanged-support-then-mean-winner-objective; reverse-never-improves-forward-upper-bound; strict-ties-retain-incumbent",
                "timingScope" to "proposal-forward-reverse-independent; nested-in-refine-total; event-format-and-append-outside-stage")))
        return best
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
