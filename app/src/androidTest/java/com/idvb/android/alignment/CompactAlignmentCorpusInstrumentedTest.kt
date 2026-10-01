package com.idvb.android.alignment

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.PrebuiltStructureLineRecord
import com.idvb.android.recognize.cv.CvImages
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Opt-in original captures stay in private cache; duplicates test genuine pose ambiguity. */
class CompactAlignmentCorpusInstrumentedTest {
    @Test fun compactRealObservationsAlignButDuplicatedPlacementsRemainRejected() {
        val relative = InstrumentationRegistry.getArguments().getString("alignmentCorpus")
        assumeTrue("No private corpus supplied", !relative.isNullOrBlank())
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
        val cases = root.listFiles { file -> file.extension == "zip" }!!.filter { file ->
            ZipFile(file).use { zip ->
                val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics.json"))
                    .bufferedReader().use { it.readText() }).jsonObject
                manifest["result"]?.jsonObject?.get("code")?.jsonPrimitive?.content in setOf("live-span-x", "live-span-y")
            }
        }
        assertTrue("No original compact-span failures supplied", cases.isNotEmpty())
        for (archive in cases) AlignmentPackageReplay.open(target, archive).use { replay ->
            val trace = AlignmentTrace(captureArtifacts = false)
            val accepted = replay.run(trace, AlignmentOutcome.ALIGNED)
            assertTrue("Original compact input must align: ${archive.name}: $accepted", accepted.passed)
            val transform = (accepted.result as AlignmentResult.Aligned).transform
            // Independently measured staircase wall corners in this real-frame corpus.
            if (archive.name.contains("99fa407b") || archive.name.contains("66a4b0f1")) {
                val viewport = replay.testCase.request.viewport
                for ((reference, observed) in listOf(
                    (324.0 to 271.0) to (257.0 to 254.0),
                    (375.0 to 322.0) to (296.0 to 294.0))) {
                    assertEquals(observed.first, reference.first * transform.scale + transform.offsetX - viewport.x, 2.0)
                    assertEquals(observed.second, reference.second * transform.scale + transform.offsetY - viewport.y, 2.0)
                }
            }
            val isolated = File(target.cacheDir, "compact-ambiguity-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(target) {
                override fun getApplicationContext() = this
                override fun getFilesDir() = isolated
            }
            try {
                val repository = MapRepository(context)
                val original = File(isolated, "original.png")
                ZipFile(archive).use { zip -> original.writeBytes(zip.getInputStream(zip.getEntry("reference.png")).use { it.readBytes() }) }
                val reference = CvImages.loadGray(original)
                val repeated = Mat.zeros(reference.rows(), reference.cols() * 2 + 80, CvType.CV_8UC1)
                try {
                    for (x in listOf(0, reference.cols() + 80)) {
                        val region = repeated.submat(Rect(x, 0, reference.cols(), reference.rows()))
                        try { reference.copyTo(region) } finally { region.release() }
                    }
                    val path = "duplicated/data/reference.png"
                    val file = File(repository.mapsRoot, path).apply { parentFile!!.mkdirs() }
                    assertTrue(Imgcodecs.imwrite(file.path, repeated))
                    val request = replay.testCase.request
                    val floor = request.floor.copy(recognitionWidth = repeated.cols(), recognitionHeight = repeated.rows(),
                        prebuiltStructureLine = PrebuiltStructureLineRecord(path,
                        AlignmentDiagnosticsStore.sha256(file.readBytes()), repeated.cols(), repeated.rows(), file.length(), "duplicated-real-reference"))
                    val map = request.map.copy(id = "duplicated", floors = listOf(floor))
                    val ambiguousTrace = AlignmentTrace(captureArtifacts = true)
                    val result = AlignmentRegistry.createDefault(repository).align("vpsg", request.copy(map = map, floor = floor), ambiguousTrace)
                    val store = AlignmentDiagnosticsStore(context)
                    val saved = store.record(ambiguousTrace, AlignmentDiagnosticContext("vpsg", map, floor.key,
                        request.viewport, 0, 0, "duplicated-real-reference"), request.frame, result, "replay").getOrThrow()
                    val evidence = File(target.filesDir, "test-evidence/compact-ambiguity/${archive.name}").apply { parentFile!!.mkdirs() }
                    evidence.outputStream().use { store.copyPackage(saved, it) }
                    assertTrue("Repeated compact geometry must stay ambiguous: $result", result is AlignmentResult.Rejected)
                    assertTrue("Must reach and fail uniqueness rather than an unrelated preflight gate",
                        ambiguousTrace.snapshot().any { event -> event.stage == "vpsg.verify.final" &&
                            event.gates.any { it.name == "unique-pose" && !it.passed } })
                } finally { repeated.release(); reference.release() }
            } finally { isolated.deleteRecursively() }
        }
    }
}
