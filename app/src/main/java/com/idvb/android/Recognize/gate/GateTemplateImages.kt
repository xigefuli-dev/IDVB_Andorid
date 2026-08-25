package com.idvb.android.recognize.gate

import android.content.Context
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

object GateTemplateImages {
    const val ASSET_PATH = "recognition/Gate.png"

    fun loadAsset(context: Context): Mat {
        OpenCvRuntime.requireAvailable()
        val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
        val encoded = Mat(1, bytes.size, CvType.CV_8UC1)
        return try {
            encoded.put(0, 0, bytes)
            val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED)
            if (decoded.empty()) {
                decoded.release()
                error("无法读取门图标资源：$ASSET_PATH")
            }
            decoded
        } finally {
            encoded.release()
        }
    }

    fun createMatchImage(source: Mat): Mat {
        require(!source.empty()) { "无法处理空图像" }
        val gray = Mat()
        try {
            when (source.channels()) {
                1 -> source.copyTo(gray)
                4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY)
                3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
                else -> error("不支持的门检测图像通道数：${source.channels()}")
            }
            return gray
        } catch (error: Throwable) {
            gray.release()
            throw error
        }
    }

    fun createEdges(source: Mat): Mat {
        val gray = createMatchImage(source)
        val blurred = Mat()
        val edges = Mat()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(
                blurred,
                edges,
                GateTemplateRules.CANNY_LOW_THRESHOLD,
                GateTemplateRules.CANNY_HIGH_THRESHOLD,
            )
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, closeKernel)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_OPEN, openKernel)
            removeSmallComponents(edges, minimumArea = 12)
            return edges
        } catch (error: Throwable) {
            edges.release()
            throw error
        } finally {
            gray.release()
            blurred.release()
            closeKernel.release()
            openKernel.release()
        }
    }

    private fun removeSmallComponents(edges: Mat, minimumArea: Int) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(
                edges,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S,
            )
            if (count <= 1) return
            val kept = Mat.zeros(edges.size(), CvType.CV_8UC1)
            try {
                for (label in 1 until count) {
                    val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                    if (area < minimumArea) continue
                    val component = Mat()
                    try {
                        Core.compare(labels, Scalar(label.toDouble()), component, Core.CMP_EQ)
                        Core.bitwise_or(kept, component, kept)
                    } finally {
                        component.release()
                    }
                }
                kept.copyTo(edges)
            } finally {
                kept.release()
            }
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }
}
