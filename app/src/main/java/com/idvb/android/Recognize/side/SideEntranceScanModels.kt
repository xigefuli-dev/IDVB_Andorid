package com.idvb.android.recognize.side

import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.gate.GateDetection
import com.idvb.android.recognize.gate.GateDetectionResult
import com.idvb.android.recognize.gate.ScreenRect
import org.opencv.core.Mat

enum class SideEntranceCandidateDisposition { NEEDS_VERIFICATION, REJECTED }

enum class SideEntranceRejectionReason {
    NONE,
    WEAK_TEMPLATE_SIMILARITY,
    AMBIGUOUS_TEMPLATE_RANKING,
    GATE_SPATIAL_MISMATCH,
    SCALE_AT_SEARCH_BOUNDARY,
    INVALID_FEATURE_DATA,
}

enum class SideEntranceGateAssociationKind { NONE, DETECTED_GATE, TEMPLATE_ONLY_RESCUE }

data class SideEntranceScanInput(
    val map: MapRecord,
    val floorKey: String,
    val featureTemplate: Mat,
    val featureCenterX: Double,
    val featureCenterY: Double,
    val recognitionWidth: Int,
    val recognitionHeight: Int,
    val sideEntranceBounds: NormalizedRect,
)

data class SideEntranceScanCandidate(
    val map: MapRecord,
    val floorKey: String,
    val matchScore: Double,
    var matchLocation: ScreenRect,
    val matchScale: Double,
    var templateMargin: Double = 0.0,
    var gateSpatialResidualPixels: Double = Double.POSITIVE_INFINITY,
    var associatedGate: GateDetection? = null,
    var associatedGateIndex: Int = -1,
    var gateAssociationKind: SideEntranceGateAssociationKind = SideEntranceGateAssociationKind.NONE,
    var disposition: SideEntranceCandidateDisposition = SideEntranceCandidateDisposition.NEEDS_VERIFICATION,
    var rejectionReason: SideEntranceRejectionReason = SideEntranceRejectionReason.NONE,
    var rejectionDetail: String = "",
)

data class SideEntranceScanResult(
    val gateDetection: GateDetectionResult,
    val candidates: List<SideEntranceScanCandidate> = emptyList(),
    val failureReason: String = "",
    val eligibleMapCount: Int = 0,
    val readyMapCount: Int = 0,
    val rejectedCandidateCount: Int = 0,
)
