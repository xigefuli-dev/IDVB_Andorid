package com.idvb.android.alignment

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.MapCatalogDocument
import com.idvb.android.idvm.PrebuiltStructureLineRecord
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import com.idvb.android.alignment.AlignmentDiagnosticsStore.Companion.sha256

/** Loads only packaged inputs into an isolated repository, never the user's active catalog. */
class AlignmentPackageReplay private constructor(
    private val root: File,
    private val frame: Bitmap,
    private val registry: AlignmentRegistry,
    val testCase: AlignmentTestCase,
    val recordedSourceFingerprint: String,
    val runningSourceFingerprint: String,
    val supplementedReferences: List<String> = emptyList(),
) : AutoCloseable {
    val sourceMatches: Boolean get() = recordedSourceFingerprint == runningSourceFingerprint
    fun run(log: AlignmentLogSink = AlignmentLogSink.NONE,
        expectedOutcome: AlignmentOutcome = testCase.expectedOutcome,
        expectedTransform: AlignmentTransform? = testCase.expectedTransform): AlignmentTestReport =
        AlignmentReplayRunner(registry, log).run(testCase.copy(expectedOutcome = expectedOutcome, expectedTransform = expectedTransform))
    override fun close() { if (!frame.isRecycled) frame.recycle(); root.deleteRecursively() }

    companion object {
        /** A different fingerprint is reported explicitly, allowing intentional A/B comparisons. */
        fun open(context: Context, archive: File, supplementalReferences: File? = null): AlignmentPackageReplay {
            val json = Json { ignoreUnknownKeys = true }
            val root = File(context.cacheDir, "alignment-replay-${UUID.randomUUID()}").apply { mkdirs() }
            var frame: Bitmap? = null
            try {
                ZipFile(archive).use { zip ->
                    fun bytes(name: String): ByteArray {
                        val entry = requireNotNull(zip.getEntry(name)) { "Missing replay input: $name" }
                        require(entry.size in 0..64L * 1024 * 1024) { "Replay entry exceeds limit" }
                        return zip.getInputStream(entry).use { stream ->
                            ByteArrayOutputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var count = stream.read(buffer)
                                while (count >= 0) {
                                    require(output.size().toLong() + count <= 64L * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                    count = stream.read(buffer)
                                }
                                output.toByteArray()
                            }
                        }
                    }
                    val manifest = json.parseToJsonElement(bytes("diagnostics.json").decodeToString()).jsonObject
                    require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1)
                    require(manifest.getValue("replayReady").jsonPrimitive.boolean) { "Replay inputs are incomplete" }
                    val artifacts = manifest.getValue("artifacts").jsonObject
                    fun checked(name: String): ByteArray {
                        val expected = artifacts.getValue(name).jsonObject
                        return bytes(name).also {
                            require(it.size.toLong() == expected.getValue("bytes").jsonPrimitive.long)
                            require(AlignmentDiagnosticsStore.sha256(it) == expected.getValue("sha256").jsonPrimitive.content) {
                                "Replay artifact hash mismatch: $name"
                            }
                        }
                    }
                    // Check every artifact, including the source snapshot and raw masks, before replay.
                    artifacts.keys.forEach { checked(it) }
                    val image = checked("captured.png")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                    frame = requireNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
                    val original = json.decodeFromJsonElement<MapRecord>(manifest.getValue("map"))
                    val selected = original.floors.first { it.key == manifest.getValue("floorKey").jsonPrimitive.content }
                    val reference = checked(if ("reference-prebuilt.png" in artifacts) "reference-prebuilt.png" else "reference.png")
                    BitmapFactory.decodeByteArray(reference, 0, reference.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                    val isolated = object : ContextWrapper(context) {
                        override fun getApplicationContext(): Context = this
                        override fun getFilesDir(): File = root
                    }
                    val repository = MapRepository(isolated)
                    val path = "replay-map/data/reference.png"
                    File(repository.mapsRoot, path).apply { parentFile!!.mkdirs(); writeBytes(reference) }
                    fun colorPath(candidate: com.idvb.android.idvm.FloorRecord, prefix: String): Pair<String?, String?> {
                        val fullSource = "${prefix}reference-source.png"
                        if (fullSource in artifacts) {
                            val sourcePath = "replay-map/data/${prefix}source.png"
                            File(repository.mapsRoot, sourcePath).apply { parentFile!!.mkdirs(); writeBytes(checked(fullSource)) }
                            return null to sourcePath
                        }
                        val entry = "${prefix}reference-color.png"
                        if (entry in artifacts) {
                            val colorPath = "replay-map/data/${prefix}color.png"
                            File(repository.mapsRoot, colorPath).apply { parentFile!!.mkdirs(); writeBytes(checked(entry)) }
                            return colorPath to null
                        }
                        val external = candidate.prebuiltStructureLine?.let { supplementalReferences?.resolve("${it.sha256}.source.png") }?.takeIf(File::isFile)
                        if (external == null) return null to null
                        val sourcePath = "replay-map/data/${prefix}source.png"
                        File(repository.mapsRoot, sourcePath).apply { parentFile!!.mkdirs(); writeBytes(external.readBytes()) }
                        return null to sourcePath
                    }
                    val (selectedColor, selectedSource) = colorPath(selected, "")
                    val floor = selected.copy(imagePath = selectedSource ?: selected.imagePath,
                        recognitionImagePath = selectedColor, recognitionWidth = bounds.outWidth,
                        recognitionHeight = bounds.outHeight, prebuiltStructureLine = PrebuiltStructureLineRecord(path,
                            AlignmentDiagnosticsStore.sha256(reference), bounds.outWidth, bounds.outHeight,
                            reference.size.toLong(), selected.prebuiltStructureLine?.algorithmId ?: "replay"))
                    val supplemented = mutableListOf<String>()
                    val floors = original.floors.mapIndexed { index, candidate ->
                        if (candidate.key == selected.key) floor else {
                            val entry = if ("floor-$index-reference-prebuilt.png" in artifacts) "floor-$index-reference-prebuilt.png" else "floor-$index-reference.png"
                            val source = candidate.prebuiltStructureLine
                            val supplied = source?.let { supplementalReferences?.resolve("${it.sha256}.png") }?.takeIf(File::isFile)
                            val data = if (entry in artifacts) checked(entry) else supplied?.readBytes()?.also {
                                require(source != null && sha256(it) == source.sha256) { "Supplemental reference does not match the recorded floor hash" }
                                supplemented += source.sha256
                            }
                            if (data == null) candidate.copy(prebuiltStructureLine = null) else {
                                val extraPath = "replay-map/data/floor-$index-reference.png"
                                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                                File(repository.mapsRoot, extraPath).apply { parentFile!!.mkdirs(); writeBytes(data) }
                                val (color, sourcePath) = colorPath(candidate, "floor-$index-")
                                candidate.copy(imagePath = sourcePath ?: candidate.imagePath, recognitionImagePath = color, recognitionWidth = bounds.outWidth,
                                    recognitionHeight = bounds.outHeight, prebuiltStructureLine = PrebuiltStructureLineRecord(
                                        extraPath, sha256(data), bounds.outWidth, bounds.outHeight, data.size.toLong(), source?.algorithmId ?: "replay"))
                            }
                        }
                    }
                    val map = original.copy(id = "replay-map", floors = floors)
                    repository.saveCatalog(MapCatalogDocument(maps = listOf(map)))
                    val viewportJson = manifest.getValue("viewport").jsonObject
                    fun number(name: String) = viewportJson.getValue(name).jsonPrimitive.double
                    val viewport = ScreenRect(number("x"), number("y"), number("width"), number("height"))
                    require(frame!!.width.toDouble() == viewport.width && frame!!.height.toDouble() == viewport.height)
                    val recorded = manifest.getValue("result").jsonObject
                    val expected = recorded["transform"]?.jsonObject?.let {
                        AlignmentTransform(it.getValue("scale").jsonPrimitive.double, it.getValue("offsetX").jsonPrimitive.double,
                            it.getValue("offsetY").jsonPrimitive.double, it.getValue("referenceWidth").jsonPrimitive.int,
                            it.getValue("referenceHeight").jsonPrimitive.int)
                    }
                    val source = json.parseToJsonElement(checked("alignment-provenance.json").decodeToString()).jsonObject
                    source.getValue("files").jsonObject.forEach { (path, hash) ->
                        require(artifacts["alignment-source/$path"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content == hash.jsonPrimitive.content) {
                            "Source snapshot incomplete or inconsistent: $path"
                        }
                    }
                    val running = context.assets.open("alignment-provenance.json").bufferedReader().use {
                        json.parseToJsonElement(it.readText()).jsonObject
                    }
                    return AlignmentPackageReplay(root, frame!!, AlignmentRegistry.createDefault(repository),
                        AlignmentTestCase(manifest.getValue("methodId").jsonPrimitive.content,
                            AlignmentRequest(frame!!, viewport, map, floor), expected,
                            AlignmentOutcome.valueOf(recorded.getValue("outcome").jsonPrimitive.content)),
                        source.getValue("sourceFingerprint").jsonPrimitive.content,
                        running.getValue("sourceFingerprint").jsonPrimitive.content, supplemented)
                }
            } catch (error: Throwable) {
                frame?.recycle(); root.deleteRecursively(); throw error
            }
        }
    }
}
