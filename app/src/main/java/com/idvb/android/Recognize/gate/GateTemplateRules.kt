package com.idvb.android.recognize.gate

/** Values frozen from desktop GateConfig at reference commit 4654a90. */
object GateTemplateRules {
    const val REFERENCE_CLIENT_WIDTH = 2560.0
    const val REFERENCE_SCALE = 0.275
    const val FALLBACK_PAIR_THRESHOLD = 0.6

    const val MATCH_THRESHOLD = 0.72
    const val NMS_IOU_THRESHOLD = 0.25
    const val SPATIAL_CLUSTER_IOU_THRESHOLD = 0.35
    const val MAXIMUM_GATE_CANDIDATES = 6
    const val WARM_SCALE_START = 0.85
    const val WARM_SCALE_STEP = 0.075
    const val WARM_SCALE_MAXIMUM = 1.15
    const val EARLY_EXIT_SCORE_THRESHOLD = 0.85
    const val SINGLE_GATE_SCALE_TOLERANCE = 0.15
    const val SINGLE_GATE_AMBIGUITY_GAP = 0.08
    const val FULL_SEARCH_MIN_SCALES_BEFORE_SINGLE_GATE_EXIT = 5
    const val CANNY_LOW_THRESHOLD = 45.0
    const val CANNY_HIGH_THRESHOLD = 135.0
    const val DEFAULT_WARM_GATE_SEARCH_BUDGET_MS = 120
    const val DEFAULT_SELECTED_MAP_FULL_SEARCH_BUDGET_MS = 150
}
