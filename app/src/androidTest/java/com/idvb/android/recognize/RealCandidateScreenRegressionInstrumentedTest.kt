package com.idvb.android.recognize

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.ClassRecord
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.Gate
import com.idvb.android.idvm.GatesDocument
import com.idvb.android.idvm.IdvmJson
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.idvm.SideEntranceFeatureRecord
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Regression extracted losslessly from the candidate screen attached to the
 * original bug report. Frozen desktop commit 4654a90 detects zero gates in
 * this viewport; its legacy unconstrained side-template probe nevertheless
 * retrieves several unrelated maps. The real route must stop at the gate
 * prerequisite and may not publish those template false positives.
 */
@RunWith(AndroidJUnit4::class)
class RealCandidateScreenRegressionInstrumentedTest {
    @Test
    fun attachedNoGateViewportCannotPublishMapCandidates() {
        assertTrue(OpenCvRuntime.initialize())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = MapRepository(context)
        val suffix = UUID.randomUUID().toString()
        val mapId = "instrumented-real-regression-$suffix"
        val classId = "instrumented-real-class-$suffix"
        val mapDir = File(repository.mapsRoot, mapId)
        val dataDir = File(mapDir, "data").apply { mkdirs() }
        val featureFile = File(dataDir, "floor-001-side-entrance-feature.png")
        instrumentation.context.assets.open(
            "real-regressions/desktop-map-1-side-feature.png",
        ).use { input -> featureFile.outputStream().use(input::copyTo) }
        val sideBounds = NormalizedRect(
            .8095728488527048,
            .5504253029499551,
            .03190946100508641,
            .028189510535253582,
        )
        File(dataDir, "gates.json").writeText(
            IdvmJson.instance.encodeToString(
                GatesDocument.serializer(),
                GatesDocument(
                    schemaVersion = 1,
                    gates = listOf(Gate("side-1", "1f", "sideEntrance", sideBounds)),
                ),
            ),
        )
        val floor = FloorRecord(
            key = "1f",
            displayName = "1F",
            sortOrder = 0,
            imagePath = "$mapId/maps/unused.png",
            imageWidth = 1106,
            imageHeight = 1011,
            recognitionWidth = 1106,
            recognitionHeight = 1011,
            validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
            sideEntranceFeature = SideEntranceFeatureRecord(
                imagePath = "$mapId/data/${featureFile.name}",
                centerX = 904.2254950510762,
                centerY = 617.433325179804,
                radius = 67,
                imageWidth = 133,
                imageHeight = 121,
            ),
        )
        val record = MapRecord(mapId, classId, "Desktop map 1 fixture", mapId, 1, listOf(floor))
        val originalCatalog = repository.loadCatalog()
        var bitmap: android.graphics.Bitmap? = null
        try {
            repository.saveCatalog(
                originalCatalog.copy(
                    classes = originalCatalog.classes + ClassRecord(classId, "Real regression"),
                    maps = originalCatalog.maps + record,
                ),
            )
            bitmap = instrumentation.context.assets.open(
                "real-regressions/candidate-screen-live-viewport.png",
            ).use(BitmapFactory::decodeStream)
            requireNotNull(bitmap)

            val result = SideEntranceRecognizer(context, repository, classId).recognize(
                frame = bitmap,
                viewportBounds = ScreenRect(36.0, 315.0, 834.0, 660.0),
                clientWidth = 2560,
                clientHeight = 1159,
            )

            assertEquals(0, result.diagnostics!!.gateDetection.gates.size)
            assertTrue(result.diagnostics!!.failureReason.contains("未检测到门"))
            assertEquals(0, result.diagnostics!!.structureVerificationCount)
            assertEquals(0, result.diagnostics!!.reliableCandidateCount)
            assertEquals(1, result.candidates.size)
            assertEquals(mapId, result.candidates.single().map.id)
            assertEquals(CandidateDisposition.CATALOG_ONLY, result.candidates.single().disposition)
            assertEquals(0.0, result.candidates.single().templateScore, 0.0)
        } finally {
            bitmap?.recycle()
            runCatching { repository.deleteMap(record) }
            if (mapDir.exists()) mapDir.deleteRecursively()
        }
    }
}
