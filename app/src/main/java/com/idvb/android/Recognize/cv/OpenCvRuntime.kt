package com.idvb.android.recognize.cv

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** OpenCV native runtime 的单一初始化与状态入口。 */
object OpenCvRuntime {
    private enum class State { UNINITIALIZED, AVAILABLE, FAILED }

    @Volatile private var state = State.UNINITIALIZED
    @Volatile private var failure: Throwable? = null

    @Synchronized
    fun initialize(): Boolean {
        if (state == State.AVAILABLE) return true
        if (state == State.FAILED) return false
        val loaded = runCatching { OpenCVLoader.initLocal() }
        if (loaded.getOrDefault(false)) {
            state = State.AVAILABLE
            return true
        }
        failure = loaded.exceptionOrNull()
            ?: IllegalStateException("OpenCV ${OpenCVLoader.OPENCV_VERSION} native library failed to load")
        state = State.FAILED
        return false
    }

    val available: Boolean get() = state == State.AVAILABLE || initialize()

    val version: String
        get() = if (available) Core.VERSION else OpenCVLoader.OPENCV_VERSION

    fun requireAvailable() {
        check(available) { "OpenCV 初始化失败：${failure?.message ?: "unknown error"}" }
    }
}

/** Bitmap/磁盘识别图转 Mat 的唯一入口，确保失败路径也释放 native 内存。 */
object CvImages {
    fun bitmapToRgba(source: Bitmap): Mat {
        OpenCvRuntime.requireAvailable()
        require(!source.isRecycled) { "Bitmap 已释放" }
        val safeBitmap = if (source.config == Bitmap.Config.HARDWARE) {
            requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) { "无法复制硬件 Bitmap" }
        } else source
        val result = Mat()
        try {
            Utils.bitmapToMat(safeBitmap, result, false)
            check(!result.empty()) { "Bitmap 转换得到空 Mat" }
            return result
        } catch (error: Throwable) {
            result.release()
            throw error
        } finally {
            if (safeBitmap !== source) safeBitmap.recycle()
        }
    }

    fun bitmapToGray(source: Bitmap): Mat {
        val rgba = bitmapToRgba(source)
        val gray = Mat()
        try {
            when (rgba.channels()) {
                1 -> rgba.copyTo(gray)
                3 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_BGR2GRAY)
                4 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> error("不支持的 Bitmap Mat 通道数：${rgba.channels()}")
            }
            check(!gray.empty()) { "灰度转换得到空 Mat" }
            return gray
        } catch (error: Throwable) {
            gray.release()
            throw error
        } finally {
            rgba.release()
        }
    }

    fun bitmapToBgr(source: Bitmap): Mat {
        val rgba = bitmapToRgba(source)
        val bgr = Mat()
        try {
            when (rgba.channels()) {
                1 -> Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_GRAY2BGR)
                3 -> rgba.copyTo(bgr)
                4 -> Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                else -> error("不支持的 Bitmap Mat 通道数：${rgba.channels()}")
            }
            check(!bgr.empty()) { "BGR 转换得到空 Mat" }
            return bgr
        } catch (error: Throwable) {
            bgr.release()
            throw error
        } finally {
            rgba.release()
        }
    }

    fun loadGray(file: File): Mat {
        OpenCvRuntime.requireAvailable()
        require(file.isFile) { "识别图片不存在：${file.path}" }
        val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        if (mat.empty()) {
            mat.release()
            error("OpenCV 无法读取识别图片：${file.path}")
        }
        return mat
    }

    fun loadColor(file: File): Mat {
        OpenCvRuntime.requireAvailable()
        require(file.isFile) { "识别图片不存在：${file.path}" }
        val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (mat.empty()) {
            mat.release()
            error("OpenCV 无法读取识别图片：${file.path}")
        }
        return mat
    }
}

inline fun <T> Mat.useReleased(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
