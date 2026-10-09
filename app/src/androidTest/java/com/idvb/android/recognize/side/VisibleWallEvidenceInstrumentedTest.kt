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
    @Test fun optionalSourceAppearanceDoesNotBecomeMissingWallEvidence() {
        assertTrue(OpenCvRuntime.initialize())
        val required = Mat.zeros(220, 300, CvType.CV_8UC1)
        Imgproc.rectangle(required, Point(20.0, 20.0), Point(280.0, 200.0), Scalar.all(255.0), 1)
        val appearance = required.clone()
        for (x in 45..255 step 20)
            Imgproc.line(appearance, Point(x.toDouble(), 40.0), Point(x.toDouble(), 180.0), Scalar.all(255.0), 1)
        val visible = Mat(220, 300, CvType.CV_8UC1, Scalar.all(255.0))
        try {
            VpsgLiveExtractor.Observation(required.clone(), visible.clone(), required.clone(), visible.clone()).use { live ->
                val points = SparseGateSearch.pixels(live.edges)
                val contours = SparseGateSearch.contours(live.edges)
                val reverse = SparseGateSearch.ReverseObservation(live)
                val pose = SparseGateSearch.Pose(1.0, 0.0, 0.0)
                val conflated = SparseGateSearch.verify(SparseGateSearch.build(appearance), pose, points, contours, 300, 220, reverse = reverse)
                val separate = SparseGateSearch.build(appearance, requiredLine = required)
                val measured = SparseGateSearch.verify(separate, pose, points, contours, 300, 220, reverse = reverse)
                assertEquals("visible-reference-conflict", conflated.rejectionCode)
                assertTrue(measured.toString(), measured.supported)
                assertEquals(0.0, separate.distance(45.0, 100.0, 1.0), .01)
                assertEquals(Core.countNonZero(required), separate.edgePositions.size)
            }
        } finally { required.release(); appearance.release(); visible.release() }
    }

    @Test fun fogCutIsNotAWallButVisibleStraightWallRemains() {
        assertTrue(OpenCvRuntime.initialize())
        val color = Mat.zeros(260,340,CvType.CV_8UC3)
        val explored = Mat.zeros(260,340,CvType.CV_8UC1)
        val frame = Mat()
        val softened = Mat(); val alpha = Mat(); val alphaBgr = Mat(); val floatColor = Mat()
        try {
            Imgproc.rectangle(color,Point(30.0,20.0),Point(300.0,240.0),Scalar(100.0,120.0,150.0),-1)
            Imgproc.rectangle(color,Point(30.0,20.0),Point(300.0,240.0),Scalar(150.0,170.0,190.0),2)
            Imgproc.circle(explored,Point(100.0,100.0),130,Scalar.all(255.0),-1)
            // Real reveal edges fade across pixels, unlike an opaque rectangular crop.
            Imgproc.GaussianBlur(explored,softened,Size(31.0,31.0),3.0)
            softened.convertTo(alpha,CvType.CV_32FC1,1.0/255.0)
            Imgproc.cvtColor(alpha,alphaBgr,Imgproc.COLOR_GRAY2BGR)
            color.convertTo(floatColor,CvType.CV_32FC3)
            Core.multiply(floatColor,alphaBgr,floatColor)
            floatColor.convertTo(frame,CvType.CV_8UC3)
            VpsgLiveExtractor.extract(frame,visibilityScopedReverse=true).use { observation ->
                val fogArc = observation.edges.submat(Rect(211,157,9,7))
                val fogValid = observation.valid.submat(Rect(211,157,9,7))
                val fogRevealed = requireNotNull(observation.revealed).submat(Rect(211,157,9,7))
                val wall = observation.edges.submat(Rect(26,70,9,50))
                try {
                    assertEquals("Soft reveal boundary must not be wall evidence",0,Core.countNonZero(fogArc))
                    assertEquals("Uncertain semantic boundary is not valid geometry",0,Core.countNonZero(fogValid))
                    assertEquals("Unknown reveal boundary must not reject reference walls",0,Core.countNonZero(fogRevealed))
                    assertTrue("Real straight wall must remain",Core.countNonZero(wall) > 30)
                } finally { fogArc.release(); fogValid.release(); fogRevealed.release(); wall.release() }
            }
        } finally {
            color.release(); explored.release(); frame.release()
            softened.release(); alpha.release(); alphaBgr.release(); floatColor.release()
        }
    }
}
