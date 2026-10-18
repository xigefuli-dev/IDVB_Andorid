package com.idvb.android.recognize

import com.idvb.android.recognize.side.SparseGateSearch
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import org.junit.Assert.*
import org.junit.Test

class SparseGateScoreBoundTest {
    @Test fun binaryExclusionPreservesEveryPixelAndReducesRetainedBytes() {
        val mask = ByteArray(64*65) { if (it % 17 == 0 || it == 63 || it == 64) 255.toByte() else 0 }
        val index = SparseGateSearch.Index(64,65,ByteArray(mask.size),mask)
        for (i in mask.indices) assertEquals(mask[i].toInt() == 0,index.observable((i%64).toDouble(),(i/64).toDouble()))
        assertTrue(index.observable(-1.0,0.0))
        assertTrue(index.observable(64.0,65.0))
        assertEquals(mask.size+((mask.size+63)/64)*8,index.bytes)
    }
    @Test fun pruningNeverDropsATransformThatCanBeatTheExistingBasin() {
        val index = SparseGateSearch.Index(64, 64, ByteArray(4096) { ((it % 64) * 3).coerceAtMost(255).toByte() })
        val points = List(128) { SparseGateSearch.Pixel(8 + it % 40, 8 + it / 4 % 40) }
        for (scale in listOf(.4, .88, 1.0, 1.5, 2.4)) for (x in listOf(-25.0, 0.0, 24.0)) {
            val exact = index.score(points, scale, x, 2.0)
            for (bound in listOf(0.0, .25, .5, .75, 1.0)) {
                val scored = index.score(points, scale, x, 2.0, bound)
                if (exact >= bound) assertEquals(exact, scored, 0.0)
                else assertTrue(scored == exact || scored == Double.NEGATIVE_INFINITY)
            }
        }
    }
    @Test fun recordedSearchBoundsContainFullScoresAndPreserveSelectedBasins() {
        val index = SparseGateSearch.Index(64,64,ByteArray(4096) { ((it%64)*3).coerceAtMost(255).toByte() })
        val points = List(128) { SparseGateSearch.Pixel(8+it%40,8+it/4%40) }
        val events = ArrayList<AlignmentLogEvent>()
        val recorded = SparseGateSearch.search(index,points,32.0,32.0,32.0,32.0,.4,2.4,0,AlignmentLogSink { events += it })
        assertEquals(SparseGateSearch.search(index,points,32.0,32.0,32.0,32.0,.4,2.4,0),recorded)
        val event = events.single()
        var pruned = 0
        for (name in listOf("coarse","refined")) for (row in event.series.getValue(name).chunked(8)) {
            val exact = index.score(points,row[1],row[2],row[3])
            assertTrue("$exact outside $row",exact >= row[4]-1e-12 && exact <= row[5]+1e-12)
            if (row[6] < points.size) {
                pruned++
                assertTrue(row[5] < row[7])
            } else assertEquals(exact,row[4],0.0)
        }
        assertTrue(pruned > 0)
        assertTrue(event.measurements.getValue("scoredPoints") < event.measurements.getValue("unprunedPoints"))
    }
    @Test fun incumbentBoundsRetainEveryForwardPoseThatCanImproveTheWinner() {
        val index = SparseGateSearch.Index(64,64,ByteArray(4096) { if (it%64<3) 160.toByte() else 8 })
        val points = List(128) { SparseGateSearch.Pixel(8+it%40,8+it/4%40) }
        val contours = listOf(listOf(SparseGateSearch.Pixel(8,8),SparseGateSearch.Pixel(48,8),
            SparseGateSearch.Pixel(48,48),SparseGateSearch.Pixel(8,48)))
        for (x in listOf(-8.0,0.0,8.0,12.0)) for (support in listOf(.88,.95,1.0)) for (mean in listOf(0.0,.5,1.0)) {
            val pose = SparseGateSearch.Pose(1.0,x,0.0)
            val full = SparseGateSearch.verify(index,pose,points,contours,64,64,acceptanceOnly=true)
            val incumbent = SparseGateSearch.Evidence(support,mean,0.0,false,128,4)
            val bounded = SparseGateSearch.verify(index,pose,points,contours,64,64,acceptanceOnly=true,competitive=incumbent)
            val improves = full.supported && (full.support>support || full.support==support && full.mean<mean)
            assertEquals("pose=$pose incumbent=$incumbent full=$full bounded=$bounded",improves,bounded.supported)
            if (improves) assertEquals(full,bounded)
        }
    }
}
