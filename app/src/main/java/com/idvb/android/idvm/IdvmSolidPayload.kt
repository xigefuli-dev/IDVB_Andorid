package com.idvb.android.idvm

import kotlinx.serialization.json.Json
import org.brotli.dec.BrotliInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object IdvmSolidPayload {
    fun expand(source: File, staging: File, json: Json): File? = ZipFile(source).use { zip ->
        val headerBytes = zip.getInputStream(requireNotNull(zip.getEntry("header"))).use {
            IdvmUtil.readBounded(it, IdvmHeader.SIZE.toLong())
        }
        val header = IdvmHeader.parse(headerBytes)
        if (header.formatMinor != 6) return null
        val entries = zip.entries().asSequence().toList()
        require(entries.size == 3 && entries.none { it.isDirectory } &&
            entries.map { it.name }.toSet() == setOf("header", "manifest.json", "payload.br"))
        val rawManifest = zip.getInputStream(zip.getEntry("manifest.json")).use {
            IdvmUtil.readBounded(it, IdvmLimits.MAX_JSON_BYTES)
        }
        val manifest = json.decodeFromString<IdvmManifest>(rawManifest.decodeToString())
        IdvmValidator.validateHeaderAndManifest(headerBytes, header, rawManifest, manifest)
        require(manifest.files.size + 2 <= IdvmLimits.MAX_ENTRIES)
        var logicalSize = 0L
        manifest.files.forEach {
            require(it.size in 0..IdvmLimits.MAX_SINGLE_FILE_BYTES && it.storageOffset != null && it.storageOffset >= 0)
            logicalSize += it.size
            require(logicalSize <= IdvmLimits.MAX_TOTAL_BYTES)
        }
        val groups = manifest.files.groupBy { Triple(it.storageOffset!!, it.size, it.sha256) }
            .entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        var expandedSize = 0L
        val hashes = HashSet<String>()
        groups.forEach { (key, _) ->
            require(key.first == expandedSize && hashes.add(key.third))
            expandedSize += key.second
        }
        val payload = zip.getEntry("payload.br")
        require(payload.size in 1..IdvmLimits.MAX_TOTAL_BYTES)
        val directory = File(staging, ".solid").apply { require(mkdirs()) }
        val prepared = File(directory, "expanded.idvm")
        ZipOutputStream(prepared.outputStream().buffered()).use { output ->
            output.setLevel(0)
            fun entry(name: String, bytes: ByteArray) {
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
            entry("header", headerBytes)
            entry("manifest.json", rawManifest)
            zip.getInputStream(payload).use { compressed ->
                BrotliInputStream(compressed).use { decoded ->
                    val buffer = ByteArray(65536)
                    for ((index, group) in groups.withIndex()) {
                        val file = File(directory, index.toString())
                        val digest = MessageDigest.getInstance("SHA-256")
                        var remaining = group.key.second
                        file.outputStream().use { target ->
                            while (remaining > 0) {
                                val count = decoded.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                require(count > 0)
                                target.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        require(IdvmUtil.sha256Hex(digest.digest()) == group.key.third)
                        for (logical in group.value) {
                            output.putNextEntry(ZipEntry(logical.path))
                            file.inputStream().use { it.copyTo(output) }
                            output.closeEntry()
                        }
                        require(file.delete())
                    }
                    require(decoded.read() == -1 && compressed.read() == -1)
                }
            }
        }
        prepared
    }
}
