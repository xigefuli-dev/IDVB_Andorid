package com.idvb.android.idvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * Opt-in compatibility test for an actual Desktop export. Set
 * IDVB_REAL_IDVM to run it; normal CI skips it when no private package exists.
 */
class IdvmRealPackageCompatibilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `真实 Desktop 包导入后侧门可无损适配移动端坐标`() {
        val packageFile = System.getenv("IDVB_REAL_IDVM")?.let(::File)
        assumeTrue("未提供 IDVB_REAL_IDVM", packageFile?.isFile == true)
        val mapsRoot = tmp.newFolder("maps")
        var savedCatalog: MapCatalogDocument? = null

        val result = IdvmImporter().importPackage(
            packageFile = requireNotNull(packageFile),
            mapsRoot = mapsRoot,
            currentCatalog = MapCatalogDocument(),
            catalogSaver = { savedCatalog = it },
        )
        val success = result as? ImportResult.Success
            ?: error("真实 IDVM 导入失败：${(result as ImportResult.Failure).reason}")
        assertTrue(success.importedMaps.isNotEmpty())
        assertEquals(success.catalog, savedCatalog)

        var checkedGates = 0
        var affectedGates = 0
        var maximumRoundTripError = 0.0
        success.importedMaps.forEach { map ->
            val gatesFile = File(mapsRoot, "${map.id}/data/gates.json")
            val document = IdvmJson.instance.decodeFromString<GatesDocument>(gatesFile.readText())
            map.floors.forEach { floor ->
                val wireBounds = document.gates.filter {
                    it.role == "sideEntrance" && it.enabled &&
                        it.floorKey.equals(floor.key, ignoreCase = true)
                }.mapNotNull(Gate::bounds)
                val editorBounds = IdvmGateCoordinateAdapter.gatesForSourceImageEditor(
                    document,
                    floor.key,
                    floor.previewRegion,
                )
                assertEquals(wireBounds.size, editorBounds.size)
                wireBounds.zip(editorBounds).forEach { (wire, editor) ->
                    checkedGates++
                    if (rectError(wire, editor) > 1e-9) affectedGates++
                    val restored = IdvmGateCoordinateAdapter.toRecognitionImage(editor, floor.previewRegion)
                    maximumRoundTripError = maxOf(maximumRoundTripError, rectError(wire, restored))
                }
            }
        }

        assertTrue("真实包内没有可校验侧门", checkedGates > 0)
        assertTrue("真实包未覆盖非整图识别区域", affectedGates > 0)
        assertTrue("IDVM 坐标往返误差过大：$maximumRoundTripError", maximumRoundTripError <= 1e-12)
    }

    private fun rectError(first: NormalizedRect, second: NormalizedRect): Double = maxOf(
        abs(first.x - second.x),
        abs(first.y - second.y),
        abs(first.width - second.width),
        abs(first.height - second.height),
    )
}
