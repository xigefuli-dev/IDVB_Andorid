package com.idvb.android.recognize

import com.idvb.android.idvm.MapVariantGroupRecord
import org.junit.Assert.*
import org.junit.Test

class ScanIdentityFamilyTest {
    private fun group(id: String, vararg ids: String, classId: String = "c") =
        MapVariantGroupRecord(id, classId, 0, ids.toList())

    @Test fun uniqueAndSingleDeclaredFamilyCanCommit() {
        assertTrue(sameScanIdentityFamily("a", "c", listOf("a"), emptyList()))
        assertTrue(sameScanIdentityFamily("a", "c", listOf("a", "b"), listOf(group("ab", "a", "b"))))
        assertFalse(sameScanIdentityFamily("a", "c", listOf("a", "b"), emptyList()))
    }

    @Test fun overlapMustNotTransitivelyMergeFamilies() {
        val groups = listOf(group("ab", "a", "b"), group("bc", "b", "c"))
        assertFalse(sameScanIdentityFamily("b", "c", listOf("a", "b", "c"), groups))
        assertFalse(sameScanIdentityFamily("a", "c", listOf("a", "c"), groups))
        assertFalse(sameScanIdentityFamily("a", "c", listOf("a", "b"), listOf(group("bad", "a", "b", classId="other"))))
    }
}
