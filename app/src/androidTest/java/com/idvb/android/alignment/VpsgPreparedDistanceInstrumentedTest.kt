package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.vpsg.VpsgNativeKernel
import com.idvb.android.recognize.vpsg.VpsgPreparedIndex
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

class VpsgPreparedDistanceInstrumentedTest {
    @Test fun existingLazyStorageMatchesEveryOriginalDistanceValueAndSurvivesSourceDeletion() {
        assertTrue(OpenCvRuntime.initialize())
        assertTrue(VpsgNativeKernel.available)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"distance-fixture-${UUID.randomUUID()}.png")
        val source=Mat.zeros(360,420,CvType.CV_8UC1)
        val inverse=Mat(); val expected=Mat()
        try {
            Imgproc.rectangle(source,Point(36.0,24.0),Point(271.0,195.0),Scalar.all(255.0),2)
            Imgproc.line(source,Point(53.0,309.0),Point(354.0,182.0),Scalar.all(255.0),1)
            assertTrue(Imgcodecs.imwrite(file.path,source))
            Core.bitwise_not(source,inverse)
            Imgproc.distanceTransform(inverse,expected,Imgproc.DIST_L2,Imgproc.DIST_MASK_PRECISE)
            val index=VpsgPreparedIndex.load(file,"private-distance-fixture")
            assertFalse("Proposal/reverse-only preparation must not build unused distance",index.nativeDistanceReady)
            val budgetedBytes=index.bytes
            assertTrue(file.delete())
            val actual=index.nativeDistance.asFloatBuffer()
            val values=FloatArray(source.cols()*source.rows()).also { expected.get(0,0,it) }
            for (i in values.indices) assertEquals("pixel=$i",values[i].toBits(),actual.get(i).toBits())
            assertTrue(index.nativeDistanceReady)
            assertSame(index.nativeDistance,index.nativeDistance)
            assertEquals("LRU must budget the full buffer before initialization",budgetedBytes,index.bytes)
        } finally { source.release(); inverse.release(); expected.release(); file.delete() }
    }
}
