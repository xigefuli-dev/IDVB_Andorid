package com.idvb.android.recognize

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Complete recognition replay, independent of installed catalogs. Legacy screenshot-only
 * packages are explicitly unavailable; a current user catalog must never fill their gaps. */
class RecognitionPackageReplay private constructor(
    private val root: File, private val context: Context, private val frame: Bitmap,
    private val repository: MapRepository, val manifest: JsonObject,
    val recordedSourceFingerprint: String, val runningSourceFingerprint: String,
) : AutoCloseable {
    fun run(): RecognitionResult {
        val r = manifest.getValue("captureRegion").jsonObject
        val left = r.getValue("left").jsonPrimitive.double; val top = r.getValue("top").jsonPrimitive.double
        return MapScanRecognizer(context, repository).recognize(frame,
            ScreenRect(left, top, r.getValue("right").jsonPrimitive.double - left, r.getValue("bottom").jsonPrimitive.double - top),
            manifest.getValue("screenWidth").jsonPrimitive.int, manifest.getValue("screenHeight").jsonPrimitive.int,
            manifest["classId"]?.jsonPrimitive?.contentOrNull)
    }
    override fun close() { frame.recycle(); check(root.deleteRecursively()) }

    companion object {
        fun open(context: Context, archive: File): RecognitionPackageReplay {
            val root = File(context.cacheDir, "recognition-replay-${UUID.randomUUID()}").apply { check(mkdirs()) }
            var frame: Bitmap? = null
            try {
                val isolated = object : ContextWrapper(context) {
                    override fun getApplicationContext() = this
                    override fun getFilesDir() = root
                    override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
                }
                val repository = MapRepository(isolated)
                ZipFile(archive).use { zip ->
                    val manifestEntry = requireNotNull(zip.getEntry("diagnostics.json")) { "Recognition manifest missing" }
                    require(manifestEntry.size in 1..16L * 1024 * 1024)
                    val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                    require(manifest.getValue("schemaVersion").jsonPrimitive.int == 2) { "Legacy recognition package lacks a complete reference snapshot" }
                    require(manifest.getValue("replayReady").jsonPrimitive.boolean) { "Recognition inputs unavailable: ${manifest["replayUnavailableReasons"]}" }
                    val artifacts = manifest.getValue("artifacts").jsonObject
                    require("captured.png" in artifacts && "maps/maps.json" in artifacts && "alignment-provenance.json" in artifacts &&
                        "runtime-assets/recognition/Gate.png" in artifacts) { "Mandatory recognition replay input missing" }
                    val entries = zip.entries().asSequence().map { it.name }.toList()
                    require(entries.size == entries.toSet().size) { "Duplicate replay artifact" }
                    for ((name, info) in artifacts) {
                        val path = name.replace('\\', '/')
                        require(!path.startsWith('/') && !path.contains(':') && path.split('/').none { it.isBlank() || it == "." || it == ".." })
                        val entry = requireNotNull(zip.getEntry(name)) { "Replay artifact missing: $name" }
                        val expected = info.jsonObject
                        val size = expected.getValue("bytes").jsonPrimitive.long
                        require(size in 0..RecognitionReplayInputs.MAX_FILE_BYTES && entry.size == size)
                        val dest = if (path.startsWith("maps/")) File(repository.mapsRoot, path.removePrefix("maps/")) else File(root, path)
                        require(dest.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                        dest.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input -> dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var count = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                count += n; require(count <= size); output.write(buffer, 0, n)
                            }
                            require(count == size)
                        } }
                        require(RecognitionReplayInputs.digest(dest) == expected.getValue("sha256").jsonPrimitive.content) { "Replay hash mismatch: $name" }
                    }
                    val recorded = Json.parseToJsonElement(File(root, "alignment-provenance.json").readText()).jsonObject
                    recorded.getValue("files").jsonObject.forEach { (name, value) ->
                        require(artifacts["alignment-source/$name"]?.jsonObject?.get("sha256") == value) { "Source snapshot incomplete: $name" }
                    }
                    require(recorded.getValue("sourceFingerprint") == manifest.getValue("sourceFingerprint"))
                    // A new algorithm can be compared; changed runtime templates need a matching build.
                    artifacts.keys.filter { it.startsWith("runtime-assets/") }.forEach { name ->
                        val bytes = context.assets.open(name.removePrefix("runtime-assets/")).use { it.readBytes() }
                        require(com.idvb.android.alignment.AlignmentDiagnosticsStore.sha256(bytes) == artifacts.getValue(name).jsonObject.getValue("sha256").jsonPrimitive.content) {
                            "Running template differs from the original input: $name"
                        }
                    }
                    val image = File(root, "captured.png")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(image.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                    val region = manifest.getValue("captureRegion").jsonObject
                    require(bounds.outWidth == region.getValue("right").jsonPrimitive.int - region.getValue("left").jsonPrimitive.int &&
                        bounds.outHeight == region.getValue("bottom").jsonPrimitive.int - region.getValue("top").jsonPrimitive.int)
                    frame = requireNotNull(BitmapFactory.decodeFile(image.path))
                    val running = context.assets.open("alignment-provenance.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                    return RecognitionPackageReplay(root, isolated, frame!!, repository, manifest,
                        recorded.getValue("sourceFingerprint").jsonPrimitive.content, running.getValue("sourceFingerprint").jsonPrimitive.content)
                }
            } catch (error: Throwable) { frame?.recycle(); root.deleteRecursively(); throw error }
        }
    }
}
