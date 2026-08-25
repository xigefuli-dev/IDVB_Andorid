package com.idvb.android.recognize

import android.graphics.Bitmap
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
import com.idvb.android.recognize.gate.GateTemplateImages
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.side.SideEntranceGateAssociationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SideEntranceRecognizerInstrumentedTest {
    @Test
    fun sameSideTemplateCannotPromoteWrongFullMapStructure() {
        assertTrue(OpenCvRuntime.initialize())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = MapRepository(context)
        val suffix = UUID.randomUUID().toString()
        val classId = "instrumented-class-$suffix"
        val correctId = "instrumented-correct-$suffix"
        val wrongId = "instrumented-wrong-$suffix"
        val mapDirs = listOf(correctId, wrongId).associateWith { File(repository.mapsRoot, it) }
        val correctReference = buildCorrectReference()
        val wrongReference = buildWrongReference(correctReference)
        val frame = correctReference.submat(Rect(100, 150, 800, 500)).clone()
        val feature = correctReference.submat(Rect(440, 352, 120, 96)).clone()
        val gateAsset = GateTemplateImages.loadAsset(context)
        val gateGray = GateTemplateImages.createMatchImage(gateAsset)
        val scaledGate = Mat()
        var bitmap: Bitmap? = null
        val records = mutableListOf<MapRecord>()
        try {
            val gateBounds = NormalizedRect(.486, .4825, .028, .035)
            listOf(correctId to correctReference, wrongId to wrongReference).forEach { (mapId, reference) ->
                val dataDir = File(mapDirs.getValue(mapId), "data").apply { mkdirs() }
                val referenceFile = File(dataDir, "floor-001-recognition.png")
                val featureFile = File(dataDir, "floor-001-side-entrance-feature.png")
                assertTrue(org.opencv.imgcodecs.Imgcodecs.imwrite(referenceFile.absolutePath, reference))
                assertTrue(org.opencv.imgcodecs.Imgcodecs.imwrite(featureFile.absolutePath, feature))
                File(dataDir, "gates.json").writeText(
                    IdvmJson.instance.encodeToString(
                        GatesDocument.serializer(),
                        GatesDocument(
                            schemaVersion = 1,
                            gates = listOf(Gate("side-1", "1f", "sideEntrance", gateBounds)),
                        ),
                    ),
                )
                val floor = FloorRecord(
                    key = "1f",
                    displayName = "1F",
                    sortOrder = 0,
                    imagePath = "$mapId/maps/unused.png",
                    imageWidth = 1000,
                    imageHeight = 800,
                    recognitionImagePath = "$mapId/data/${referenceFile.name}",
                    recognitionWidth = 1000,
                    recognitionHeight = 800,
                    validMapBounds = NormalizedRect(0.0, 0.0, 1.0, 1.0),
                    sideEntranceFeature = SideEntranceFeatureRecord(
                        imagePath = "$mapId/data/${featureFile.name}",
                        centerX = 500.0,
                        centerY = 400.0,
                        radius = 60,
                        imageWidth = feature.cols(),
                        imageHeight = feature.rows(),
                    ),
                )
                records += MapRecord(mapId, classId, mapId, mapId, 1, listOf(floor))
            }
            val existing = repository.loadCatalog()
            repository.saveCatalog(
                existing.copy(
                    classes = existing.classes + ClassRecord(classId, "Instrumented class"),
                    maps = existing.maps + records,
                ),
            )

            Imgproc.resize(gateGray, scaledGate, Size(28.0, 28.0), 0.0, 0.0, Imgproc.INTER_AREA)
            frame.submat(Rect(386, 236, 28, 28)).useRecognizerMat { scaledGate.copyTo(it) }
            bitmap = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame, bitmap)

            val result = SideEntranceRecognizer(context, repository, classId).recognize(
                frame = bitmap,
                viewportBounds = ScreenRect(61.0, 37.0, 800.0, 500.0),
                clientWidth = 2560,
                clientHeight = 1440,
            )

            assertEquals(SideEntranceRecognizer.ROUTE, result.diagnostics?.route)
            assertTrue(result.diagnostics!!.gateDetection.gates.isNotEmpty())
            assertEquals(2, result.diagnostics!!.readyMapCount)
            assertEquals(2, result.diagnostics!!.structureVerificationCount)
            assertEquals(1, result.diagnostics!!.reliableCandidateCount)
            assertTrue(result.diagnostics!!.failureReason.isEmpty())

            val correct = result.candidates.first { it.map.id == correctId }
            val wrong = result.candidates.first { it.map.id == wrongId }
            android.util.Log.i(
                "IDVB_STRUCTURE_PERF",
                "correctMs=${correct.structureElapsedMilliseconds} wrongMs=${wrong.structureElapsedMilliseconds} " +
                    "totalMs=${result.diagnostics!!.structureTotalMilliseconds}",
            )
            assertTrue(
                "双候选整图结构验证耗时 ${result.diagnostics!!.structureTotalMilliseconds}ms",
                result.diagnostics!!.structureTotalMilliseconds < 10_000.0,
            )
            assertEquals(CandidateDisposition.RELIABLE, correct.disposition)
            assertEquals(SideEntranceGateAssociationKind.DETECTED_GATE, correct.gateAssociationKind)
            assertTrue(correct.templateScore >= .68)
            assertTrue(correct.chamferPixels <= SideEntranceRecognizer.STRICT_CHAMFER_LIMIT)
            assertTrue(correct.edgeCoverage >= .40)
            assertTrue(correct.occupancyCoverage >= .42)
            assertTrue(correct.consistentStructurePartitions >= 2)
            assertTrue(correct.structureCandidateMargin >= .04)
            assertTrue(correct.gateSpatialResidualPixels <= 42.0)

            // Both candidates own the exact same side feature pixels. The
            // wrong map must therefore be rejected by independent full-map
            // structure rather than by the retrieval template.
            assertTrue(wrong.templateScore >= .68)
            assertEquals(CandidateDisposition.NEEDS_VERIFICATION, wrong.disposition)
            assertFalse(wrong.structureRejectionReason == null)
        } finally {
            bitmap?.recycle()
            scaledGate.release()
            gateGray.release()
            gateAsset.release()
            frame.release()
            feature.release()
            correctReference.release()
            wrongReference.release()
            records.forEach { runCatching { repository.deleteMap(it) } }
            mapDirs.values.forEach { if (it.exists()) it.deleteRecursively() }
        }
    }

    private fun buildCorrectReference(): Mat = Mat(800, 1000, CvType.CV_8UC1, Scalar(28.0)).also { mat ->
        Imgproc.rectangle(mat, Rect(100, 250, 800, 90), Scalar(170.0), -1)
        Imgproc.rectangle(mat, Rect(430, 90, 120, 620), Scalar(185.0), -1)
        Imgproc.rectangle(mat, Rect(180, 120, 250, 80), Scalar(150.0), -1)
        Imgproc.rectangle(mat, Rect(550, 500, 310, 105), Scalar(205.0), -1)
        Imgproc.rectangle(mat, Rect(720, 170, 95, 80), Scalar(140.0), -1)
        Imgproc.rectangle(mat, Rect(472, 372, 56, 56), Scalar(28.0), -1)
        Imgproc.rectangle(mat, Rect(205, 270, 75, 50), Scalar(75.0), -1)
        Imgproc.rectangle(mat, Rect(650, 520, 65, 65), Scalar(95.0), -1)
    }

    private fun buildWrongReference(correct: Mat): Mat =
        Mat(800, 1000, CvType.CV_8UC1, Scalar(28.0)).also { mat ->
            Imgproc.rectangle(mat, Rect(120, 120, 110, 590), Scalar(185.0), -1)
            Imgproc.rectangle(mat, Rect(120, 590, 740, 95), Scalar(165.0), -1)
            Imgproc.rectangle(mat, Rect(700, 180, 160, 410), Scalar(205.0), -1)
            Imgproc.rectangle(mat, Rect(310, 420, 390, 75), Scalar(145.0), -1)
            correct.submat(Rect(440, 352, 120, 96)).useRecognizerMat { source ->
                mat.submat(Rect(440, 352, 120, 96)).useRecognizerMat { target -> source.copyTo(target) }
            }
        }
}

private inline fun <T> Mat.useRecognizerMat(block: (Mat) -> T): T = try {
    block(this)
} finally {
    release()
}
