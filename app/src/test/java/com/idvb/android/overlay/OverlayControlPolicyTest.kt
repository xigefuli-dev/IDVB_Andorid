package com.idvb.android.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayControlPolicyTest {
    @Test fun automaticScanChoiceCanReopenUntilSelectionCommits() {
        val empty = OverlayControlPolicy(true, false, false)
        assertFalse(empty.eyeEnabled)
        assertFalse(empty.floorEnabled)
        val pending = empty.copy(candidatesPending = true)
        assertTrue(pending.eyeEnabled)
        assertTrue(pending.retainDismissedCandidates)
        assertFalse(pending.floorEnabled)
        // Manual selection and reliable scan confirmation both commit a map.
        val selected = pending.copy(mapSelected = true, candidatesPending = false)
        assertTrue(selected.eyeEnabled)
        assertTrue(selected.floorEnabled)
        assertFalse(selected.retainDismissedCandidates)
    }

    @Test fun ambiguousRescanOverridesRetainedMapAndConfirmationRestoresManualControls() {
        val selected = OverlayControlPolicy(true, true, false)
        assertTrue(selected.eyeEnabled)
        val ambiguous = selected.copy(candidatesPending = true)
        assertTrue(ambiguous.eyeEnabled)
        assertTrue(ambiguous.retainDismissedCandidates)
        assertFalse(ambiguous.floorEnabled)
        assertTrue(ambiguous.copy(candidatesPending = false).eyeEnabled)
    }

    @Test fun traditionalControlsAndDismissalRemainManual() {
        val automatic = OverlayControlPolicy(true, true, false)
        val traditional = automatic.copy(automaticMapOpen = false)
        assertTrue(traditional.eyeEnabled)
        assertTrue(traditional.floorEnabled)
        assertFalse(traditional.copy(candidatesPending = true).retainDismissedCandidates)
        assertFalse(traditional.copy(mapSelected = false).eyeEnabled)
        assertFalse(traditional.copy(mapSelected = false).floorEnabled)
        assertTrue(traditional.copy(mapSelected = false, candidatesPending = true).eyeEnabled)
    }
}
