package com.idvb.android.idvm

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.data.MapTemplate
import com.idvb.android.data.TemplateFloor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IdvmImportedMapEditingInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: MapRepository

    @Before
    fun setUp() {
        repository = MapRepository(context)
        repository.mapsRoot.deleteRecursively()
        repository.mapsRoot.mkdirs()
    }

    @After
    fun tearDown() {
        repository.mapsRoot.deleteRecursively()
    }

    @Test
    fun importedGateIsAdaptedForEditorAndRestoredWithoutLosingDesktopAssets() {
        val classId = "11111111-1111-4111-8111-111111111111"
        val mapId = "22222222-2222-4222-8222-222222222222"
        val floorKey = "1f"
        val region = NormalizedRect(.1, .2, .5, .4)
        val wireGate = NormalizedRect(.8, .5, .1, .2)
        val mapDir = File(repository.mapsRoot, mapId)
        val mapsDir = File(mapDir, "maps").apply { mkdirs() }
        val dataDir = File(mapDir, "data").apply { mkdirs() }
        val floorImage = File(mapsDir, "floor-001.png")
        val recognitionImage = File(dataDir, "floor-001-recognition.png")
        val featureImage = File(dataDir, "floor-001-side-entrance-feature.png")
        InstrumentationRegistry.getInstrumentation().context.assets.open(
            "real-regressions/desktop-map-1-side-feature.png"
        ).use { input ->
            floorImage.outputStream().use(input::copyTo)
        }
        floorImage.copyTo(recognitionImage)
        floorImage.copyTo(featureImage)

        val feature = SideEntranceFeatureRecord(
            imagePath = "$mapId/data/${featureImage.name}",
            centerX = 40.0,
            centerY = 50.0,
            radius = 20,
            imageWidth = 133,
            imageHeight = 121,
        )
        val floor = FloorRecord(
            key = floorKey,
            displayName = "1F",
            sortOrder = 1,
            imagePath = "$mapId/maps/${floorImage.name}",
            imageWidth = 133,
            imageHeight = 121,
            previewRegion = region,
            recognitionImagePath = "$mapId/data/${recognitionImage.name}",
            recognitionWidth = 133,
            recognitionHeight = 121,
            validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
            sideEntranceFeature = feature,
        )
        val map = MapRecord(mapId, classId, "地图", mapId, 1, listOf(floor))
        repository.saveCatalog(
            MapCatalogDocument(
                classes = listOf(ClassRecord(classId, "S0")),
                maps = listOf(map),
            )
        )
        val metadata = MetadataDocument(
            schemaVersion = 1,
            map = MetadataMap(mapId, classId, "地图", "survey", "normalized-top-left-y-down"),
            floors = listOf(
                MetadataFloor(
                    key = floorKey,
                    displayName = "1F",
                    sortOrder = 1,
                    image = "maps/$mapId/maps/${floorImage.name}",
                    imageWidth = 133,
                    imageHeight = 121,
                    recognitionRegion = region,
                    validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    recognitionImage = "maps/$mapId/data/${recognitionImage.name}",
                    sideEntranceFeature = SideEntranceFeatureMetadata(
                        file = "maps/$mapId/data/${featureImage.name}",
                        centerX = 40.0,
                        centerY = 50.0,
                        radius = 20,
                    ),
                )
            ),
        )
        val gates = GatesDocument(
            schemaVersion = 1,
            gates = listOf(Gate("1f-side", floorKey, "sideEntrance", wireGate, directionDegrees = 37.0)),
        )
        val anchors = AnchorsDocument(
            schemaVersion = 1,
            floors = mapOf(
                floorKey to FloorAnchors(
                    anchors = listOf(
                        Anchor("anchor-side", "side-entrance", "侧门", "required", 1.0, true, gateId = "1f-side")
                    )
                )
            ),
        )
        val metadataFile = File(dataDir, "metadata.json")
        val anchorsFile = File(dataDir, "anchors.json")
        metadataFile.writeText(IdvmJson.instance.encodeToString(MetadataDocument.serializer(), metadata))
        File(dataDir, "gates.json").writeText(IdvmJson.instance.encodeToString(GatesDocument.serializer(), gates))
        anchorsFile.writeText(IdvmJson.instance.encodeToString(AnchorsDocument.serializer(), anchors))
        val metadataBefore = metadataFile.readBytes()
        val anchorsBefore = anchorsFile.readBytes()

        val editorBounds = repository.loadSideDoorsForEditing(mapId, floor).single()
        assertEquals(.5, editorBounds.x, 1e-12)
        assertEquals(.4, editorBounds.y, 1e-12)
        assertEquals(.05, editorBounds.width, 1e-12)
        assertEquals(.08, editorBounds.height, 1e-12)

        val updated = repository.updateMap(
            mapId = mapId,
            classId = classId,
            title = "地图（已编辑）",
            template = MapTemplate("saved-$mapId", "地图", listOf(TemplateFloor(floorKey, "1F"))),
            images = mapOf(floorKey to Uri.fromFile(floorImage)),
            sideDoors = listOf(editorBounds),
            resolver = context.contentResolver,
        )

        val restoredGate = repository.loadSideDoors(mapId, floorKey).single()
        assertEquals(wireGate.x, restoredGate.x, 1e-12)
        assertEquals(wireGate.y, restoredGate.y, 1e-12)
        assertEquals(wireGate.width, restoredGate.width, 1e-12)
        assertEquals(wireGate.height, restoredGate.height, 1e-12)
        val restoredFloor = updated.floors.single()
        assertEquals(1, restoredFloor.sortOrder)
        assertEquals(region, restoredFloor.previewRegion)
        assertEquals(feature, restoredFloor.sideEntranceFeature)
        assertTrue(File(repository.mapsRoot, requireNotNull(restoredFloor.recognitionImagePath)).isFile)
        assertTrue(File(repository.mapsRoot, requireNotNull(restoredFloor.sideEntranceFeature).imagePath).isFile)
        assertTrue(metadataBefore.contentEquals(metadataFile.readBytes()))
        assertTrue(anchorsBefore.contentEquals(anchorsFile.readBytes()))
        val restoredAnchors = IdvmJson.instance.decodeFromString<AnchorsDocument>(
            anchorsFile.readText()
        )
        assertEquals("1f-side", restoredAnchors.floors.getValue(floorKey).anchors.single().gateId)
    }
}
