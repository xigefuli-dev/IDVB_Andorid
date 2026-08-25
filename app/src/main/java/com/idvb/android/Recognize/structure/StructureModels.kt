package com.idvb.android.recognize.structure

import org.opencv.core.Mat
import org.opencv.core.Rect

data class StructureRegistrationTuning(
    val maximumChamferPixels: Double = 3.0,
    val minimumEdgeCoverage: Double = .40,
    val minimumOccupancyCoverage: Double = .42,
    val minimumConsistentPartitions: Int = 2,
    val minimumCandidateMargin: Double = .04,
    val minimumEdgePixels: Int = 90,
    val minimumSpanPixels: Int = 28,
    val topCandidateCount: Int = 6,
    val edgeDistanceTolerancePixels: Double = 2.25,
    val distanceClipPixels: Double = 12.0,
    val scaleSearchRadius: Double = .02,
    val scaleSearchStep: Double = .01,
    val restrictedSearchRadiusPixels: Int = 96,
)

enum class StructureRejectionReason {
    NONE,
    INVALID_INPUT,
    INSUFFICIENT_STRUCTURE,
    NO_SEARCH_CANDIDATE,
    OUTSIDE_VALID_BOUNDS,
    WEAK_ABSOLUTE_SCORE,
    INCONSISTENT_STRUCTURE,
    AMBIGUOUS_CANDIDATES,
}

data class StructureTransform(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
)

data class StructureCandidate(
    val scale: Double,
    val referenceX: Int,
    val referenceY: Int,
    val offsetX: Double,
    val offsetY: Double,
    val chamferPixels: Double,
    val edgeCoverage: Double,
    val occupancyCoverage: Double,
    val referenceCoverage: Double,
    val consistentPartitions: Int,
    val compositeCost: Double,
    val isWithinValidBounds: Boolean,
    val usedGlobalSearch: Boolean,
)

data class StructureRegistrationResult(
    val accepted: Boolean,
    val transform: StructureTransform? = null,
    val rejectionReason: StructureRejectionReason,
    val failureReason: String,
    val best: StructureCandidate? = null,
    val candidates: List<StructureCandidate> = emptyList(),
    val candidateMargin: Double = 0.0,
    val secondScore: Double = Double.POSITIVE_INFINITY,
    val queryEdgePixels: Int = 0,
    val queryBounds: Rect = Rect(),
    val scaleHypothesisCount: Int = 0,
    val usedGlobalRecovery: Boolean = false,
    val elapsedMilliseconds: Double = 0.0,
)

internal class StructureFeatures(
    val structureMask: Mat,
    val edges: Mat,
) : AutoCloseable {
    override fun close() {
        structureMask.release()
        edges.release()
    }
}
