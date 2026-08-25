package com.idvb.android.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectionPolicyTest {
    @Test
    fun reliableCandidateSelectsOnFirstTap() {
        val decision = CandidateSelectionPolicy.onTap(CandidateDisposition.RELIABLE, 2, null)
        assertTrue(decision.select)
        assertNull(decision.armedIndex)
    }

    @Test
    fun unverifiedCandidateRequiresTwoTapsOnSameCard() {
        val first = CandidateSelectionPolicy.onTap(CandidateDisposition.NEEDS_VERIFICATION, 3, null)
        assertFalse(first.select)
        assertEquals(3, first.armedIndex)
        val second = CandidateSelectionPolicy.onTap(CandidateDisposition.NEEDS_VERIFICATION, 3, first.armedIndex)
        assertTrue(second.select)
        assertNull(second.armedIndex)
    }

    @Test
    fun tappingAnotherManualCardMovesConfirmationArm() {
        val decision = CandidateSelectionPolicy.onTap(CandidateDisposition.CATALOG_ONLY, 5, 3)
        assertFalse(decision.select)
        assertEquals(5, decision.armedIndex)
    }
}
