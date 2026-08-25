package com.idvb.android.recognize.side

import com.idvb.android.idvm.NormalizedRect
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class GeneratedSideEntranceFeature(
    val feature: Mat,
    val centerX: Double,
    val centerY: Double,
    val radius: Int,
    val width: Int,
    val height: Int,
)

object SideEntranceFeaturePreprocessor {
    const val ALGORITHM_VERSION = "3-ratio-gate-masked"

    fun process(
        recognitionImage: Mat,
        anchorBounds: NormalizedRect,
        featureRegionRatio: Double,
        clampToBounds: Boolean,
    ): GeneratedSideEntranceFeature {
        require(!recognitionImage.empty()) { "识别图为空" }
        require(featureRegionRatio.isFinite() && featureRegionRatio > 0.0 && featureRegionRatio <= 1.0) {
            "侧门特征比例无效"
        }
        val width = max(1, roundedInt(recognitionImage.cols() * featureRegionRatio))
        val height = max(1, roundedInt(recognitionImage.rows() * featureRegionRatio))
        return processCore(recognitionImage, anchorBounds, width, height, clampToBounds)
    }

    private fun processCore(
        recognitionImage: Mat,
        anchor: NormalizedRect,
        featureWidth: Int,
        featureHeight: Int,
        clampToBounds: Boolean,
    ): GeneratedSideEntranceFeature {
        val imageWidth = recognitionImage.cols()
        val imageHeight = recognitionImage.rows()
        var centerX = (anchor.x + anchor.width / 2.0) * imageWidth
        var centerY = (anchor.y + anchor.height / 2.0) * imageHeight
        if (clampToBounds) {
            centerX = clampCenter(centerX, featureWidth / 2.0, imageWidth)
            centerY = clampCenter(centerY, featureHeight / 2.0, imageHeight)
        }
        val left = roundedInt(centerX - featureWidth / 2.0)
        val top = roundedInt(centerY - featureHeight / 2.0)
        val gray = toGray(recognitionImage)
        try {
            val requested = Rect(left, top, featureWidth, featureHeight)
            val clippedBounds = intersect(requested, Rect(0, 0, imageWidth, imageHeight))
            val clipped = if (clippedBounds.width > 0 && clippedBounds.height > 0) {
                gray.submat(clippedBounds).clone()
            } else Mat()
            var feature = Mat()
            try {
                val fill = Core.mean(gray).`val`[0]
                Core.copyMakeBorder(
                    clipped,
                    feature,
                    max(0, -requested.y),
                    max(0, requested.y + requested.height - imageHeight),
                    max(0, -requested.x),
                    max(0, requested.x + requested.width - imageWidth),
                    Core.BORDER_CONSTANT,
                    Scalar(fill),
                )
                if (feature.cols() != featureWidth || feature.rows() != featureHeight) {
                    val normalizedBounds = Rect(
                        max(0, (feature.cols() - featureWidth) / 2),
                        max(0, (feature.rows() - featureHeight) / 2),
                        min(featureWidth, feature.cols()),
                        min(featureHeight, feature.rows()),
                    )
                    val normalized = feature.submat(normalizedBounds).clone()
                    feature.release()
                    feature = Mat()
                    try {
                        Core.copyMakeBorder(
                            normalized,
                            feature,
                            0,
                            max(0, featureHeight - normalized.rows()),
                            0,
                            max(0, featureWidth - normalized.cols()),
                            Core.BORDER_CONSTANT,
                            Scalar(fill),
                        )
                    } finally {
                        normalized.release()
                    }
                }

                val anchorLeft = floor(anchor.x * imageWidth).toInt() - left
                val anchorTop = floor(anchor.y * imageHeight).toInt() - top
                val anchorWidth = ceil(anchor.width * imageWidth).toInt()
                val anchorHeight = ceil(anchor.height * imageHeight).toInt()
                val iconX = anchorLeft.coerceIn(0, max(0, feature.cols() - 1))
                val iconY = anchorTop.coerceIn(0, max(0, feature.rows() - 1))
                val iconWidth = anchorWidth.coerceIn(1, max(1, feature.cols() - iconX))
                val iconHeight = anchorHeight.coerceIn(1, max(1, feature.rows() - iconY))
                if (iconWidth > 0 && iconHeight > 0) {
                    Imgproc.rectangle(feature, Rect(iconX, iconY, iconWidth, iconHeight), Scalar(Core.mean(feature).`val`[0]), -1)
                }
                return GeneratedSideEntranceFeature(
                    feature = feature,
                    centerX = centerX,
                    centerY = centerY,
                    radius = ceil(max(featureWidth, featureHeight) / 2.0).toInt(),
                    width = featureWidth,
                    height = featureHeight,
                )
            } catch (error: Throwable) {
                feature.release()
                throw error
            } finally {
                clipped.release()
            }
        } finally {
            gray.release()
        }
    }

    private fun toGray(source: Mat): Mat {
        val gray = Mat()
        when (source.channels()) {
            1 -> source.copyTo(gray)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY)
            else -> {
                gray.release()
                error("不支持的识别图通道数：${source.channels()}")
            }
        }
        return gray
    }

    private fun clampCenter(center: Double, halfExtent: Double, dimension: Int): Double =
        if (halfExtent * 2.0 >= dimension) dimension / 2.0 else center.coerceIn(halfExtent, dimension - halfExtent)

    private fun intersect(left: Rect, right: Rect): Rect {
        val x1 = max(left.x, right.x)
        val y1 = max(left.y, right.y)
        val x2 = min(left.x + left.width, right.x + right.width)
        val y2 = min(left.y + left.height, right.y + right.height)
        return Rect(x1, y1, max(0, x2 - x1), max(0, y2 - y1))
    }

    private fun roundedInt(value: Double): Int = round(value).toInt()
}
