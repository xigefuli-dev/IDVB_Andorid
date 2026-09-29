package com.idvb.android.recognize.side

import android.graphics.Bitmap
import android.util.Log
import com.idvb.android.data.MapRepository
import com.idvb.android.data.FloorRecognitionAssets
import com.idvb.android.idvm.*
import com.idvb.android.recognize.*
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.gate.*
import com.idvb.android.recognize.structure.*
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Gate position removes the full-image translation search. Every map retains independent
 * scale basins; all visible pixels, variants and the final registered transform are checked. */
internal class SparseGateRecognizer(private val repository: MapRepository) {
    companion object { const val ROUTE = "side-gate-sparse-structure-v3" }
    private data class Input(val map: MapRecord, val floor: FloorRecord, val assets: FloorRecognitionAssets,
        val anchor: NormalizedRect) {
        val ax get() = (anchor.x+anchor.width/2)*assets.recognitionWidth
        val ay get() = (anchor.y+anchor.height/2)*assets.recognitionHeight
    }
    private data class Fit(val input: Input, val index: SparseGateSearch.Index,
        val hypotheses: List<Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>>,
        val formalRejected: Boolean = false) {
        val best = hypotheses.sortedWith(compareByDescending<Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>> { it.second.supported }
            .thenBy { it.second.cost }).first()
        val supported get() = !formalRejected && best.second.supported
    }

    private fun inputs(maps: List<MapRecord>): List<Input> {
        val classes = repository.loadCatalog().classes.associateBy { it.id }
        return maps.mapNotNull { map ->
            val key = classes[map.classId]?.scanFloorKey
            val floor = if (key == null) map.floors.minByOrNull { it.sortOrder } else map.floors.firstOrNull { it.key == key }
            floor ?: return@mapNotNull null
            val assets = repository.loadRecognitionAssets(map.id,floor)
            val anchor = repository.loadSideDoors(map.id,floor.key).firstOrNull() ?: return@mapNotNull null
            if (assets.prebuiltStructureLine == null || assets.recognitionWidth <= 0 || assets.recognitionHeight <= 0)
                return@mapNotNull null
            Input(map,floor,assets,anchor)
        }
    }

    private fun indexSource(input: Input) = input.assets.recognitionImageFile ?:
        repository.floorImageFile(input.map.id,input.floor.imagePath)

    private fun indexGeneration(input: Input): String {
        val line = input.assets.prebuiltStructureLine!!.file
        return "side-full-v3|${input.map.mapVersion}|${input.floor}|${line.canonicalPath}|${line.length()}|${line.lastModified()}"
    }

    private fun prepareIndex(input: Input): SparseGateSearch.Index {
        val generation = indexGeneration(input)
        return SparseGateSearch.load(indexSource(input),generation) {
            val reference = loadReference(input)
            try {
                MapStructurePreprocessor.processReference(reference).use { features ->
                    val line = CvImages.loadGray(input.assets.prebuiltStructureLine!!.file)
                    try {
                        Core.bitwise_or(features.edges,line,features.edges)
                        val excluded = referenceAnnotations(reference)
                        try {
                            SparseGateSearch.build(features.edges,excluded).also {
                                check(indexGeneration(input) == generation) { "Reference changed during preparation" }
                            }
                        } finally { excluded.release() }
                    } finally { line.release() }
                }
            } finally { reference.release() }
        }
    }

    /** Same indexes as recognition, prepared off the scan path. Two mobile workers,
     * active class only, cancellation between floors; no full-library PC rebuild. */
    fun prepare(maps: List<MapRecord>, isCurrent: () -> Boolean = { true }): Int {
        val selected = inputs(maps)
        if (selected.isEmpty()) return 0
        if (!isCurrent()) return 0
        SparseGateSearch.prepareKernel()
        val workers = Executors.newFixedThreadPool(min(2,selected.size))
        try {
            val tasks = selected.map { input -> workers.submit(Callable {
                if (isCurrent()) runCatching { prepareIndex(input) }
                    .onFailure { Log.w("IDVB-Prepare","Index failed: ${input.map.id}",it) }
            }) }
            tasks.forEach { it.get() }
            return if (!isCurrent()) 0 else selected.count {
                SparseGateSearch.isCached(indexSource(it),indexGeneration(it))
            }
        } finally { workers.shutdown() }
    }

