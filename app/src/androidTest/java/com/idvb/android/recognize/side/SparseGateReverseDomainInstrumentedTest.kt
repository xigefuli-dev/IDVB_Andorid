package com.idvb.android.recognize.side

import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

class SparseGateReverseDomainInstrumentedTest {
    @Test fun boundedRowsPreserveTheFullReferenceTraversalAndSamplingAtRoundingBoundaries() {
        assertTrue(OpenCvRuntime.initialize())
        val edges=Mat.zeros(80,96,CvType.CV_8UC1)
        val domain=Mat.zeros(80,96,CvType.CV_8UC1)
        Imgproc.rectangle(edges,Point(7.0,12.0),Point(86.0,67.0),Scalar.all(255.0),1)
        Imgproc.rectangle(domain,Point(4.0,16.0),Point(90.0,60.0),Scalar.all(255.0),-1)
        VpsgLiveExtractor.Observation(edges,domain.clone(),edges.clone(),domain).use { live ->
            val reverse=SparseGateSearch.ReverseObservation(live)
            val positions=IntArray(160*180) { it }
            val excluded=ByteArray(positions.size) { if (it%19==0) 255.toByte() else 0 }
            val index=SparseGateSearch.Index(160,180,ByteArray(positions.size),excluded,positions)
            for (scale in listOf(.4,.75,1.0,2.4)) for (y in listOf(-240.0,-16.5000001,-16.5,-16.4999999,0.0,16.5,90.0)) {
                val pose=SparseGateSearch.Pose(scale,-4.5,y)
                val eligible=positions.filter { position ->
                    val rx=position%index.width; val ry=position/index.width
                    val x=(rx*scale+pose.x).roundToInt(); val ly=(ry*scale+pose.y).roundToInt()
                    index.observable(rx.toDouble(),ry.toDouble()) && x in 0 until reverse.width && ly in 0 until reverse.height &&
                        (reverse.domain[ly*reverse.width+x].toInt() and 255)>128
                }
                val tested=minOf(2048,eligible.size)
                val hits=(0 until tested).count { i ->
                    val position=eligible[i*eligible.size/tested]
                    reverse.distance(position%index.width*scale+pose.x,position/index.width*scale+pose.y)<=5.5
                }
                val actual=SparseGateSearch.verify(index,pose,emptyList(),emptyList(),96,80,reverse=reverse)
                assertEquals("$pose",eligible.size,actual.reverseEligiblePoints)
                assertEquals(tested,actual.reversePoints)
                assertEquals(if (tested==0) 0.0 else hits.toDouble()/tested,actual.reverseSupport,0.0)
            }
            domain.setTo(Scalar.all(0.0))
            val empty=SparseGateSearch.ReverseObservation(live)
            assertEquals(0,SparseGateSearch.verify(index,SparseGateSearch.Pose(1.0,0.0,0.0),emptyList(),emptyList(),96,80,reverse=empty).reversePoints)
        }
    }
}
