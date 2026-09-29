package com.idvb.android.recognize.vpsg

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.ClassRecord
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapCatalogDocument
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.PrebuiltStructureLineRecord
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class VpsgLineScannerInstrumentedTest {
    @Test
    fun singleLineMapConfirmsButIdenticalMapsRemainUnresolved() {
        assertTrue(OpenCvRuntime.initialize())
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "vpsg-scan-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.rgb(160, 100, 80); style = Paint.Style.FILL }
        canvas.drawRect(30f, 25f, 160f, 165f, paint)
        canvas.drawRect(185f, 55f, 295f, 218f, paint)
        paint.color = Color.rgb(80, 125, 160)
        canvas.drawRect(150f, 95f, 205f, 135f, paint)
        val bgr = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(bgr).use { observed ->
                assertTrue(Core.countNonZero(observed.edges) > 100)
                val reference = Mat.zeros(400, 500, CvType.CV_8UC1)
                try {
                    val destination = reference.submat(Rect(90, 70, frame.width, frame.height))
                    try { observed.edges.copyTo(destination) } finally { destination.release() }
                    val repository = MapRepository(context)
                    val mapA = addMap(repository, reference, "map-a")
                    repository.saveCatalog(MapCatalogDocument(
                        classes = listOf(ClassRecord("class-a", "test", scanFloorKey = "1f")),
                        maps = listOf(mapA),
                    ))
                    val viewport = ScreenRect(0.0, 0.0, frame.width.toDouble(), frame.height.toDouble())
                    val single = requireNotNull(VpsgLineScanner(repository).recognize(frame, viewport, listOf(mapA)))
                    assertEquals("${single.vpsgDiagnostics} ${single.candidates.map { "${it.structureScale}, ${it.structureOffsetX}, ${it.structureOffsetY}: ${it.evidenceLabel}" }}",
                        1, single.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    val routed = com.idvb.android.recognize.MapScanRecognizer(context, repository)
                        .recognize(frame, viewport, frame.width, frame.height, "class-a")
                    assertEquals(1, routed.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    assertTrue("No-door routing must retain VPSG", routed.vpsgDiagnostics != null)

                    // One map with two indistinguishable placements must remain unresolved too.
                    val repeated = Mat.zeros(400, 1000, CvType.CV_8UC1)
                    try {
                        for (x in listOf(90, 590)) {
                            val destination = repeated.submat(Rect(x, 70, frame.width, frame.height))
                            try { observed.edges.copyTo(destination) } finally { destination.release() }
                        }
                        val repeatedMap = addMap(repository, repeated, "map-repeated")
                        repository.saveCatalog(repository.loadCatalog().copy(maps = listOf(repeatedMap)))
                        val repeatedResult = requireNotNull(VpsgLineScanner(repository)
                            .recognize(frame, viewport, listOf(repeatedMap)))
                        assertEquals(0, repeatedResult.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    } finally { repeated.release() }

                    val originalIndex = VpsgPreparedIndex.load(repository.floorImageFile(mapA.id,
                        mapA.floors.first().prebuiltStructureLine!!.imagePath), "fixture-1")
                    val cachedIndex = VpsgPreparedIndex.load(repository.floorImageFile(mapA.id,
                        mapA.floors.first().prebuiltStructureLine!!.imagePath), "fixture-1")
                    org.junit.Assert.assertSame(originalIndex, cachedIndex)
                    val nextGeneration = VpsgPreparedIndex.load(repository.floorImageFile(mapA.id,
                        mapA.floors.first().prebuiltStructureLine!!.imagePath), "fixture-2")
                    org.junit.Assert.assertNotSame(originalIndex, nextGeneration)

                    val largeReference = Mat.zeros(800, 1000, CvType.CV_8UC1)
                    try {
                        val destination = largeReference.submat(Rect(160, 210, frame.width, frame.height))
                        try { observed.edges.copyTo(destination) } finally { destination.release() }
                        val largeMap = addMap(repository, largeReference, "map-large")
                        repository.saveCatalog(repository.loadCatalog().copy(maps = listOf(largeMap)))
                        val large = requireNotNull(VpsgLineScanner(repository)
                            .recognize(frame, viewport, listOf(largeMap)))
                        assertEquals(1, large.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    } finally { largeReference.release() }

                    val missingScanFloor = mapA.copy(id = "map-missing-floor",
                        floors = mapA.floors.map { it.copy(key = "2f") })
                    repository.saveCatalog(repository.loadCatalog().copy(
                        maps = listOf(mapA, missingScanFloor)))
                    val incomplete = requireNotNull(VpsgLineScanner(repository)
                        .recognize(frame, viewport, listOf(mapA, missingScanFloor)))
                    assertEquals(0, incomplete.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    assertEquals(2, incomplete.vpsgDiagnostics?.eligibleFloorCount)
                    assertEquals(1, incomplete.vpsgDiagnostics?.readyFloorCount)

                    val denseReference = reference.clone()
                    try {
                        for (x in 100..370 step 12) Imgproc.line(denseReference,
                            Point(x.toDouble(), 80.0), Point(x.toDouble(), 300.0), Scalar.all(255.0), 2)
                        for (y in 80..300 step 12) Imgproc.line(denseReference,
                            Point(100.0, y.toDouble()), Point(370.0, y.toDouble()), Scalar.all(255.0), 2)
                        val denseMap = addMap(repository, denseReference, "map-dense")
                        repository.saveCatalog(repository.loadCatalog().copy(maps = listOf(denseMap)))
                        val dense = requireNotNull(VpsgLineScanner(repository)
                            .recognize(frame, viewport, listOf(denseMap)))
                        assertEquals(0, dense.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    } finally { denseReference.release() }

                    val mapB = addMap(repository, reference, "map-b")
                    repository.saveCatalog(repository.loadCatalog().copy(maps = listOf(mapA, mapB)))
                    val ambiguous = requireNotNull(VpsgLineScanner(repository)
                        .recognize(frame, viewport, listOf(mapA, mapB)))
                    assertEquals(0, ambiguous.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                    assertEquals(2, ambiguous.vpsgDiagnostics?.evaluatedFloorCount)
                    repository.saveCatalog(repository.loadCatalog().copy(variantGroups=listOf(
                        com.idvb.android.idvm.MapVariantGroupRecord("ab",mapA.classId,0,listOf(mapA.id,mapB.id)))))
                    val family = requireNotNull(VpsgLineScanner(repository).recognize(frame,viewport,listOf(mapA,mapB)))
                    assertEquals(1,family.candidates.count { it.disposition == CandidateDisposition.RELIABLE })
                } finally { reference.release() }
            }
        } finally {
            bgr.release()
            frame.recycle()
            isolated.deleteRecursively()
        }
    }

    private fun addMap(repository: MapRepository, reference: Mat, mapId: String): MapRecord {
        val path = "$mapId/data/prebuilt.png"
        val file = File(repository.mapsRoot, path).apply { parentFile?.mkdirs() }
        assertTrue(Imgcodecs.imwrite(file.path, reference))
        return MapRecord(
            id = mapId,
            classId = "class-a",
            title = mapId,
            sourceMapId = mapId,
            mapVersion = 1,
            floors = listOf(FloorRecord(
                key = "1f", displayName = "1F", sortOrder = 1,
                imagePath = "$mapId/maps/floor-001.png",
                imageWidth = reference.cols(), imageHeight = reference.rows(),
                recognitionWidth = reference.cols(), recognitionHeight = reference.rows(),
                prebuiltStructureLine = PrebuiltStructureLineRecord(
                    imagePath = path, sha256 = "fixture", width = reference.cols(),
                    height = reference.rows(), fileLength = file.length(), algorithmId = "fixture",
                ),
            )),
        )
    }
}
