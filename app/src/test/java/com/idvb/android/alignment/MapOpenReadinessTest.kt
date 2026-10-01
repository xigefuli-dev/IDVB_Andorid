package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test

class MapOpenReadinessTest {
    private fun frame(color: Int) = MapOpenReadiness.signature(IntArray(160 * 100) { color })
    @Test fun absentMapNeverPassesEvenWhenStable() {
        val game = frame(0xffa08450.toInt())
        assertFalse(MapOpenReadiness.evaluate(game, null, game).ready)
    }
    @Test fun noReferenceRequiresSecondFrameAndRejectsFade() {
        val map = frame(0xff687580.toInt())
        val dark = frame(0xff20252a.toInt())
        assertFalse(MapOpenReadiness.evaluate(map, null, null).ready)
        assertFalse(MapOpenReadiness.evaluate(map, null, dark).ready)
        assertTrue(MapOpenReadiness.evaluate(map, null, map).ready)
    }
    @Test fun referenceRejectsWrongColorAndBrightnessWithoutFallback() {
        val map = frame(0xff687580.toInt())
        val other = frame(0xffa08450.toInt())
        assertTrue(MapOpenReadiness.evaluate(map, map, null).ready)
        assertFalse(MapOpenReadiness.evaluate(map, other, map).ready)
        assertFalse(MapOpenReadiness.evaluate(frame(0xff20252a.toInt()), map, null).ready)
    }
    @Test fun signatureChecksCancellationDuringBoundedRows() {
        var rows = 0
        try {
            MapOpenReadiness.signature(IntArray(160 * 100)) { if (++rows == 3) throw java.util.concurrent.CancellationException() }
            fail("cancel expected")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(3, rows) }
    }
}
