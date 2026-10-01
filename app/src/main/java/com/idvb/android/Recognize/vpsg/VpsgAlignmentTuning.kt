package com.idvb.android.recognize.vpsg

/** One source for operational thresholds and the values exported in alignment diagnostics. */
internal object VpsgAlignmentTuning {
    const val MIN_SCALE = .4
    const val MAX_SCALE = 2.4
    // Mobile pinch zoom produces a 70 px live pitch against a 25 px reference (2.8x).
    // Selected-floor alignment supports that domain without changing scan identity gates.
    const val MAX_SELECTED_SCALE = 4.0
    const val MIN_REFERENCE_EDGES = 300
    const val MIN_PITCH = 5.0
    const val MIN_PITCH_RATIO = 2.0
    const val MIN_LIVE_POINTS = 100
    const val MIN_LIVE_WIDTH = 100
    const val MIN_LIVE_HEIGHT = 75
    const val MIN_TESTED_POINTS = 80
    const val MIN_SUPPORT = .88
    const val MIN_REVERSE_SUPPORT = .65
    const val DISTANCE_TOLERANCE = 5.5
    const val MIN_CELL_POINTS = 30
    const val MIN_CELL_SUPPORT = .70
    const val MAX_CONFLICT_LENGTH = 30.0
    const val MIN_POSE_MARGIN = .09
    const val RIVAL_DISTANCE = 10.0

    val thresholds get() = linkedMapOf(
        "minimumScale" to MIN_SCALE, "maximumScale" to MAX_SCALE,
        "minimumReferenceEdges" to MIN_REFERENCE_EDGES.toDouble(), "minimumPitch" to MIN_PITCH,
        "minimumPitchRatio" to MIN_PITCH_RATIO, "minimumLivePoints" to MIN_LIVE_POINTS.toDouble(),
        "minimumLiveWidth" to MIN_LIVE_WIDTH.toDouble(), "minimumLiveHeight" to MIN_LIVE_HEIGHT.toDouble(),
        "minimumTestedPoints" to MIN_TESTED_POINTS.toDouble(), "minimumVisibleSupport" to MIN_SUPPORT,
        "minimumReverseSupport" to MIN_REVERSE_SUPPORT, "distanceTolerancePixels" to DISTANCE_TOLERANCE,
        "minimumCellPoints" to MIN_CELL_POINTS.toDouble(), "minimumCellSupport" to MIN_CELL_SUPPORT,
        "maximumConflictLengthPixels" to MAX_CONFLICT_LENGTH, "minimumPoseMargin" to MIN_POSE_MARGIN,
        "rivalDistancePixels" to RIVAL_DISTANCE,
    )
}
