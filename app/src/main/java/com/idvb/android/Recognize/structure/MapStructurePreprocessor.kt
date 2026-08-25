package com.idvb.android.recognize.structure

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Edge-only port of desktop MapStructurePreprocessor algorithm version 6. */
object MapStructurePreprocessor {
    const val ALGORITHM_VERSION = 6

    internal fun processReference(source: Mat): StructureFeatures = process(source, retainDominantCluster = false)

    internal fun processLive(source: Mat, dynamicIgnoreRegions: List<Rect>): StructureFeatures =
        process(source, retainDominantCluster = true, dynamicIgnoreRegions = dynamicIgnoreRegions)

    private fun process(
        source: Mat,
        retainDominantCluster: Boolean,
        dynamicIgnoreRegions: List<Rect> = emptyList(),
    ): StructureFeatures {
        require(!source.empty()) { "结构预处理不能处理空图像" }
        val bgr = toBgr(source)
        val gray = Mat()
        val hsv = Mat()
        val normalized = Mat()
        val blurred = Mat()
        var nuisance: Mat? = null
        var structure: Mat? = null
        var edges: Mat? = null
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try {
                clahe.apply(gray, normalized)
            } finally {
                clahe.collectGarbage()
                clahe.clear()
            }
            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)

            val channels = ArrayList<Mat>(3)
            Core.split(hsv, channels)
            try {
                val saturated = Mat()
                val bright = Mat()
                nuisance = Mat()
                try {
                    Imgproc.threshold(channels[1], saturated, 105.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.threshold(channels[2], bright, 70.0, 255.0, Imgproc.THRESH_BINARY)
                    Core.bitwise_and(saturated, bright, nuisance)
                    val nuisanceKernel = kernel(Imgproc.MORPH_ELLIPSE, 3)
                    try {
                        Imgproc.dilate(nuisance, nuisance, nuisanceKernel, org.opencv.core.Point(-1.0, -1.0), 1)
                    } finally {
                        nuisanceKernel.release()
                    }
                    applyDynamicIgnoreRegions(nuisance, dynamicIgnoreRegions)
                } finally {
                    saturated.release()
                    bright.release()
                }

                structure = Mat()
                Imgproc.threshold(blurred, structure, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                val valid = Mat()
                try {
                    Core.bitwise_not(nuisance, valid)
                    Core.bitwise_and(structure, valid, structure)
                } finally {
                    valid.release()
                }
                removeSmallComponents(structure, edgeMode = false)
                val closeKernel = kernel(Imgproc.MORPH_RECT, 5)
                try {
                    Imgproc.morphologyEx(structure, structure, Imgproc.MORPH_CLOSE, closeKernel)
                } finally {
                    closeKernel.release()
                }
                val openKernel = kernel(Imgproc.MORPH_RECT, 3)
                try {
                    Imgproc.morphologyEx(structure, structure, Imgproc.MORPH_OPEN, openKernel)
                    val border = max(1, (min(source.cols(), source.rows()) * .02).roundToInt())
                    Imgproc.rectangle(
                        structure,
                        Rect(0, 0, structure.cols(), structure.rows()),
                        Scalar.all(0.0),
                        border,
                    )
                    if (retainDominantCluster) retainDominantStructureCluster(structure)

                    val canny = Mat()
                    val gradient = Mat()
                    val expanded = Mat()
                    try {
                        Imgproc.Canny(blurred, canny, 35.0, 110.0)
                        Imgproc.morphologyEx(structure, gradient, Imgproc.MORPH_GRADIENT, openKernel)
                        Imgproc.dilate(structure, expanded, openKernel)
                        Core.bitwise_and(canny, expanded, canny)
                        val notNuisance = Mat()
                        try {
                            Core.bitwise_not(nuisance, notNuisance)
                            Core.bitwise_and(canny, notNuisance, canny)
                        } finally {
                            notNuisance.release()
                        }
                        if (retainDominantCluster) {
                            val support = Mat()
                            try {
                                val supportKernel = kernel(Imgproc.MORPH_ELLIPSE, 9)
                                try {
                                    Imgproc.dilate(canny, support, supportKernel)
                                } finally {
                                    supportKernel.release()
                                }
                                Core.bitwise_and(gradient, support, gradient)
                            } finally {
                                support.release()
                            }
                        }
                        edges = Mat()
                        Core.bitwise_or(gradient, canny, edges)
                    } finally {
                        canny.release()
                        gradient.release()
                        expanded.release()
                    }
                    removeSmallComponents(edges, edgeMode = true)
                    Imgproc.rectangle(
                        edges,
                        Rect(0, 0, edges.cols(), edges.rows()),
                        Scalar.all(0.0),
                        border,
                    )
                } finally {
                    openKernel.release()
                }
            } finally {
                channels.forEach(Mat::release)
            }

            val result = StructureFeatures(requireNotNull(structure), requireNotNull(edges))
            structure = null
            edges = null
            return result
        } finally {
            bgr.release()
            gray.release()
            hsv.release()
            normalized.release()
            blurred.release()
            nuisance?.release()
            structure?.release()
            edges?.release()
        }
    }

