package com.idvb.android.idvm

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class IdvmCommunityPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun packages(): List<File> {
        val root = System.getProperty("idvb.community.packages")?.let(::File)
        assumeTrue(root?.isDirectory == true)
        return root!!.listFiles()!!.filter { it.extension == "idvm" }.sortedBy { it.name }
            .also { assertEquals(6, it.size) }
    }

    @Test fun actualDownloadedPackagesEnterCatalogWithEveryDeclaredAsset() {
        val root = tmp.newFolder()
        var catalog = MapCatalogDocument()
        for (file in packages()) {
            val result = IdvmImporter().importPackage(file, root, catalog) { catalog = it }
            val success = result as? ImportResult.Success
                ?: error("${file.name}: ${(result as ImportResult.Failure).reason}")
            val manifest = ZipFile(file).use { zip ->
                IdvmJson.instance.decodeFromString<IdvmManifest>(zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader().use { it.readText() })
            }
            assertEquals(manifest.maps.size, success.importedMaps.size)
            assertEquals(manifest.classes.size, success.importedClasses.size)
            for (map in success.importedMaps) {
                val source = manifest.maps.single { it.mapId == map.sourceMapId }
                assertEquals(source.floors.size, map.floors.size)
                for (asset in manifest.files.filter { it.path.startsWith("${source.root}/") }) {
                    val installed = File(root, "${map.id}/${asset.path.removePrefix("${source.root}/")}")
                    assertEquals(asset.size, installed.length())
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    installed.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    assertEquals(asset.sha256, IdvmUtil.sha256Hex(digest.digest()))
                }
            }
        }
        assertTrue(catalog.maps.isNotEmpty())
        assertFalse(root.listFiles()!!.any { it.name.startsWith('.') })
    }

    @Test fun truncatedOrSuffixedPayloadNeverCommitsCatalog() {
        val source = packages().first()
        for (suffix in listOf(false, true)) {
            val corrupted = tmp.newFile()
            ZipFile(source).use { zip ->
                ZipOutputStream(corrupted.outputStream()).use { out ->
                    out.setLevel(0)
                    for (entry in zip.entries()) {
                        out.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { input ->
                            if (entry.name == "payload.br" && !suffix) {
                                var remaining = entry.size - 1
                                val buffer = ByteArray(65536)
                                while (remaining > 0) {
                                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                    assertTrue(count > 0)
                                    out.write(buffer, 0, count)
                                    remaining -= count
                                }
                            } else input.copyTo(out)
                        }
                        if (entry.name == "payload.br" && suffix) out.write(byteArrayOf(1, 2, 3))
                        out.closeEntry()
                    }
                }
            }
            val root = tmp.newFolder()
            var committed = false
            val result = IdvmImporter().importPackage(corrupted, root, MapCatalogDocument()) { committed = true }
            assertTrue(result.toString(), result is ImportResult.Failure)
            assertFalse(committed)
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }
}
