package com.idvb.android.recognize.gate

/** Physical screen rectangle, matching the desktop MapScreenRect contract. */
data class ScreenRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0
    val isValid: Boolean get() = width > 0.0 && height > 0.0
}

data class GateDetection(
    val score: Double,
    val scale: Double,
    val screenBounds: ScreenRect,
)

enum class GateSearchMode {
    FULL_SEARCH,
    WARM_SCALE_SEARCH,
    LOCAL_CONFIRMATION_SEARCH,
    LOCKED_SCALE,
}

enum class GateSearchStopReason {
    COMPLETED,
    DUAL_GATE_EARLY_EXIT,
    SINGLE_GATE_WARM_EXIT,
    BUDGET_EXCEEDED,
    NO_VALID_SCALE,
    INVALID_SEARCH_CONTEXT,
}

data class GateSearchContext(
    val mode: GateSearchMode = GateSearchMode.FULL_SEARCH,
    val warmScale: Double? = null,
    val predictedGateRegions: List<ScreenRect> = emptyList(),
    val predictedScale: Double? = null,
    val lockedScale: Double? = null,
    val localRoiTemplatePaddingFactor: Double = 1.0,
    val localRoiMinimumPaddingPixels: Int = 24,
    val maximumExpectedMotionPixels: Int = 0,
    val timeBudgetMilliseconds: Int? = null,
    val allowDualGateEarlyExit: Boolean = true,
    val allowSingleGateEarlyExit: Boolean = false,
    val singleGateScoreThreshold: Double = GateTemplateRules.EARLY_EXIT_SCORE_THRESHOLD,
    val singleGateScaleTolerance: Double = GateTemplateRules.SINGLE_GATE_SCALE_TOLERANCE,
    val ambiguityScoreGap: Double = GateTemplateRules.SINGLE_GATE_AMBIGUITY_GAP,
)

data class GateDetectionResult(
    val gates: List<GateDetection> = emptyList(),
    val rawCandidates: List<GateDetection> = emptyList(),
    val searchModeUsed: GateSearchMode = GateSearchMode.FULL_SEARCH,
    val stopReason: GateSearchStopReason = GateSearchStopReason.COMPLETED,
    val scalesEvaluated: Int = 0,
    val regionsEvaluated: Int = 0,
    val matchTemplateCalls: Int = 0,
    val budgetExceeded: Boolean = false,
    val elapsedMilliseconds: Double = 0.0,
)
