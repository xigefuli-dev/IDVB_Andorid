package com.idvb.android.feedback

import org.junit.Assert.*
import org.junit.Test

class FeedbackTextTest {
    @Test fun matchesDesktopWeightedBoundaries() {
        assertFalse(FeedbackText.isValid(" 一二三四五 "))
        assertTrue(FeedbackText.isValid(" 一二三四五六 "))
        assertFalse(FeedbackText.isValid("1234567890"))
        assertTrue(FeedbackText.isValid("12345678901"))
        assertEquals(3, FeedbackText.weightedLength("𠀀😀"))
        assertEquals(6, FeedbackText.weightedLength("，—“"))
        assertFalse(FeedbackText.isValid("a".repeat(4001)))
        assertTrue(FeedbackText.isValid("a".repeat(4000)))
    }
}
