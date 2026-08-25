package com.idvb.android.idvm

import kotlin.math.max
import kotlin.math.min

/**
 * Keeps IDVM gate geometry in its wire-format coordinate space while adapting
 * it at the Android editor boundary.
 *
 * IDVM gate bounds are relative to the effective recognition image. Android's
 * map editor displays the complete source floor image, so editor coordinates
 * must be converted through [recognitionRegion] in both directions.
 */
object IdvmGateCoordinateAdapter {
    private const val EPSILON = 1e-6

    fun gatesForSourceImageEditor(
        document: GatesDocument,
        floorKey: String,
        recognitionRegion: NormalizedRect?,
    ): List<NormalizedRect> = document.gates
        .asSequence()
        .filter {
            it.role == "sideEntrance" && it.enabled &&
                it.floorKey.equals(floorKey, ignoreCase = true)
        }
        .mapNotNull(Gate::bounds)
        .map { toSourceImage(it, recognitionRegion) }
        .toList()

    fun replaceSideEntrancesFromSourceImageEditor(
        document: GatesDocument,
        floorKey: String,
        sourceImageBounds: List<NormalizedRect>,
        recognitionRegion: NormalizedRect?,
    ): GatesDocument {
        val existing = document.gates.filter {
            it.role == "sideEntrance" && it.floorKey.equals(floorKey, ignoreCase = true)
        }
        val retained = document.gates.filterNot {
            it.role == "sideEntrance" && it.floorKey.equals(floorKey, ignoreCase = true)
        }
        val usedIds = retained.mapTo(mutableSetOf()) { it.id }
        val replacements = sourceImageBounds.mapIndexed { index, bounds ->
            val previous = existing.getOrNull(index)
            val id = previous?.id?.takeIf(usedIds::add)
                ?: uniqueGateId(floorKey, index, usedIds)
            (previous ?: Gate(id, floorKey, "sideEntrance")).copy(
                id = id,
                floorKey = floorKey,
                role = "sideEntrance",
                bounds = toRecognitionImage(bounds, recognitionRegion),
                enabled = true,
            )
        }
        return document.copy(gates = retained + replacements)
    }

    fun toSourceImage(
        recognitionRelative: NormalizedRect,
        recognitionRegion: NormalizedRect?,
    ): NormalizedRect {
        validateNormalizedRect(recognitionRelative, "IDVM 侧门")
        if (recognitionRegion == null) return recognitionRelative
        val region = recognitionRegion
        validateNormalizedRect(region, "识别区域")
        return clamp(
            NormalizedRect(
                x = region.x + recognitionRelative.x * region.width,
                y = region.y + recognitionRelative.y * region.height,
                width = recognitionRelative.width * region.width,
                height = recognitionRelative.height * region.height,
            )
        )
    }

    fun toRecognitionImage(
        sourceRelative: NormalizedRect,
        recognitionRegion: NormalizedRect?,
    ): NormalizedRect {
        validateNormalizedRect(sourceRelative, "移动端侧门")
        if (recognitionRegion == null) return sourceRelative
        val region = recognitionRegion
        validateNormalizedRect(region, "识别区域")
        require(contains(region, sourceRelative)) {
            "侧门必须完整位于原地图的识别区域内"
        }
        return clamp(
            NormalizedRect(
                x = (sourceRelative.x - region.x) / region.width,
                y = (sourceRelative.y - region.y) / region.height,
                width = sourceRelative.width / region.width,
                height = sourceRelative.height / region.height,
            )
        )
    }

    private fun uniqueGateId(floorKey: String, index: Int, usedIds: MutableSet<String>): String {
        val base = "$floorKey-side"
        var suffix = index + 1
        var candidate = if (suffix == 1) base else "$base-$suffix"
        while (!usedIds.add(candidate)) {
            suffix++
            candidate = "$base-$suffix"
        }
        return candidate
    }

    private fun validateNormalizedRect(value: NormalizedRect, what: String) {
        require(value.x.isFinite() && value.y.isFinite() && value.width.isFinite() && value.height.isFinite()) {
            "$what 坐标必须为有限数"
        }
        require(value.width > 0.0 && value.height > 0.0) { "$what 必须有正面积" }
        require(
            value.x >= -EPSILON && value.y >= -EPSILON &&
                value.x + value.width <= 1.0 + EPSILON &&
                value.y + value.height <= 1.0 + EPSILON
        ) { "$what 必须完整位于 0..1" }
    }

    private fun contains(outer: NormalizedRect, inner: NormalizedRect): Boolean =
        inner.x >= outer.x - EPSILON && inner.y >= outer.y - EPSILON &&
            inner.x + inner.width <= outer.x + outer.width + EPSILON &&
            inner.y + inner.height <= outer.y + outer.height + EPSILON

    private fun clamp(value: NormalizedRect): NormalizedRect {
        val left = value.x.coerceIn(0.0, 1.0)
        val top = value.y.coerceIn(0.0, 1.0)
        val right = min(1.0, max(left, value.x + value.width))
        val bottom = min(1.0, max(top, value.y + value.height))
        return NormalizedRect(left, top, right - left, bottom - top)
    }
}
