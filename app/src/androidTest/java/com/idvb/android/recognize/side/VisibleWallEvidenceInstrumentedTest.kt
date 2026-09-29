package com.idvb.android.recognize.side

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class VisibleWallEvidenceInstrumentedTest {
    @Test fun fogCutIsNotAWallButVisibleStraightWallRemains() {
        assertTrue(OpenCvRuntime.initialize())
        val color = Mat.zeros(260,340,CvType.CV_8UC3)
        val explored = Mat.zeros(260,340,CvType.CV_8UC1)
        val frame = Mat()
        try {
            Imgproc.rectangle(color,Point(30.0,20.0),Point(300.0,240.0),Scalar(100.0,120.0,150.0),-1)
            Imgproc.rectangle(color,Point(30.0,20.0),Point(300.0,240.0),Scalar(150.0,170.0,190.0),2)
            Imgproc.circle(explored,Point(100.0,100.0),130,Scalar.all(255.0),-1)
            color.copyTo(frame,explored)
            VpsgLiveExtractor.extract(frame).use { observation ->
                val removed = VisibleWallEvidence.retainPhotometricWalls(frame,observation)
                assertTrue("Must reject unsupported fog boundary",removed > 20)
                val fogArc = observation.edges.submat(Rect(211,157,9,7))
                val wall = observation.edges.submat(Rect(26,70,9,50))
                try {
                    assertEquals("Fog cut through filled room is unknown",0,Core.countNonZero(fogArc))
                    assertTrue("Real straight wall must remain",Core.countNonZero(wall) > 30)
                } finally { fogArc.release(); wall.release() }
            }
        } finally { color.release(); explored.release(); frame.release() }
    }
}
