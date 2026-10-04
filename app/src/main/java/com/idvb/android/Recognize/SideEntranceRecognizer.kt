package com.idvb.android.recognize

import android.content.Context
import android.graphics.Bitmap
import com.idvb.android.data.FloorRecognitionAssets
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.gate.GateSearchContext
import com.idvb.android.recognize.gate.GateDetectionResult
import com.idvb.android.recognize.gate.GateSearchMode
import com.idvb.android.recognize.gate.GateTemplateDetector
import com.idvb.android.recognize.gate.GateTemplateRules
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.side.SideEntranceGateAssociationKind
import com.idvb.android.recognize.side.SideEntranceFeaturePreprocessor
import com.idvb.android.recognize.side.SideEntranceScanConfig
import com.idvb.android.recognize.side.SideEntranceScanInput
import com.idvb.android.recognize.side.SideEntranceScanPipeline
import com.idvb.android.recognize.side.SideEntranceScanProfiles
import com.idvb.android.recognize.side.SideEntranceScanResult
import com.idvb.android.recognize.side.SideEntranceScanCandidate
import com.idvb.android.recognize.structure.MapStructurePreprocessor
import com.idvb.android.recognize.structure.MapStructureRegistrar
import com.idvb.android.recognize.structure.StructureRegistrationResult
import com.idvb.android.recognize.structure.StructureRejectionReason
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Desktop SideEntrance initial-recognition route port, before structure verification. */
class SideEntranceRecognizer(
    context: Context,
    private val repository: MapRepository,
    private val classId: String? = null,
) : RecognitionRoute {
    private val appContext = context.applicationContext

    companion object {
        const val FEATURE_RATIO = .12
        const val MIN_REFERENCE_SIMILARITY = .55
        const val MIN_VERIFICATION_SIMILARITY = .68
        const val MIN_TEMPLATE_MARGIN = .035
        const val STRICT_CHAMFER_LIMIT = 3.0
        const val ROUTE = "desktop-side-entrance-structure-v1"
    }

    override fun recognize(frame: Bitmap): RecognitionResult = recognize(
        frame = frame,
        viewportBounds = ScreenRect(0.0, 0.0, frame.width.toDouble(), frame.height.toDouble()),
        clientWidth = frame.width,
        clientHeight = frame.height,
    )

    fun recognize(
        frame: Bitmap,
        viewportBounds: ScreenRect,
        clientWidth: Int,
        clientHeight: Int,
        progress: ((Double) -> Unit)? = null,
        detectedGates: GateDetectionResult? = null,
    ): RecognitionResult {
        val startedNanos = System.nanoTime()
        progress?.invoke(0.0)
        val catalog = repository.loadCatalog()
        val modeMaps = catalog.maps.filter { classId == null || it.classId == classId }
        val profile = SideEntranceScanProfiles.resolve(clientWidth, clientHeight)
        val scanFloorByClass = catalog.classes.associate { it.id to it.scanFloorKey }
        val inputs = buildInputs(modeMaps, profile, scanFloorByClass)
        val inputsAt = System.nanoTime()
        try {
            progress?.invoke(.10)
            val eligibleMapCount = modeMaps.count { map ->
                scanFloor(map, scanFloorByClass[map.classId])?.let {
                    repository.loadSideDoors(map.id, it.key).isNotEmpty()
                } == true
            }
            val grayFrame = CvImages.bitmapToGray(frame)
            try {
                GateTemplateDetector.fromAssets(appContext).use { gateDetector ->
                    val gateResult = detectedGates ?: gateDetector.detect(
                        liveMatchImage = grayFrame,
                        viewportBounds = viewportBounds,
                        clientWidth = clientWidth.toDouble(),
                        scoreThreshold = GateTemplateRules.MATCH_THRESHOLD,
                        searchContext = GateSearchContext(
                            mode = GateSearchMode.FULL_SEARCH,
                            allowDualGateEarlyExit = false,
                            allowSingleGateEarlyExit = false,
                            singleGateScoreThreshold = max(
                                GateTemplateRules.MATCH_THRESHOLD,
                                GateTemplateRules.EARLY_EXIT_SCORE_THRESHOLD,
                            ),
                        ),
                    )
                    progress?.invoke(.30)
                    val gatesAt = System.nanoTime()
                    if (gateResult.gates.isEmpty()) {
                        return buildResult(
                            frame,
                            modeMaps,
                            SideEntranceScanResult(
                                gateDetection = gateResult,
                                failureReason = "侧门扫描要求当前地图暴露门图标，但未检测到门。",
                                eligibleMapCount = eligibleMapCount,
                                readyMapCount = inputs.size,
                                rejectedCandidateCount = inputs.size,
                            ),
                            profile,
                            viewportBounds = viewportBounds,
                        )
                    }

                    val candidates = SideEntranceScanPipeline(profile).runScan(
                        capturedGrayFrame = grayFrame,
                        inputs = inputs,
                        detectedGates = gateResult.gates,
                        viewportBounds = viewportBounds,
                        topK = max(5, inputs.size),
                        progress = { value -> progress?.invoke(.30 + .55 * value) },
                    )
                    progress?.invoke(.85)
                    val templatesAt = System.nanoTime()
                    val scan = SideEntranceScanResult(
                        gateDetection = gateResult,
                        candidates = candidates,
                        eligibleMapCount = eligibleMapCount,
                        readyMapCount = inputs.size,
                        rejectedCandidateCount = max(0, inputs.size - candidates.size),
                        failureReason = when {
                            candidates.isNotEmpty() -> ""
                            inputs.isEmpty() -> "当前地图类别没有可用的侧门特征（就绪 0/$eligibleMapCount）。"
                            else -> "检测到侧门，但没有地图通过最低模板证据门槛。"
                        },
                    )
                    val structure = verifyStructureCandidates(
                        frame,
                        viewportBounds,
                        inputs,
                        candidates,
                        gateResult.gates.mapNotNull { gate -> gate.screenBounds.toLocalRect(viewportBounds, frame) },
                        profile,
                    )
                    progress?.invoke(.98)
                    android.util.Log.i("IDVB-Scan", "side prepareMs=${(inputsAt - startedNanos) / 1e6}" +
                        " gatesMs=${(gatesAt - inputsAt) / 1e6}" +
                        " templatesMs=${(templatesAt - gatesAt) / 1e6}" +
                        " structureMs=${(System.nanoTime() - templatesAt) / 1e6}")
                    return buildResult(frame, modeMaps, scan, profile, structure, viewportBounds)
                }
            } finally {
                grayFrame.release()
            }
        } finally {
            inputs.forEach { it.featureTemplate.release() }
        }
    }

    private fun buildInputs(
        maps: List<MapRecord>,
        profile: SideEntranceScanConfig,
        scanFloorByClass: Map<String, String?>,
    ): List<SideEntranceScanInput> = buildList {
        try {
        maps.forEach { map ->
            com.idvb.android.alignment.AlignmentCancellation.checkpoint("scan.side.input-map")
            val floor = scanFloor(map, scanFloorByClass[map.classId]) ?: return@forEach
            val assets = repository.loadRecognitionAssets(map.id, floor)
            val gate = repository.loadSideDoors(map.id, floor.key).firstOrNull() ?: return@forEach
            val stored = assets.sideEntranceFeature
            if (stored != null) {
                if (assets.recognitionWidth <= 0 || assets.recognitionHeight <= 0 ||
                    !stored.centerX.isFinite() || !stored.centerY.isFinite() || stored.radius <= 0
                ) return@forEach
                val template = runCatching { CvImages.loadGray(stored.file) }.getOrNull() ?: return@forEach
                if (template.cols() != stored.width || template.rows() != stored.height) {
                    template.release()
                    return@forEach
                }
                add(
                    SideEntranceScanInput(
                        map = map,
                        floorKey = floor.key,
                        featureTemplate = template,
                        featureCenterX = stored.centerX,
                        featureCenterY = stored.centerY,
                        recognitionWidth = assets.recognitionWidth,
                        recognitionHeight = assets.recognitionHeight,
                        sideEntranceBounds = gate,
                    ),
                )
                return@forEach
            }

            // Old/Android-authored maps may not carry the persisted v3 feature.
            // Rebuild it from the same recognition pixels without replacing an IDVM asset.
            val recognition = runCatching { loadRecognitionGray(map, floor, assets) }.getOrNull() ?: return@forEach
            try {
                val generated = runCatching {
                    SideEntranceFeaturePreprocessor.process(
                        recognition,
                        gate,
                        featureRegionRatio = profile.featureRegionRatio,
                        clampToBounds = false,
                    )
                }.getOrNull() ?: return@forEach
                add(
                    SideEntranceScanInput(
                        map = map,
                        floorKey = floor.key,
                        featureTemplate = generated.feature,
                        featureCenterX = generated.centerX,
                        featureCenterY = generated.centerY,
                        recognitionWidth = recognition.cols(),
                        recognitionHeight = recognition.rows(),
                        sideEntranceBounds = gate,
                    ),
                )
            } finally {
                recognition.release()
            }
        }
        } catch (failure: Throwable) {
            forEach { it.featureTemplate.release() }
            throw failure
        }
    }

    private fun scanFloor(map: MapRecord, key: String?): FloorRecord? =
        if (key == null) map.floors.minByOrNull(FloorRecord::sortOrder)
        else map.floors.firstOrNull { it.key == key }

    private fun loadRecognitionGray(
        map: MapRecord,
        floorRecord: FloorRecord,
        assets: FloorRecognitionAssets,
    ): Mat {
        assets.recognitionImageFile?.let { return CvImages.loadGray(it) }
        val source = CvImages.loadGray(repository.floorImageFile(map.id, floorRecord.imagePath))
        val region = assets.recognitionRegion ?: return source
        try {
            val left = floor(region.x * source.cols()).toInt().coerceIn(0, source.cols() - 1)
            val top = floor(region.y * source.rows()).toInt().coerceIn(0, source.rows() - 1)
            val right = ceil((region.x + region.width) * source.cols()).toInt().coerceIn(left + 1, source.cols())
            val bottom = ceil((region.y + region.height) * source.rows()).toInt().coerceIn(top + 1, source.rows())
            return source.submat(Rect(left, top, right - left, bottom - top)).clone()
        } finally {
            source.release()
        }
    }

    private fun loadRecognitionColor(
        map: MapRecord,
        floorRecord: FloorRecord,
        assets: FloorRecognitionAssets,
    ): Mat {
        assets.recognitionImageFile?.let { return CvImages.loadColor(it) }
        val source = CvImages.loadColor(repository.floorImageFile(map.id, floorRecord.imagePath))
        val region = assets.recognitionRegion ?: return source
        try {
            val left = floor(region.x * source.cols()).toInt().coerceIn(0, source.cols() - 1)
            val top = floor(region.y * source.rows()).toInt().coerceIn(0, source.rows() - 1)
            val right = ceil((region.x + region.width) * source.cols()).toInt().coerceIn(left + 1, source.cols())
            val bottom = ceil((region.y + region.height) * source.rows()).toInt().coerceIn(top + 1, source.rows())
            return source.submat(Rect(left, top, right - left, bottom - top)).clone()
        } finally {
            source.release()
        }
    }

    private fun verifyStructureCandidates(
        frame: Bitmap,
        viewportBounds: ScreenRect,
        inputs: List<SideEntranceScanInput>,
        candidates: List<SideEntranceScanCandidate>,
        dynamicIgnoreRegions: List<Rect>,
        profile: SideEntranceScanConfig,
    ): Map<Pair<String, String>, StructureRegistrationResult> {
        val selected = candidates
            .filter { it.matchScore >= profile.minimumVerificationSimilarity }
            .take(profile.maximumStructureVerificationCandidates)
        if (selected.isEmpty()) return emptyMap()
        val inputByKey = inputs.associateBy { it.map.id to it.floorKey }
        val liveColor = CvImages.bitmapToBgr(frame)
        try {
            MapStructurePreprocessor.processLive(liveColor, dynamicIgnoreRegions).use { liveStructure ->
                val registrar = MapStructureRegistrar()
                return buildMap {
                    selected.forEach { candidate ->
                        val startedNanos = System.nanoTime()
                        val key = candidate.map.id to candidate.floorKey
                        val input = inputByKey[key]
                        if (input == null) {
                            put(key, unavailableStructure("侧门特征元数据已丢失，无法建立结构位姿种子。"))
                            return@forEach
                        }
                        val floorRecord = candidate.map.floors.firstOrNull { it.key == candidate.floorKey }
                        if (floorRecord == null) {
                            put(key, unavailableStructure("候选楼层记录已丢失。"))
                            return@forEach
                        }
                        val assets = repository.loadRecognitionAssets(candidate.map.id, floorRecord)
                        val result = runCatching {
                            val referenceColor = loadRecognitionColor(candidate.map, floorRecord, assets)
                            try {
                                MapStructurePreprocessor.processReference(referenceColor).use { referenceStructure ->
                                    val seedOffsetX = viewportBounds.x + candidate.matchLocation.centerX -
                                        input.featureCenterX * candidate.matchScale
                                    val seedOffsetY = viewportBounds.y + candidate.matchLocation.centerY -
                                        input.featureCenterY * candidate.matchScale
                                    registrar.register(
                                        reference = referenceStructure,
                                        live = liveStructure,
                                        viewportBounds = viewportBounds,
                                        seedScale = candidate.matchScale,
                                        seedOffsetX = seedOffsetX,
                                        seedOffsetY = seedOffsetY,
                                        validMapBounds = assets.validMapBounds,
                                        allowStrongSeedEarlyExit =
                                            candidate.templateMargin >= profile.minimumTemplateMargin &&
                                                candidate.gateAssociationKind ==
                                                SideEntranceGateAssociationKind.DETECTED_GATE,
                                    )
                                }
                            } finally {
                                referenceColor.release()
                            }
                        }.getOrElse { error ->
                            unavailableStructure("结构验证无法读取或处理参考图：${error.message ?: error.javaClass.simpleName}")
                        }
                        put(
                            key,
                            result.copy(
                                elapsedMilliseconds = (System.nanoTime() - startedNanos) / 1_000_000.0,
                            ),
                        )
                    }
                }
            }
        } finally {
            liveColor.release()
        }
    }

    private fun unavailableStructure(detail: String) = StructureRegistrationResult(
        accepted = false,
        rejectionReason = StructureRejectionReason.INVALID_INPUT,
        failureReason = detail,
    )

    private fun buildResult(
        frame: Bitmap,
        modeMaps: List<MapRecord>,
        scan: SideEntranceScanResult,
        profile: SideEntranceScanConfig,
        structureResults: Map<Pair<String, String>, StructureRegistrationResult> = emptyMap(),
        viewportBounds: ScreenRect = ScreenRect(0.0, 0.0, frame.width.toDouble(), frame.height.toDouble()),
    ): RecognitionResult {
        val matched = scan.candidates.map { candidate ->
            val association = when (candidate.gateAssociationKind) {
                SideEntranceGateAssociationKind.DETECTED_GATE ->
                    "已关联门 · 残差 ${"%.1f".format(candidate.gateSpatialResidualPixels)}px"
                SideEntranceGateAssociationKind.TEMPLATE_ONLY_RESCUE -> "全帧补救 · 未关联门"
                SideEntranceGateAssociationKind.NONE -> "未关联门"
            }
            val structure = structureResults[candidate.map.id to candidate.floorKey]
            val best = structure?.best
            val disposition = if (structure?.accepted == true &&
                best != null && best.chamferPixels <= STRICT_CHAMFER_LIMIT
            ) CandidateDisposition.RELIABLE else CandidateDisposition.NEEDS_VERIFICATION
            val structureText = when {
                disposition == CandidateDisposition.RELIABLE ->
                    "结构已确认 · Chamfer ${"%.2f".format(best!!.chamferPixels)}px" +
                        " · 边缘 ${(best.edgeCoverage * 100).toInt()}% · 占用 ${(best.occupancyCoverage * 100).toInt()}%"
                structure != null -> "结构未通过 · ${structure.failureReason}"
                candidate.matchScore < profile.minimumVerificationSimilarity -> "模板只达到召回门槛，未进入结构验证"
                else -> "等待结构验证"
            }
            RecognitionCandidate(
                map = candidate.map,
                floorKey = candidate.floorKey,
                disposition = disposition,
                templateScore = candidate.matchScore,
                templateMargin = candidate.templateMargin,
                chamferPixels = best?.chamferPixels ?: Double.POSITIVE_INFINITY,
                edgeCoverage = best?.edgeCoverage ?: 0.0,
                occupancyCoverage = best?.occupancyCoverage ?: 0.0,
                referenceCoverage = best?.referenceCoverage ?: 0.0,
                consistentStructurePartitions = best?.consistentPartitions ?: 0,
                structureCompositeCost = best?.compositeCost ?: Double.POSITIVE_INFINITY,
                structureCandidateMargin = structure?.candidateMargin ?: 0.0,
                structureScale = structure?.transform?.scale ?: best?.scale ?: 0.0,
                structureOffsetX = structure?.transform?.offsetX ?: best?.offsetX ?: Double.NaN,
                structureOffsetY = structure?.transform?.offsetY ?: best?.offsetY ?: Double.NaN,
                structureRejectionReason = structure?.rejectionReason,
                usedStructureGlobalRecovery = structure?.usedGlobalRecovery == true,
                structureElapsedMilliseconds = structure?.elapsedMilliseconds ?: 0.0,
                matchScale = candidate.matchScale,
                matchBounds = candidate.matchLocation,
                gateAssociationKind = candidate.gateAssociationKind,
                associatedGateIndex = candidate.associatedGateIndex,
                gateSpatialResidualPixels = candidate.gateSpatialResidualPixels,
                evidenceLabel = "$structureText · $association · 模板缩放 ${"%.3f".format(candidate.matchScale)}" +
                    " · 模板相似度 ${(candidate.matchScore * 100).toInt()}%",
            )
        }.sortedWith(
            compareByDescending<RecognitionCandidate> { it.disposition == CandidateDisposition.RELIABLE }
                .thenBy { it.chamferPixels }
                .thenByDescending { it.edgeCoverage }
                .thenByDescending { it.occupancyCoverage }
                .thenByDescending { it.templateScore },
        )
        val included = matched.mapTo(mutableSetOf()) { it.map.id }
        val catalogOnly = modeMaps.filterNot { it.id in included }.map { map ->
            RecognitionCandidate(
                map = map,
                floorKey = map.floors.minByOrNull { it.sortOrder }?.key.orEmpty(),
                disposition = CandidateDisposition.CATALOG_ONLY,
                evidenceLabel = scan.failureReason.ifBlank { "未通过本次门约束模板召回" },
            )
        }
        return RecognitionResult(
            capturedRegion = frame,
            candidates = matched + catalogOnly,
            viewportBounds = viewportBounds,
            diagnostics = RecognitionScanDiagnostics(
                route = ROUTE,
                gateDetection = scan.gateDetection,
                sideEntranceConfig = profile,
                eligibleMapCount = scan.eligibleMapCount,
                readyMapCount = scan.readyMapCount,
                rejectedCandidateCount = scan.rejectedCandidateCount,
                structureVerificationCount = structureResults.size,
                reliableCandidateCount = matched.count { it.disposition == CandidateDisposition.RELIABLE },
                structureTotalMilliseconds = structureResults.values.sumOf { it.elapsedMilliseconds },
                failureReason = scan.failureReason,
            ),
        )
    }

    private fun ScreenRect.toLocalRect(viewport: ScreenRect, frame: Bitmap): Rect? {
        if (!isValid || !viewport.isValid) return null
        val left = floor(x - viewport.x).toInt().coerceIn(0, max(0, frame.width - 1))
        val top = floor(y - viewport.y).toInt().coerceIn(0, max(0, frame.height - 1))
        val right = ceil(x + width - viewport.x).toInt().coerceIn(left + 1, frame.width)
        val bottom = ceil(y + height - viewport.y).toInt().coerceIn(top + 1, frame.height)
        return Rect(left, top, right - left, bottom - top)
    }
}
