package com.idvb.android.alignment

import com.idvb.android.data.EyeButtonAction
import com.idvb.android.data.SearchButtonAction
import org.junit.Assert.*
import org.junit.Test

class OperationActionsTest {
    @Test fun freshLegacyAndUnknownSettingsDefaultToAutomaticAlignment() {
        for (stored in listOf(null, "", "future-value")) {
            assertEquals(EyeButtonAction.SHOW_AND_ALIGN, EyeButtonAction.fromStored(stored))
            assertEquals(SearchButtonAction.SCAN_MAP, SearchButtonAction.fromStored(stored))
        }
        assertEquals(EyeButtonAction.SHOW_ONLY, EyeButtonAction.fromStored("SHOW_ONLY"))
    }

    @Test fun transformIncludesScreenOriginAndUsesReferenceDimensions() {
        val transform = AlignmentTransform(.75, -12.5, 67.25, 800, 600)
        assertEquals(-12.5, transform.bounds.x, 0.0)
        assertEquals(67.25, transform.bounds.y, 0.0)
        assertEquals(600.0, transform.bounds.width, 0.0)
        assertEquals(450.0, transform.bounds.height, 0.0)
    }

    @Test fun invalidTransformsCannotReachRendering() {
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { AlignmentTransform(scale, 0.0, 0.0, 100, 100) }
        }
        assertThrows(IllegalArgumentException::class.java) { AlignmentTransform(1.0, Double.NaN, 0.0, 100, 100) }
    }

    @Test fun registryResolvesNewMethodsWithoutChangingButtonCode() {
        val alternative = object : AlignmentMethod {
            override val id = "alternative"
            override fun align(request: AlignmentRequest, log: AlignmentLogSink) = AlignmentResult.Rejected("fixture")
        }
        val registry = AlignmentRegistry(listOf(alternative))
        assertSame(alternative, registry.resolve("alternative"))
        assertNull(registry.resolve("unregistered"))
        assertThrows(IllegalArgumentException::class.java) { AlignmentRegistry(listOf(alternative, alternative)) }
    }
}