    fun recognize(frame: Bitmap, viewport: ScreenRect, maps: List<MapRecord>, gates: GateDetectionResult,
        config: SideEntranceScanConfig, progress: ((Double)->Unit)? = null): RecognitionResult? {
        if (gates.gates.isEmpty() || maps.isEmpty()) return null
        val started = System.nanoTime()
        val catalog = repository.loadCatalog()
        val inputs = inputs(maps)
        // Legacy packages without any full-floor lines retain the old route.
        if (inputs.isEmpty()) return null
        val color = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(color).use { live ->
                maskAnnotations(color,live)
                val unobservedBoundaryPoints = VisibleWallEvidence.retainPhotometricWalls(color,live)
                // Shared exclusion across all maps, including authored boxes larger than icons.
                for (gate in gates.gates) {
                    // Mask the observed icon, not the largest possible authored
                    // anchor at the search ceiling. Wider zoom support must not
                    // erase additional walls from every identity's observation.
                    val width = gate.screenBounds.width * 1.5 + 18
                    val height = gate.screenBounds.height * 1.5 + 18
                    mask(live,gate.screenBounds.centerX-viewport.x-width/2,
                        gate.screenBounds.centerY-viewport.y-height/2,width,height)
                }
                val dense = SparseGateSearch.pixels(live.edges)
                val contours = SparseGateSearch.contours(live.edges)
                if (dense.size < 80 || contours.isEmpty()) {
                    val elapsed = (System.nanoTime()-started)/1e6
                    return RecognitionResult(frame, emptyList(), viewport, route=ROUTE,
                        sparseGateDiagnostics=SparseGateScanDiagnostics(elapsed,elapsed,0.0,0.0,
                            0,dense.size,false,0,false,emptyList()))
                }
                val sample = SparseGateSearch.sample(dense,frame.width,frame.height,128)
                // Proposals use a bounded spatial sample; only the final dense
                // verifier decides support. Do not run a 1024-point grid for
                // every losing scale/offset basin on a mobile CPU.
                val refineSample = sample
                val preparedAt = System.nanoTime()
                val indexNanos = AtomicLong()
                val fitNanos = AtomicLong()
                // Foreground scan is short-lived; keep background preparation at
                // two workers, and cap retrieval at four available CPU cores.
                val executor = Executors.newFixedThreadPool(minOf(4,
                    Runtime.getRuntime().availableProcessors().coerceAtLeast(1),inputs.size))
                val futures = inputs.map { input -> executor.submit(Callable {
                    runCatching {
                        val indexStarted = System.nanoTime()
                        // Use the same full-reference evidence policy for EVERY identity.
                        // Supplementing only the apparent winner could hide a competing map
                        // whose imported outer-wall layer also omitted internal structure.
                        val index = prepareIndex(input)
                        require(index.width == input.assets.recognitionWidth && index.height == input.assets.recognitionHeight)
                        val indexFinished = System.nanoTime()
                        indexNanos.addAndGet(indexFinished-indexStarted)
                        val hypotheses = ArrayList<Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>>()
                        for ((gateIndex,gate) in gates.gates.withIndex()) {
                            if (!gate.screenBounds.isValid) continue
                            val gx = gate.screenBounds.centerX-viewport.x; val gy = gate.screenBounds.centerY-viewport.y
                            val seeds = SparseGateSearch.search(index,sample,input.ax,input.ay,gx,gy,
                                config.minimumScale,config.maximumScale,gateIndex)
                            for (seed in seeds) {
                                val evidence = SparseGateSearch.verify(index,seed,dense,contours,frame.width,frame.height)
                                hypotheses += seed to evidence
                                if (!evidence.supported && evidence.support >= .50) {
                                    val expanded = SparseGateSearch.expand(index,seed,refineSample,input.ax,input.ay,gx,gy,
                                        config.maximumGateSpatialResidualPixels,config.minimumScale,config.maximumScale)
                                    val expandedEvidence = SparseGateSearch.verify(index,expanded,dense,contours,frame.width,frame.height)
                                    hypotheses += expanded to expandedEvidence
                                    if (!expandedEvidence.supported && expandedEvidence.support >= .80) {
                                        SparseGateSearch.refine(index,expanded,refineSample,dense,contours,frame.width,frame.height,
                                            input.ax,input.ay,gx,gy,config.minimumScale,config.maximumScale,
                                            config.maximumGateSpatialResidualPixels)?.let { hypotheses += it }
                                    }
                                    Log.i("IDVB-Scan","sparse expanded map=${input.map.title} support=${expandedEvidence.support}"+
                                        " scale=${expanded.scale} x=${expanded.x} y=${expanded.y} ax=${input.ax} ay=${input.ay}")
                                }
                                if (!evidence.supported && evidence.support >= .80) {
                                    SparseGateSearch.refine(index,seed,refineSample,dense,contours,frame.width,frame.height,
                                        input.ax,input.ay,gx,gy,config.minimumScale,config.maximumScale,
                                        config.maximumGateSpatialResidualPixels)?.let { hypotheses += it }
                                }
                            }
                        }
                        fitNanos.addAndGet(System.nanoTime()-indexFinished)
                        if (hypotheses.isEmpty()) null else Fit(input,index,hypotheses)
                    }.onFailure { Log.w("IDVB-Scan","Sparse gate floor failed: ${input.map.id}",it) }.getOrNull()
                }) }
                val retrievedFits = try {
                    futures.mapIndexedNotNull { i,future ->
                        val fit = future.get(); progress?.invoke(.15+.65*(i+1)/futures.size); fit
                    }
                } finally {
                    futures.forEach { runCatching { it.get() } }; executor.shutdown()
                }
                // A sparse supported pose is a proposal, not a confirmed identity.
                // Apply the same formal verifier to every supported competitor
                // before deciding whether the identity family is unique.
                val searchedAt = System.nanoTime()
                var formalComplete = true
                val registrations = HashMap<String, StructureRegistrationResult>()
                val fits = retrievedFits.map { fit ->
                    if (!fit.best.second.supported) return@map fit
                    val registration = runCatching { register(color, viewport, fit, gates) }
                        .onFailure {
                            formalComplete = false
                            Log.w("IDVB-Scan", "Formal verification failed: ${fit.input.map.id}", it)
                        }.getOrNull()
                    val transform = registration?.transform
                    if (registration != null) registrations[fit.input.map.id] = registration
                    val valid = registration?.accepted == true && transform != null &&
                        transform.scale in config.minimumScale..config.maximumScale
                    if (!valid) {
                        Log.i("IDVB-Scan", "sparse formal rejected map=${fit.input.map.title} reason=${registration?.failureReason}")
                        fit.copy(formalRejected = true)
                    } else {
                        registrations[fit.input.map.id] = registration!!
                        val pose = fit.best.first.copy(scale=transform!!.scale,
                            x=transform.offsetX-viewport.x,y=transform.offsetY-viewport.y)
                        val evidence = SparseGateSearch.verify(fit.index,pose,dense,contours,frame.width,frame.height)
                        val gate = gates.gates[pose.gate]
                        val residual = hypot(pose.x+fit.input.ax*pose.scale-(gate.screenBounds.centerX-viewport.x),
                            pose.y+fit.input.ay*pose.scale-(gate.screenBounds.centerY-viewport.y))
                        fit.copy(hypotheses=listOf(pose to evidence),
                            formalRejected=residual > config.maximumGateSpatialResidualPixels)
                    }
                }
                val complete = formalComplete && fits.size == maps.size && dense.size >= 80 && contours.isNotEmpty()
                val supported = fits.filter { it.supported }
                val best = supported.minWithOrNull(compareBy<Fit> { it.best.second.cost }
                    .thenByDescending { it.best.second.support }.thenBy { it.input.map.id })
                val familyUnique = best != null && sameScanIdentityFamily(best.input.map.id,
                    best.input.map.classId, supported.map { it.input.map.id }, catalog.variantGroups)
                var registered: StructureRegistrationResult? = null
                var finalPose = best?.best?.first
                var finalEvidence = best?.best?.second
                var unique = false
                if (complete && best != null && familyUnique) {
                    registered = registrations[best.input.map.id]
                    val transform = registered?.transform
                    if (registered?.accepted == true && registered.best != null && registered.best.chamferPixels <= 3 && transform != null &&
                        transform.scale in config.minimumScale..config.maximumScale) {
                        val pose = best.best.first.copy(scale=transform.scale,x=transform.offsetX-viewport.x,y=transform.offsetY-viewport.y)
                        val evidence = SparseGateSearch.verify(best.index,pose,dense,contours,frame.width,frame.height)
                        val gate = gates.gates[pose.gate]
                        val residual = hypot(pose.x+best.input.ax*pose.scale-(gate.screenBounds.centerX-viewport.x),
                            pose.y+best.input.ay*pose.scale-(gate.screenBounds.centerY-viewport.y))
                        unique = evidence.supported && residual <= config.maximumGateSpatialResidualPixels
                        finalPose = pose; finalEvidence = evidence
                    }
                }
                val finished = System.nanoTime()
                val ranked = fits.sortedWith(compareByDescending<Fit> { it.supported }.thenBy { it.best.second.cost })
                val candidates = ranked.map { fit ->
                    val pose = if (fit === best) finalPose!! else fit.best.first
                    val evidence = if (fit === best) finalEvidence!! else fit.best.second
                    val gate = gates.gates[pose.gate]
                    val confirmed = fit === best && unique
                    Log.i("IDVB-Scan","sparse map=${fit.input.map.title} support=${evidence.support}"+
                        " spatial=${evidence.spatialConflict} conflict=${evidence.longest} scale=${pose.scale} supported=${fit.supported} formalRejected=${fit.formalRejected}"+
                        " details=${evidence.conflictDetail}")
                    RecognitionCandidate(map=fit.input.map,floorKey=fit.input.floor.key,
                        disposition=if (confirmed) CandidateDisposition.RELIABLE else CandidateDisposition.NEEDS_VERIFICATION,
                        templateScore=pose.score, chamferPixels=evidence.mean,edgeCoverage=evidence.support,
                        structureCompositeCost=evidence.cost,
                        structureRejectionReason=registrations[fit.input.map.id]?.rejectionReason,
                        consistentStructurePartitions=evidence.cells,structureScale=pose.scale,
                        structureOffsetX=viewport.x+pose.x,structureOffsetY=viewport.y+pose.y,
                        occupancyCoverage=if (fit === best) registered?.best?.occupancyCoverage ?: 0.0 else 0.0,
                        referenceCoverage=if (fit === best) registered?.best?.referenceCoverage ?: 0.0 else 0.0,
                        matchScale=pose.scale,matchBounds=ScreenRect(pose.x,pose.y,fit.index.width*pose.scale,fit.index.height*pose.scale),
                        gateAssociationKind=SideEntranceGateAssociationKind.DETECTED_GATE,associatedGateIndex=pose.gate,
                        gateSpatialResidualPixels=hypot(pose.x+fit.input.ax*pose.scale-(gate.screenBounds.centerX-viewport.x),
                            pose.y+fit.input.ay*pose.scale-(gate.screenBounds.centerY-viewport.y)),
                        evidenceLabel="门约束结构 · 可见支持 ${(evidence.support*100).toInt()}% · " +
                            if (confirmed) "结构已确认" else "身份尚未唯一确认")
                }
                val included = candidates.mapTo(HashSet()) { it.map.id }
                val elapsed = (finished-started)/1e6
                Log.i("IDVB-Scan","sparse totalMs=$elapsed prepareMs=${(preparedAt-started)/1e6}"+
                    " searchVerifyMs=${(searchedAt-preparedAt)/1e6} formalMs=${(finished-searchedAt)/1e6}"+
                    " indexWorkerMs=${indexNanos.get()/1e6} fitWorkerMs=${fitNanos.get()/1e6}"+
                    " evaluated=${fits.size}/${maps.size} points=${dense.size} supported=${supported.size} unique=$unique"+
                    " unobservedBoundaryPoints=$unobservedBoundaryPoints"+
                    " formal=${registered?.failureReason}")
                return RecognitionResult(frame,candidates+maps.filterNot { it.id in included }.map {
                    RecognitionCandidate(it,"",CandidateDisposition.CATALOG_ONLY,evidenceLabel="地图结构资料未完整评估")
                },viewport,route=ROUTE,diagnostics=RecognitionScanDiagnostics(
                    route=ROUTE,gateDetection=gates,sideEntranceConfig=config,eligibleMapCount=maps.size,
                    readyMapCount=inputs.size,rejectedCandidateCount=fits.count { !it.supported },
                    structureVerificationCount=fits.size,reliableCandidateCount=if (unique) 1 else 0,
                    structureTotalMilliseconds=(finished-preparedAt)/1e6,
                    failureReason=if (unique) "" else if (!complete) "地图结构未完整评估" else "可见结构尚不能唯一确认地图"),
                    sparseGateDiagnostics=SparseGateScanDiagnostics(elapsed,(preparedAt-started)/1e6,
                        (searchedAt-preparedAt)/1e6,(finished-searchedAt)/1e6,fits.size,dense.size,complete,supported.size,unique,
                        fits.map { fit ->
                            val evidence = if (fit === best) finalEvidence!! else fit.best.second
                            SparseGateFloorEvidence(fit.input.map.id,fit.input.floor.key,
                                retrievedFits.first { it.input.map.id == fit.input.map.id }.hypotheses.size,
                                evidence.support,evidence.mean,evidence.longest,evidence.spatialConflict,fit.supported)
                        }))
            }
        } finally { color.release() }
    }

    private fun loadReference(input: Input): Mat {
        val assets = input.assets
        return assets.recognitionImageFile?.let { CvImages.loadColor(it) } ?: run {
            val full = CvImages.loadColor(repository.floorImageFile(input.map.id,input.floor.imagePath))
            val region = assets.recognitionRegion
            if (region == null) full else try {
                val x = floor(region.x*full.cols()).toInt().coerceIn(0,full.cols()-1)
                val y = floor(region.y*full.rows()).toInt().coerceIn(0,full.rows()-1)
                val r = ceil((region.x+region.width)*full.cols()).toInt().coerceIn(x+1,full.cols())
                val b = ceil((region.y+region.height)*full.rows()).toInt().coerceIn(y+1,full.rows())
                val sub = full.submat(Rect(x,y,r-x,b-y)); try { sub.clone() } finally { sub.release() }
            } finally { full.release() }
        }
    }
    private fun register(color: Mat, viewport: ScreenRect, fit: Fit, gates: GateDetectionResult): StructureRegistrationResult {
        val input = fit.input; val assets = input.assets
        val reference = loadReference(input)
        val ignore = gates.gates.mapNotNull { gate -> clipped(gate.screenBounds.x-viewport.x,
            gate.screenBounds.y-viewport.y,gate.screenBounds.width,gate.screenBounds.height,color.cols(),color.rows()) }
        try {
            MapStructurePreprocessor.processReference(reference).use { ref ->
                MapStructurePreprocessor.processLive(color,ignore).use { live ->
                    val pose = fit.best.first
                    return MapStructureRegistrar().register(ref,live,viewport,pose.scale,viewport.x+pose.x,viewport.y+pose.y,
                        assets.validMapBounds,allowStrongSeedEarlyExit=true)
                }
            }
        } finally { reference.release() }
    }
    private fun clipped(x: Double,y: Double,w: Double,h: Double,width: Int,height: Int): Rect? {
        val l = floor(x).toInt().coerceIn(0,width); val t = floor(y).toInt().coerceIn(0,height)
        val r = ceil(x+w).toInt().coerceIn(0,width); val b = ceil(y+h).toInt().coerceIn(0,height)
        return if (r>l && b>t) Rect(l,t,r-l,b-t) else null
    }
    private fun mask(live: VpsgLiveExtractor.Observation,x: Double,y: Double,w: Double,h: Double) {
        val rect = clipped(x,y,w,h,live.edges.cols(),live.edges.rows()) ?: return
        for (mat in listOf(live.edges,live.valid,live.proposal)) Imgproc.rectangle(mat,rect,Scalar.all(0.0),-1)
    }
    private fun maskAnnotations(color: Mat, live: VpsgLiveExtractor.Observation) {
        val hsv = Mat(); val markers = Mat(); val labels = Mat(); val stats = Mat(); val centroids = Mat()
        try {
            Imgproc.cvtColor(color,hsv,Imgproc.COLOR_BGR2HSV)
            // Red enemy markers and blue player badges are just as unobservable
            // as yellow/green annotations. Leaving them in the wall evidence made
            // a small viewport translation flip a 4x4 cell's conflict decision.
            Core.inRange(hsv,Scalar(0.0,90.0,100.0),Scalar(179.0,255.0,255.0),markers)
            val count = Imgproc.connectedComponentsWithStats(markers,labels,stats,centroids)
            val data = IntArray(count*stats.cols()).also { stats.get(0,0,it) }; val stride = stats.cols()
            for (i in 1 until count) {
                val at = i*stride; val width = data[at+Imgproc.CC_STAT_WIDTH]; val height = data[at+Imgproc.CC_STAT_HEIGHT]
                if (data[at+Imgproc.CC_STAT_AREA] < 6 || width !in 8..64 || height !in 8..64 ||
                    max(width,height) > min(width,height)*2.5) continue
                // Player badges carry a translucent direction cone beyond their
                // colored center; that cone is also an overlay, not a wall.
                val padding = max(8,ceil(max(width,height)*1.5).toInt())
                mask(live,(data[at+Imgproc.CC_STAT_LEFT]-padding).toDouble(),(data[at+Imgproc.CC_STAT_TOP]-padding).toDouble(),
                    (width+2*padding).toDouble(),(height+2*padding).toDouble())
            }
        } finally { hsv.release(); markers.release(); labels.release(); stats.release(); centroids.release() }
    }

    /** Authored colored routes hide reference walls; hidden pixels are unknown, not conflicts. */
    private fun referenceAnnotations(color: Mat): Mat {
        val hsv = Mat(); val excluded = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0,5.0))
        try {
            Imgproc.cvtColor(color,hsv,Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsv,Scalar(0.0,106.0,71.0),Scalar(179.0,255.0,255.0),excluded)
            Imgproc.dilate(excluded,excluded,kernel)
            return excluded
        } catch (error: Throwable) { excluded.release(); throw error }
        finally { hsv.release(); kernel.release() }
    }
}
