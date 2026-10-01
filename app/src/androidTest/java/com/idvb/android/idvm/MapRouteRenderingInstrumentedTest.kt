package com.idvb.android.idvm

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.graphics.MapRouteRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MapRouteRenderingInstrumentedTest {
    private fun line() = MapAnnotation(UUID.randomUUID().toString(), "line", color = "#FF0000",
        start = NormalizedPoint(.25, .5), end = NormalizedPoint(.75, .5))

    @Test fun routesFollowSourceCropAndThicknessWithoutChangingSource() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val counts = (0..3).map { level ->
            val result = MapRouteRenderer.render(source, listOf(line()), NormalizedRect(.25, .25, .5, .5),
                200, 200, emptyList(), level)
            assertEquals(Color.RED, result.getPixel(50, 50))
            assertEquals(Color.TRANSPARENT, source.getPixel(50, 50))
            val pixels = IntArray(10000)
            result.getPixels(pixels, 0, 100, 0, 0, 100, 100)
            result.recycle()
            pixels.count { Color.alpha(it) > 0 }
        }
        assertTrue(counts.zipWithNext().all { (a, b) -> b > a })
        source.recycle()
    }

    @Test fun routesRespectFreeCropAndLegacyColors() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val polygon = listOf(NormalizedPoint(0.0, 0.0), NormalizedPoint(.5, 0.0),
            NormalizedPoint(.5, 1.0), NormalizedPoint(0.0, 1.0))
        val result = MapRouteRenderer.render(source, listOf(line().copy(color = null, colorIndex = 5)),
            null, 100, 100, polygon, 1)
        assertEquals(Color.rgb(0, 122, 255), result.getPixel(40, 50))
        assertEquals(Color.TRANSPARENT, result.getPixel(65, 50))
        result.recycle()
        source.recycle()
    }
}
