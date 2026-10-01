package com.idvb.android.alignment

import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.vpsg.VpsgObservationIndex
import com.idvb.android.recognize.vpsg.VpsgPreparedIndex
import com.idvb.android.recognize.vpsg.VpsgFastSolver
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class VpsgObservationIndexInstrumentedTest {
    @Test fun nativeEnumerationPreservesBothScalarSamplesAndEveryProjectionBin() {
        // Initializes the same bundled OpenCV runtime as production recognition.
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        CvImages.bitmapToBgr(bitmap).release(); bitmap.recycle()
        val random = Random(79231)
        for (width in listOf(63, 160, 649)) for (density in listOf(0, 1, 17, 100)) {
            val height = 520
            val bytes = ByteArray(width * height) { if (random.nextInt(100) < density) 255.toByte() else 0 }
            val mat = Mat(height, width, CvType.CV_8UC1)
            try {
                mat.put(0, 0, bytes)
                val index = VpsgObservationIndex(mat)
                assertEquals(VpsgPreparedIndex.sample(bytes, width), index.sample(150))
                val positions = bytes.indices.filter { (bytes[it].toInt() and 255) > 128 }
                val step = maxOf(1, (positions.size + 2047) / 2048)
                val expected = positions.filterIndexed { i, _ -> i % step == 0 }.take(2048)
                    .map { VpsgFastSolver.Point(it % width, it / width) }
                assertEquals(expected, index.sample(2048, integerStride = true))
                val (x, y) = index.projections()
                assertArrayEquals(VpsgPreparedIndex.projection(bytes, width, height), x, 0.0)
                val expectedY = DoubleArray(height)
                positions.forEach { expectedY[it / width]++ }
                assertArrayEquals(expectedY, y, 0.0)
            } finally { mat.release() }
        }
    }
}
