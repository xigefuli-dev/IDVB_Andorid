package com.idvb.android.recognize.cv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenCvRuntimeInstrumentedTest {
    @Test
    fun nativeRuntimeLoadsAndConvertsBitmapToGray() {
        assertTrue(OpenCvRuntime.initialize())
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 100, 220))
        }
        val gray = CvImages.bitmapToGray(bitmap)
        try {
            assertEquals(12, gray.cols())
            assertEquals(8, gray.rows())
            assertEquals(1, gray.channels())
            assertTrue(gray.get(0, 0)[0] > 0.0)
        } finally {
            gray.release()
            bitmap.recycle()
        }
    }
}
