package com.idvb.android.alignment

import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.vpsg.VpsgLineScanner
import com.idvb.android.recognize.vpsg.VpsgAlignmentTuning
import com.idvb.android.recognize.vpsg.VpsgPreparedIndex
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.FloorRecord
import kotlin.math.min

/**
 * Desktop VPSG alignment of a selected floor: scale prior, bit-plane translation,
 * local/continuous refinement, and bidirectional spatial verification.
 * This path never scans identities or changes the user's selected map.
 */
class Vpsg(private val repository: MapRepository) : AlignmentMethod {
    override val id = AlignmentRegistry.DEFAULT_METHOD_ID

    override fun prepare(map: MapRecord, floor: FloorRecord, log: AlignmentLogSink) {
        com.idvb.android.recognize.vpsg.VpsgRuntimePreparation.prepare(repository, log)
        val prebuilt = repository.loadRecognitionAssets(map.id, floor).prebuiltStructureLine ?: return
        val lines = VpsgReferenceGeometry.prepare(repository, map, floor, prebuilt, log)
        val proposals = VpsgReferenceGeometry.proposalLine(lines)
        val indices = listOf(lines, proposals).distinctBy { it.file }.map {
            VpsgPreparedIndex.load(it.file, "${map.mapVersion}|${floor.prebuiltStructureLine}", log)
        }
        for (index in indices) {
        if (com.idvb.android.recognize.vpsg.VpsgNativeKernel.available) log.measure("vpsg.prepare-index.native") {
            index.nativeStride4; index.nativeK3; index.nativeK5; index.nativeEdges; index.nativeDistance
        }
        }
    }

