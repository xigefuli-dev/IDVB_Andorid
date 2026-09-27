package com.idvb.android.recognize

import com.idvb.android.idvm.MetadataTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualMapSelectionPolicyTest {
    private val tags = listOf(MetadataTag("direction", "门方位", "右"), MetadataTag("shape", "门形状", "┤"))

    @Test fun selectedTagGroupsMustAllMatch() {
        assertTrue(ManualMapSelectionPolicy.matches(tags, mapOf("direction" to "右", "shape" to "┤")))
        assertFalse(ManualMapSelectionPolicy.matches(tags, mapOf("direction" to "左")))
    }

    @Test fun recognitionCandidatesExposeAllDistinctTagGroupsWithoutManualMode() {
        val groups = ManualMapSelectionPolicy.groups(tags + tags.first())
        assertEquals(listOf("direction", "shape"), groups.map { it.id })
        assertEquals(listOf("右"), groups.first().values)
        assertTrue(ManualMapSelectionPolicy.matches(emptyList(), emptyMap()))
        assertFalse(ManualMapSelectionPolicy.matches(emptyList(), mapOf("direction" to "右")))
    }
}
