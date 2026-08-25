package com.idvb.android.recognize

import android.graphics.Bitmap
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.gate.GateDetectionResult
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.side.SideEntranceGateAssociationKind
import com.idvb.android.recognize.side.SideEntranceScanConfig
import com.idvb.android.recognize.structure.MapStructurePreprocessor
import com.idvb.android.recognize.structure.StructureRejectionReason

enum class CandidateDisposition { RELIABLE, NEEDS_VERIFICATION, CATALOG_ONLY }

data class RecognitionCandidate(
    val map: MapRecord,
    val floorKey: String,
    val disposition: CandidateDisposition,
    val templateScore: Double = 0.0,
    val templateMargin: Double = 0.0,
    val chamferPixels: Double = Double.POSITIVE_INFINITY,
    val edgeCoverage: Double = 0.0,
    val occupancyCoverage: Double = 0.0,
    val referenceCoverage: Double = 0.0,
    val consistentStructurePartitions: Int = 0,
    val structureCompositeCost: Double = Double.POSITIVE_INFINITY,
    val structureCandidateMargin: Double = 0.0,
    val structureScale: Double = 0.0,
    val structureOffsetX: Double = Double.NaN,
    val structureOffsetY: Double = Double.NaN,
    val structureRejectionReason: StructureRejectionReason? = null,
    val usedStructureGlobalRecovery: Boolean = false,
    val structureElapsedMilliseconds: Double = 0.0,
    val matchScale: Double = 0.0,
    val matchBounds: ScreenRect? = null,
    val gateAssociationKind: SideEntranceGateAssociationKind = SideEntranceGateAssociationKind.NONE,
    val associatedGateIndex: Int = -1,
    val gateSpatialResidualPixels: Double = Double.POSITIVE_INFINITY,
    val evidenceLabel: String,
)

data class RecognitionScanDiagnostics(
    val route: String,
    val gateDetection: GateDetectionResult,
    val sideEntranceConfig: SideEntranceScanConfig,
    val eligibleMapCount: Int,
    val readyMapCount: Int,
    val rejectedCandidateCount: Int,
    val structureAlgorithmVersion: Int = MapStructurePreprocessor.ALGORITHM_VERSION,
    val structureVerificationCount: Int = 0,
    val reliableCandidateCount: Int = 0,
    val structureTotalMilliseconds: Double = 0.0,
    val failureReason: String,
)

data class RecognitionResult(
    val capturedRegion: Bitmap,
    val candidates: List<RecognitionCandidate>,
    val viewportBounds: ScreenRect = ScreenRect(
        0.0,
        0.0,
        capturedRegion.width.toDouble(),
        capturedRegion.height.toDouble(),
    ),
    val diagnostics: RecognitionScanDiagnostics? = null,
)

/** 后台扫描路线预留；当前只由前台触发实现调用。 */
interface RecognitionRoute {
    fun recognize(frame: Bitmap): RecognitionResult
}

interface BackgroundRecognitionRoute {
    fun prepare()
    fun submitFrame(frame: Bitmap, timestampMillis: Long)
    fun stop()
}
