package com.idvb.android.onboarding

import com.idvb.android.data.EyeButtonAction
import com.idvb.android.data.ScreenCaptureMethod
import org.junit.Assert.*
import org.junit.Test

class FeatureGuideRegistryTest {
    private val first = FeatureGuideRegistry.entries.first()

    @Test fun firstLaunchShowsAllAndCompletedGuidesStayHidden() {
        val second = FeatureGuideRegistry.entries[1]
        assertEquals(listOf(first, second), FeatureGuideRegistry.pending(emptySet()))
        assertEquals(listOf(second), FeatureGuideRegistry.pending(setOf(first.id)))
        assertTrue(FeatureGuideRegistry.pending(setOf(first.id, second.id)).isEmpty())
    }

    @Test fun futureGuidesAreSortedAndOldAcknowledgementsArePreserved() {
        val later = first.copy(id = "later", order = 300)
        val earlier = first.copy(id = "earlier", order = 50)
        assertEquals(listOf(earlier, later), FeatureGuideRegistry.pending(
            setOf(first.id, "retired"), listOf(later, first, earlier)))
        assertEquals(listOf(earlier, first, later), FeatureGuideRegistry.pending(
            emptySet(), listOf(later, first, earlier)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsCannotSilentlySkipAnotherGuide() {
        FeatureGuideRegistry.pending(emptySet(), listOf(first, first.copy(order = 2)))
    }

    @Test fun presetsMatchTheirPromises() {
        assertEquals(EyeButtonAction.SHOW_ONLY, ScanPreset.TRADITIONAL.eyeAction)
        assertFalse(ScanPreset.TRADITIONAL.autoDetect)
        assertEquals(EyeButtonAction.SHOW_AND_ALIGN, ScanPreset.AUTOMATIC.eyeAction)
        assertTrue(ScanPreset.AUTOMATIC.autoDetect)
        val captureGuide = FeatureGuideRegistry.entries[1]
        assertTrue(captureGuide.order > first.order)
        assertEquals(listOf(ScreenCaptureMethod.MEDIA_PROJECTION, ScreenCaptureMethod.ACCESSIBILITY),
            captureGuide.choices.map { it.captureMethod })
        assertTrue(captureGuide.choices.all { it.scanPreset == null })
    }
}
