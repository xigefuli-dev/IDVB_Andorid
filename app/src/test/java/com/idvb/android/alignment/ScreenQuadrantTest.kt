package com.idvb.android.alignment

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenQuadrantTest {
    @Test
    fun quadrantCoordinatesMapping() {
        val w = 2400
        val h = 1080

        // Center: (1200, 540)
        // Top-left -> Second quadrant
        assertEquals(ScreenQuadrant.SECOND, ScreenQuadrant.fromCoordinates(100f, 100f, w, h))
        assertEquals(ScreenQuadrant.SECOND, ScreenQuadrant.fromCoordinates(1199f, 539f, w, h))

        // Top-right -> First quadrant
        assertEquals(ScreenQuadrant.FIRST, ScreenQuadrant.fromCoordinates(1200f, 100f, w, h))
        assertEquals(ScreenQuadrant.FIRST, ScreenQuadrant.fromCoordinates(2300f, 100f, w, h))

        // Bottom-left -> Third quadrant
        assertEquals(ScreenQuadrant.THIRD, ScreenQuadrant.fromCoordinates(100f, 540f, w, h))
        assertEquals(ScreenQuadrant.THIRD, ScreenQuadrant.fromCoordinates(500f, 900f, w, h))

        // Bottom-right -> Fourth quadrant
        assertEquals(ScreenQuadrant.FOURTH, ScreenQuadrant.fromCoordinates(1200f, 540f, w, h))
        assertEquals(ScreenQuadrant.FOURTH, ScreenQuadrant.fromCoordinates(2000f, 900f, w, h))

        // Out of bounds or invalid
        assertEquals(ScreenQuadrant.UNKNOWN, ScreenQuadrant.fromCoordinates(-1f, 100f, w, h))
        assertEquals(ScreenQuadrant.UNKNOWN, ScreenQuadrant.fromCoordinates(100f, -1f, w, h))
        assertEquals(ScreenQuadrant.UNKNOWN, ScreenQuadrant.fromCoordinates(2500f, 100f, w, h))
        assertEquals(ScreenQuadrant.UNKNOWN, ScreenQuadrant.fromCoordinates(Float.NaN, 100f, w, h))
        assertEquals(ScreenQuadrant.UNKNOWN, ScreenQuadrant.fromCoordinates(100f, 100f, 0, h))
    }
}
