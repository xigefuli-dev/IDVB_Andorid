package com.idvb.android.recognize.side

import kotlin.math.abs

data class SideEntranceScanConfig(
    val featureRegionRatio: Double = .12,
    val coarseScaleStep: Double = .06,
    val refineStepsPerSide: Int = 3,
    val coarsePyramidFactor: Int = 4,
    val coarseScorePruneThreshold: Double = 0.0,
    val scanParallelism: Int = 4,
    val minimumReferenceSimilarity: Double = .55,
    val minimumVerificationSimilarity: Double = .68,
    val minimumTemplateMargin: Double = .035,
    val maximumGateSpatialResidualPixels: Double = 42.0,
    val scaleBoundaryTolerance: Double = .02,
    val maximumStructureVerificationCandidates: Int = 8,
    val maximumReferenceCandidates: Int = 5,
    val minimumScale: Double = .55,
    val maximumScale: Double = 2.2,
) {
    fun normalized(): SideEntranceScanConfig = copy(
        featureRegionRatio = featureRegionRatio.takeIf(Double::isFinite)?.coerceIn(.01, 1.0) ?: .12,
        coarseScaleStep = coarseScaleStep.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(.5) ?: .06,
        refineStepsPerSide = refineStepsPerSide.coerceIn(1, 12),
        coarsePyramidFactor = coarsePyramidFactor.coerceIn(2, 8),
        coarseScorePruneThreshold = coarseScorePruneThreshold.takeIf(Double::isFinite)?.coerceIn(-1.0, 1.0) ?: 0.0,
        scanParallelism = scanParallelism.coerceIn(1, 4),
        minimumReferenceSimilarity = minimumReferenceSimilarity.coerceIn(0.0, 1.0),
        minimumVerificationSimilarity = minimumVerificationSimilarity.coerceIn(0.0, 1.0),
        minimumTemplateMargin = minimumTemplateMargin.coerceIn(0.0, 1.0),
        maximumGateSpatialResidualPixels = maximumGateSpatialResidualPixels.coerceAtLeast(1.0),
        scaleBoundaryTolerance = scaleBoundaryTolerance.coerceIn(0.0, .25),
        maximumStructureVerificationCandidates = maximumStructureVerificationCandidates.coerceAtLeast(1),
        maximumReferenceCandidates = maximumReferenceCandidates.coerceAtLeast(1),
        minimumScale = minimumScale.takeIf { it.isFinite() && it > 0.0 } ?: .55,
        maximumScale = maximumScale.takeIf { it.isFinite() && it > 0.0 } ?: 2.2,
    ).let { if (it.maximumScale >= it.minimumScale) it else it.copy(minimumScale = it.maximumScale, maximumScale = it.minimumScale) }
}

/** Mirrors desktop resolution-profile selection and side_entrance.toml values. */
object SideEntranceScanProfiles {
    private data class Profile(val width: Int, val height: Int, val config: SideEntranceScanConfig)

    private val profiles = listOf(
        Profile(1920, 1080, SideEntranceScanConfig(coarseScaleStep = .05, scanParallelism = 2, minimumScale = .40)),
        Profile(1600, 900, SideEntranceScanConfig(coarseScaleStep = .05, scanParallelism = 2, minimumScale = .32)),
        Profile(2560, 1440, SideEntranceScanConfig(minimumScale = .45)),
        Profile(3440, 1440, SideEntranceScanConfig(minimumScale = .45)),
        Profile(2560, 1080, SideEntranceScanConfig(minimumScale = .45)),
        Profile(2560, 1600, SideEntranceScanConfig(minimumScale = .50)),
    )

    fun resolve(clientWidth: Int, clientHeight: Int): SideEntranceScanConfig {
        profiles.firstOrNull { it.width == clientWidth && it.height == clientHeight }?.let { return it.config }
        profiles.minByOrNull { abs(it.width - clientWidth) + abs(it.height - clientHeight) }
            ?.takeIf { abs(it.width - clientWidth) <= 100 && abs(it.height - clientHeight) <= 100 }
            ?.let { return it.config }
        if (clientHeight > 0) {
            val ratio = clientWidth.toDouble() / clientHeight
            profiles.minByOrNull { abs(it.width.toDouble() / it.height - ratio) }
                ?.takeIf { abs(it.width.toDouble() / it.height - ratio) < .05 }
                ?.let { return it.config }
        }
        return SideEntranceScanConfig()
    }
}
