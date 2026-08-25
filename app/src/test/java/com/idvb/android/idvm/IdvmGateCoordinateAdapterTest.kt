package com.idvb.android.idvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdvmGateCoordinateAdapterTest {
    private val region = NormalizedRect(x = .1, y = .2, width = .5, height = .4)
    private val wireBounds = NormalizedRect(x = .8, y = .5, width = .1, height = .2)

    @Test
    fun `IDVM 识别坐标转换为移动端整图坐标`() {
        val converted = IdvmGateCoordinateAdapter.toSourceImage(wireBounds, region)
        assertEquals(.5, converted.x, 1e-12)
        assertEquals(.4, converted.y, 1e-12)
        assertEquals(.05, converted.width, 1e-12)
        assertEquals(.08, converted.height, 1e-12)
    }

    @Test
    fun `移动端坐标往返后保持 IDVM 权威坐标`() {
        val source = IdvmGateCoordinateAdapter.toSourceImage(wireBounds, region)
        val restored = IdvmGateCoordinateAdapter.toRecognitionImage(source, region)

        assertEquals(wireBounds.x, restored.x, 1e-12)
        assertEquals(wireBounds.y, restored.y, 1e-12)
        assertEquals(wireBounds.width, restored.width, 1e-12)
        assertEquals(wireBounds.height, restored.height, 1e-12)
    }

    @Test
    fun `空识别区域按整张图片处理`() {
        assertEquals(wireBounds, IdvmGateCoordinateAdapter.toSourceImage(wireBounds, null))
        assertEquals(wireBounds, IdvmGateCoordinateAdapter.toRecognitionImage(wireBounds, null))
    }

    @Test
    fun `保存时拒绝识别区域以外的侧门`() {
        val outside = NormalizedRect(x = .02, y = .3, width = .05, height = .05)
        assertThrows(IllegalArgumentException::class.java) {
            IdvmGateCoordinateAdapter.toRecognitionImage(outside, region)
        }
    }

    @Test
    fun `替换编辑楼层侧门并保留其他门和原始 ID`() {
        val original = GatesDocument(
            schemaVersion = 1,
            gates = listOf(
                Gate("1f-main", "1f", "mainEntrance", NormalizedRect(.1, .1, .1, .1)),
                Gate("1f-side", "1f", "sideEntrance", wireBounds, directionDegrees = 42.0, confidence = .8),
                Gate("2f-side", "2f", "sideEntrance", NormalizedRect(.2, .2, .1, .1)),
            ),
        )
        val sourceBounds = IdvmGateCoordinateAdapter.gatesForSourceImageEditor(original, "1f", region)
        val roundTrip = IdvmGateCoordinateAdapter.replaceSideEntrancesFromSourceImageEditor(
            original,
            "1f",
            sourceBounds,
            region,
        )

        assertEquals(3, roundTrip.gates.size)
        assertEquals(original.gates[0], roundTrip.gates.single { it.id == "1f-main" })
        assertEquals(original.gates[2], roundTrip.gates.single { it.id == "2f-side" })
        val restored = roundTrip.gates.single { it.id == "1f-side" }
        assertEquals(42.0, restored.directionDegrees, 0.0)
        assertEquals(.8, restored.confidence, 0.0)
        assertEquals(wireBounds.x, requireNotNull(restored.bounds).x, 1e-12)
    }
}
