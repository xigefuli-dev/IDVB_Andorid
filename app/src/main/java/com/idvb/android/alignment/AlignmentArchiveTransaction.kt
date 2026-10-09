package com.idvb.android.alignment

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** A durable publication plan. Only a completely staged request may be retried after
 * process death; incomplete requests stay exportable and explicitly non-replayable. */
internal object AlignmentArchiveTransaction {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val scratch = ByteArray(64 * 1024)
        while (true) { val n = input.read(scratch); if (n < 0) break; digest.update(scratch, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun prepare(root: File, journal: AlignmentJournal, destination: File) {
        val relative = destination.relativeTo(root).invariantSeparatorsPath
        requireContained(root, relative)
        val files = journal.artifacts.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()
        require(files.any { it.name == "diagnostics.json" })
        journal.writeState(buildJsonObject {
            put("schemaVersion", 1); put("status", "ready-to-publish")
            put("destination", relative)
            put("entries", buildJsonObject { files.forEach { file ->
                put(file.relativeTo(journal.artifacts).invariantSeparatorsPath, buildJsonObject {
                    put("bytes", file.length()); put("sha256", sha256(file))
                })
            } })
        }.toString())
    }

    /** Test hook can fail after any byte or on close. No source is consumed by a failed attempt. */
    fun publish(root: File, journal: AlignmentJournal,
        openOutput: (File) -> OutputStream = { it.outputStream().buffered() }): File {
        val state = Json.parseToJsonElement(File(journal.directory, "state.json").readText()).jsonObject
        check(state.getValue("schemaVersion").jsonPrimitive.int == 1)
        check(state.getValue("status").jsonPrimitive.content == "ready-to-publish")
        val destination = requireContained(root, state.getValue("destination").jsonPrimitive.content)
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        val temporary = File.createTempFile(".alignment-", ".tmp", destination.parentFile)
        try {
            ZipOutputStream(openOutput(temporary)).use { zip ->
                val scratch = ByteArray(64 * 1024)
                for ((name, entry) in state.getValue("entries").jsonObject) {
                    val source = requireContained(journal.artifacts, name)
                    val expected = entry.jsonObject
                    val size = expected.getValue("bytes").jsonPrimitive.long
                    check(source.length() == size) { "Staged evidence size changed: $name" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    zip.putNextEntry(ZipEntry(name))
                    source.inputStream().use { input ->
                        while (true) {
                            val n = input.read(scratch); if (n < 0) break
                            zip.write(scratch, 0, n); digest.update(scratch, 0, n); copied += n
                        }
                    }
                    check(copied == size && digest.digest().joinToString("") { "%02x".format(it) } ==
                        expected.getValue("sha256").jsonPrimitive.content) { "Staged evidence hash changed: $name" }
                    zip.closeEntry()
                }
            }
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return destination
        } finally { temporary.delete() }
    }

    private fun requireContained(root: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute)
        val result = File(root, relative).canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath()) && result != root.canonicalFile)
        return result
    }
}
