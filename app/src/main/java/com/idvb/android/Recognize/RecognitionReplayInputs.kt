package com.idvb.android.recognize

import android.content.Context
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.MapCatalogDocument
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Capture before computation, then verify every live input at publication. Streams images;
 * never retain a whole catalog in RAM or silently substitute a later catalog for the scan. */
class RecognitionReplayInputs private constructor(
    private val root: File,
    private val repository: MapRepository,
    private val originalCatalog: MapCatalogDocument,
    val catalog: MapCatalogDocument,
    val classId: String?,
) : AutoCloseable {
    data class Input(val entry: String, val source: File, val bytes: Long, val sha256: String)
    val files = linkedMapOf<String, File>()
    private val inputs = linkedMapOf<String, Input>()
    private val absent = mutableListOf<File>()
    val missing = mutableListOf<String>()
    var captureMs = 0.0; private set
    var verificationMs = 0.0; private set

    fun verifyUnchanged(): List<String> {
        val started = System.nanoTime()
        val changed = mutableListOf<String>()
        if (repository.loadCatalog() != originalCatalog) changed += "catalog-changed-during-scan"
        absent.filter(File::exists).forEach { changed += "previously-absent-reference-appeared:${it.relativeTo(repository.mapsRoot).invariantSeparatorsPath}" }
        for (input in inputs.values) {
            if (!input.source.isFile || input.source.length() != input.bytes ||
                runCatching { digest(input.source) }.getOrNull() != input.sha256)
                changed += "reference-changed-during-scan:${input.entry}"
        }
        verificationMs = (System.nanoTime() - started) / 1e6
        return missing + changed
    }

    override fun close() { check(root.deleteRecursively()) { "Unable to release private scan input spool" } }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        internal fun digest(file: File): String = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun capture(context: Context, repository: MapRepository, catalog: MapCatalogDocument, classId: String?): RecognitionReplayInputs {
            val started = System.nanoTime()
            val root = File(context.cacheDir, "recognition-inputs-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val selected = catalog.copy(maps = catalog.maps.filter { classId == null || it.classId == classId })
            val snapshot = RecognitionReplayInputs(root, repository, catalog, selected, classId)
            try {
                val catalogFile = File(root, "maps/maps.json").apply { parentFile!!.mkdirs() }
                catalogFile.writeText(Json.encodeToString(selected))
                snapshot.files["maps/maps.json"] = catalogFile
                val mapsRoot = repository.mapsRoot.canonicalFile
                fun copy(source: File, required: Boolean) {
                    val canonical = source.canonicalFile
                    require(canonical.toPath().startsWith(mapsRoot.toPath()) && canonical != mapsRoot) { "Reference escapes private map root" }
                    val entry = "maps/" + canonical.relativeTo(mapsRoot).invariantSeparatorsPath
                    if (entry in snapshot.files) return
                    if (!canonical.isFile) { snapshot.absent += canonical; if (required) snapshot.missing += "reference-missing:$entry"; return }
                    if (canonical.length() > MAX_FILE_BYTES) { snapshot.missing += "reference-exceeds-file-limit:$entry"; return }
                    val dest = File(root, entry).apply { parentFile!!.mkdirs() }
                    runCatching {
                        val beforeSize = canonical.length(); val beforeModified = canonical.lastModified()
                        val hash = MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        canonical.inputStream().use { input -> dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                com.idvb.android.alignment.AlignmentCancellation.checkpoint("diagnostics.recognition-reference-copy")
                                val n = input.read(buffer); if (n < 0) break
                                bytes += n; check(bytes <= MAX_FILE_BYTES)
                                hash.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        } }
                        check(bytes == beforeSize && beforeSize == canonical.length() && beforeModified == canonical.lastModified()) { "Reference changed while spooling" }
                        val copied = hash.digest().joinToString("") { "%02x".format(it) }
                        snapshot.files[entry] = dest
                        snapshot.inputs[entry] = Input(entry, canonical, dest.length(), copied)
                    }.onFailure {
                        if (it is java.util.concurrent.CancellationException) throw it
                        snapshot.missing += "reference-capture-failed:$entry:${it.message}"
                    }
                }
                for (map in selected.maps) {
                    com.idvb.android.alignment.AlignmentCancellation.checkpoint("diagnostics.recognition-reference-map")
                    for (name in listOf("metadata.json", "gates.json", "anchors.json"))
                        copy(File(repository.mapsRoot, "${map.id}/data/$name"), false)
                    for (floor in map.floors) {
                        copy(repository.floorImageFile(map.id, floor.imagePath), true)
                        val assets = repository.loadRecognitionAssets(map.id, floor)
                        assets.recognitionImageFile?.let { copy(it, true) }
                        assets.sideEntranceFeature?.file?.let { copy(it, true) }
                        assets.prebuiltStructureLine?.file?.let { copy(it, true) }
                        floor.recognitionImagePath?.let { copy(File(repository.mapsRoot, it), true) }
                        floor.sideEntranceFeature?.imagePath?.let { copy(File(repository.mapsRoot, it), true) }
                        floor.prebuiltStructureLine?.imagePath?.let { copy(File(repository.mapsRoot, it), true) }
                    }
                }
                snapshot.captureMs = (System.nanoTime() - started) / 1e6
                return snapshot
            } catch (error: Throwable) { snapshot.close(); throw error }
        }
    }
}