    private fun toBgr(source: Mat): Mat = Mat().also { bgr ->
        when (source.channels()) {
            1 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR)
            3 -> source.copyTo(bgr)
            4 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_RGBA2BGR)
            else -> error("不支持的结构图通道数：${source.channels()}")
        }
    }

    private fun applyDynamicIgnoreRegions(mask: Mat, regions: List<Rect>) {
        regions.forEach { source ->
            if (source.width <= 0 || source.height <= 0) return@forEach
            val left = (source.x - 6).coerceIn(0, max(0, mask.cols() - 1))
            val top = (source.y - 6).coerceIn(0, max(0, mask.rows() - 1))
            val right = (source.x + source.width + 6).coerceIn(left + 1, mask.cols())
            val bottom = (source.y + source.height + 6).coerceIn(top + 1, mask.rows())
            Imgproc.rectangle(mask, Rect(left, top, right - left, bottom - top), Scalar.all(255.0), -1)
        }
    }

    private data class Component(val label: Int, val area: Int, val bounds: Rect)

    private fun retainDominantStructureCluster(binary: Mat) {
        val components = connectedComponents(binary)
        if (components.isEmpty()) return
        val minimumDominantArea = max(500, (binary.cols() * binary.rows() * .002).roundToInt())
        val dominant = components
            .filter { it.area >= minimumDominantArea && it.bounds.width >= 40 && it.bounds.height >= 40 }
            .maxByOrNull(Component::area)
        if (dominant == null) {
            binary.setTo(Scalar.all(0.0))
            return
        }
        val attachmentDistance = (min(binary.cols(), binary.rows()) / 30).coerceIn(18, 48)
        val minimumAttachedArea = max(24, (dominant.area * .001).roundToInt())
        val kept = mutableSetOf(dominant.label)
        var changed: Boolean
        do {
            changed = false
            components.forEach { component ->
                if (component.label in kept || component.area < minimumAttachedArea) return@forEach
                if (components.any { it.label in kept && rectangleDistance(it.bounds, component.bounds) <= attachmentDistance }) {
                    kept += component.label
                    changed = true
                }
            }
        } while (changed)
        retainLabels(binary, kept)
    }

    private fun removeSmallComponents(binary: Mat, edgeMode: Boolean) {
        val components = connectedComponents(binary)
        val minimumArea = max(
            if (edgeMode) 8 else 24,
            (binary.cols() * binary.rows() * if (edgeMode) .000005 else .00002).roundToInt(),
        )
        val kept = components.filter {
            it.area >= minimumArea && !(it.bounds.width < 3 && it.bounds.height < 3)
        }.mapTo(mutableSetOf(), Component::label)
        retainLabels(binary, kept)
    }

    private data class Connected(val labels: Mat, val components: List<Component>) : AutoCloseable {
        override fun close() = labels.release()
    }

    private fun connected(binary: Mat): Connected {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8, CvType.CV_32S)
            val components = (1 until count).map { label ->
                Component(
                    label,
                    stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt(),
                    Rect(
                        stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt(),
                        stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt(),
                        stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt(),
                        stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt(),
                    ),
                )
            }
            return Connected(labels, components)
        } finally {
            stats.release()
            centroids.release()
        }
    }

    private fun connectedComponents(binary: Mat): List<Component> = connected(binary).use { it.components }

    private fun retainLabels(binary: Mat, keptLabels: Set<Int>) {
        if (keptLabels.isEmpty()) {
            binary.setTo(Scalar.all(0.0))
            return
        }
        connected(binary).use { connected ->
            val kept = Mat.zeros(binary.size(), CvType.CV_8UC1)
            try {
                keptLabels.forEach { label ->
                    val component = Mat()
                    try {
                        Core.compare(connected.labels, Scalar(label.toDouble()), component, Core.CMP_EQ)
                        Core.bitwise_or(kept, component, kept)
                    } finally {
                        component.release()
                    }
                }
                kept.copyTo(binary)
            } finally {
                kept.release()
            }
        }
    }

    private fun rectangleDistance(first: Rect, second: Rect): Double {
        val horizontal = max(0, max(first.x - (second.x + second.width), second.x - (first.x + first.width)))
        val vertical = max(0, max(first.y - (second.y + second.height), second.y - (first.y + first.height)))
        return hypot(horizontal.toDouble(), vertical.toDouble())
    }

    private fun kernel(shape: Int, size: Int): Mat =
        Imgproc.getStructuringElement(shape, Size(size.toDouble(), size.toDouble()))
}