    override fun align(request: AlignmentRequest, log: AlignmentLogSink): AlignmentResult {
        log.emit(AlignmentLogEvent("vpsg.desktop-basis", labels = DesktopVpsgBasis.labels))
        if (request.floor !in request.map.floors) return AlignmentResult.Unavailable("所选楼层已变化", "floor-changed")
        val prebuilt = log.measure("vpsg.resolve-assets") {
            repository.loadRecognitionAssets(request.map.id, request.floor).prebuiltStructureLine
        }
            ?: return AlignmentResult.Unavailable("当前楼层缺少有效的 VPSG 预制结构线图", "prebuilt-unavailable")
        val lines = VpsgReferenceGeometry.prepare(repository, request.map, request.floor, prebuilt, log)
        log.emit(AlignmentLogEvent("prebuilt", lines.algorithmId,
            mapOf("width" to lines.width.toDouble(), "height" to lines.height.toDouble(),
                "fileLength" to lines.file.length().toDouble(), "fileModifiedMillis" to lines.file.lastModified().toDouble()),
            labels = mapOf("record" to request.floor.prebuiltStructureLine.toString())))
        log.attach("reference.png") { lines.file.readBytes() }
        val decisions = mutableListOf<AlignmentLogEvent>()
        val decisionLog = object : AlignmentLogSink {
            override fun record(event: AlignmentLogEvent) {
                if (event.gates.isNotEmpty() || event.stage == "verification") decisions += event
                log.emit(event)
            }
            override fun attach(name: String, bytes: () -> ByteArray) = log.attach(name, bytes)
            override fun attachOwned(name: String, bytes: () -> ByteArray) = log.attachOwned(name, bytes)
            override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) = log.attachDirect(name, capture)
        }
        val selected = VpsgLineScanner(repository).alignSelected(
            request.frame, request.viewport, request.map, request.floor, lines, decisionLog,
            proposalLines = VpsgReferenceGeometry.proposalLine(lines),
        )
        if (selected != null && (request.floorSelectedByIndicator || request.map.floors.size == 1 ||
                min(selected.evidence.visibleSupport ?: 0.0, selected.evidence.referenceSupport ?: 0.0) >= .90)) return selected
        val rejected = if (selected == null) AlignmentExplanation.rejected(decisions) else
            AlignmentResult.Rejected("当前结构支持不足以排除其他楼层，请展开更多地图结构", "floor-ambiguous")
        if (selected != null) log.emit(AlignmentLogEvent("vpsg.selected-floor.identity-check",
            "weak-manual-pose-requires-competing-floor-evidence",
            measurements = mapOf("bidirectionalSupport" to min(selected.evidence.visibleSupport ?: 0.0, selected.evidence.referenceSupport ?: 0.0)),
            thresholds = mapOf("strongSupport" to .90),
            labels = mapOf("policy" to "pose-acceptance-does-not-establish-manually-selected-floor-identity")))
        if (request.floorSelectedByIndicator) {
            log.emit(AlignmentLogEvent("vpsg.selected-floor.outcome", rejected.code,
                labels = mapOf("selectedFloor" to request.floor.key, "policy" to "indicator-authoritative; structure-checks-pose-only")))
            return rejected
        }
        // Floor identity and pose uniqueness are separate questions: repeated corridors
        // can identify the floor while still forbidding an automatic transform commit.
        fun floorSupport(events: List<AlignmentLogEvent>): Double = events.asSequence()
            .filter { it.stage == "vpsg.verify.pose-evidence" && it.gates.all(AlignmentGate::passed) }
            .filter { (it.measurements["referencePoints"] ?: 0.0) >= VpsgAlignmentTuning.MIN_TESTED_POINTS &&
                (it.measurements["reverseSupport"] ?: 0.0) >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT }
            .maxOfOrNull { min(it.measurements.getValue("support"), it.measurements.getValue("reverseSupport")) } ?: 0.0
        val floorScores = mutableMapOf(request.floor.key to floorSupport(decisions))
        val floorNames = request.map.floors.associate { it.key to it.displayName }
        for ((index, floor) in request.map.floors.withIndex()) {
            if (floor.key == request.floor.key) continue
            request.cancellation.throwIfCancelled("vpsg.floor-diagnosis")
            val reference = repository.loadRecognitionAssets(request.map.id, floor).prebuiltStructureLine
            if (reference == null) {
                log.emit(AlignmentLogEvent("vpsg.floor-diagnosis.unavailable", labels = mapOf("floorKey" to floor.key)))
                continue
            }
            val floorDecisions = mutableListOf<AlignmentLogEvent>()
            val branch = object : AlignmentLogSink {
                override val enabled get() = log.enabled
                override fun record(event: AlignmentLogEvent) {
                    if (event.stage == "vpsg.verify.pose-evidence") floorDecisions += event
                    log.emit(event.copy(labels = event.labels + ("diagnosticFloorKey" to floor.key)))
                }
                override fun attach(name: String, bytes: () -> ByteArray) = log.attach("floor-$index-$name", bytes)
                override fun attachOwned(name: String, bytes: () -> ByteArray) = log.attachOwned("floor-$index-$name", bytes)
                override fun attachDirect(name: String, capture: () -> java.nio.ByteBuffer) = log.attachDirect("floor-$index-$name", capture)
            }
            val preparedReference = VpsgReferenceGeometry.prepare(repository, request.map, floor, reference, branch)
            branch.attach("reference.png") { preparedReference.file.readBytes() }
            val fit = log.measure("vpsg.floor-diagnosis.total") {
                VpsgLineScanner(repository).alignSelected(request.frame, request.viewport, request.map, floor, preparedReference, branch,
                    proposalLines = VpsgReferenceGeometry.proposalLine(preparedReference))
            }
            log.emit(AlignmentLogEvent("vpsg.floor-diagnosis.result", if (fit == null) "rejected" else "verified",
                labels = mapOf("floorKey" to floor.key, "referenceArtifact" to "floor-$index-reference.png",
                    "policy" to "diagnosis-only-manual-floor-selection")))
            floorScores[floor.key] = floorSupport(floorDecisions)
        }
        val ranked = floorScores.entries.sortedByDescending { it.value }
        val winner = ranked.firstOrNull()
        val margin = (winner?.value ?: 0.0) - (ranked.getOrNull(1)?.value ?: 0.0)
        val mismatch = winner != null && winner.key != request.floor.key &&
            floorScores.size == request.map.floors.size && winner.value >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT &&
            margin >= VpsgAlignmentTuning.MIN_POSE_MARGIN
        log.emit(AlignmentLogEvent("vpsg.selected-floor.outcome", rejected.code,
            measurements = floorScores.mapKeys { "floorSupport.${it.key}" } + ("floorMargin" to margin),
            thresholds = mapOf("minimumFloorMargin" to VpsgAlignmentTuning.MIN_POSE_MARGIN),
            labels = mapOf("selectedFloor" to request.floor.key, "matchingFloor" to if (mismatch) winner!!.key else "unresolved",
                "policy" to "floor-identity-only; ambiguous-pose-never-committed")))
        return if (mismatch) AlignmentResult.Rejected(
            "楼层不符：当前选择 ${request.floor.displayName}，画面匹配 ${floorNames.getValue(winner!!.key)}，请手动切换楼层", "floor-mismatch")
        else if (selected != null && winner?.key == request.floor.key &&
            floorScores.size == request.map.floors.size && margin >= VpsgAlignmentTuning.MIN_POSE_MARGIN) selected
        else if (ranked.count { it.value >= VpsgAlignmentTuning.MIN_REVERSE_SUPPORT } > 1)
            AlignmentResult.Rejected("当前楼层无法唯一贴合，可能楼层不符；请检查楼层或展开更多地图结构", "floor-ambiguous")
        else rejected
    }
}
