package com.idvb.android.graphics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.idvb.android.idvm.NormalizedRect
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Directly decodes the Desktop recognition/display region.  Decoding the full
 * source first made small regions both blurry and memory hungry on Android.
 */
fun decodeMapRegion(file: File, region: NormalizedRect?, targetLongestSide: Int): Bitmap? {
    if (!file.isFile) return null
    val target = targetLongestSide.coerceAtLeast(1)
    val normalized = region.validOrFull()

    val direct = runCatching<Bitmap?> {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return@runCatching null
        try {
            val sourceRect = normalized.toPixelRect(decoder.width, decoder.height)
            var sample = 1
            val longest = max(sourceRect.width(), sourceRect.height())
            while (longest / (sample * 2) >= target) sample *= 2
            decoder.decodeRegion(sourceRect, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            @Suppress("DEPRECATION")
            decoder.recycle()
        }
    }.getOrNull()
    if (direct != null) return direct

    // A few old platform codecs cannot region-decode particular PNG files.
    return runCatching<Bitmap?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val originalRect = normalized.toPixelRect(bounds.outWidth, bounds.outHeight)
        var sample = 1
        val longest = max(originalRect.width(), originalRect.height())
        while (longest / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@runCatching null
        val sampledRect = normalized.toPixelRect(decoded.width, decoded.height)
        if (sampledRect.left == 0 && sampledRect.top == 0 &&
            sampledRect.right == decoded.width && sampledRect.bottom == decoded.height
        ) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                sampledRect.left,
                sampledRect.top,
                sampledRect.width(),
                sampledRect.height(),
            ).also { decoded.recycle() }
        }
    }.getOrNull()
}

private fun NormalizedRect?.validOrFull(): NormalizedRect {
    val value = this ?: return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    if (value.width <= 0.0 || value.height <= 0.0 || value.x >= 1.0 || value.y >= 1.0) {
        return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    }
    val left = value.x.coerceIn(0.0, 1.0)
    val top = value.y.coerceIn(0.0, 1.0)
    val right = (value.x + value.width).coerceIn(left, 1.0)
    val bottom = (value.y + value.height).coerceIn(top, 1.0)
    if (right <= left || bottom <= top) return NormalizedRect(0.0, 0.0, 1.0, 1.0)
    return NormalizedRect(left, top, right - left, bottom - top)
}

private fun NormalizedRect.toPixelRect(width: Int, height: Int): Rect {
    val left = floor(x * width).toInt().coerceIn(0, width - 1)
    val top = floor(y * height).toInt().coerceIn(0, height - 1)
    val right = ceil((x + this.width) * width).toInt().coerceIn(left + 1, width)
    val bottom = ceil((y + this.height) * height).toInt().coerceIn(top + 1, height)
    return Rect(left, top, right, bottom)
}
