package com.idvb.android.recognize

import com.idvb.android.recognize.side.SparseGateSearch
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
}
