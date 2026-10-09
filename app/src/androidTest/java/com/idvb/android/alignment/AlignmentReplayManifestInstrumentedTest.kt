package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AlignmentReplayManifestInstrumentedTest {
    @Test fun largeTimelineDoesNotChangeOrRetainRequestMetadata() {
        // Instrumentation executes under the isolated target UID, which cannot
        // create files in the test package's separate private data directory.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "large-manifest-${UUID.randomUUID()}.zip")
        val request = """{"schemaVersion":1,"replayReady":true,"artifacts":{},"map":{"title":"原始地图","floors":[{"key":"2f","sortOrder":2}],"optional":null},"floorKey":"2f","viewport":{"x":708.0,"y":85.0,"width":608.0,"height":497.0},"result":{"outcome":"REJECTED","code":"unique-pose"},"methodId":"vpsg","floorSelectedByIndicator":true}"""
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.json"))
                zip.write(request.dropLast(1).toByteArray())
                zip.write(",\"events\":[".toByteArray())
                val padding = ByteArray(8192) { 32 }
                repeat(68 * 128) { zip.write(padding) }
                zip.write("{\"series\":{\"values\":[0,0.5,-2]},\"labels\":{\"detail\":\"可跳过但不删除\"}}]}".toByteArray())
                zip.closeEntry()
            }
            ZipFile(archive).use { zip ->
                assertTrue(zip.getEntry("diagnostics.json").size > 64L * 1024 * 1024)
                val loaded = AlignmentReplayManifest.read(zip)
                assertEquals(Json.parseToJsonElement(request), loaded)
                assertFalse(loaded.containsKey("events"))
            }
        } finally { archive.delete() }
    }

    @Test fun originalCorpusMetadataMatchesThePreviousReaderExactly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val relative = requireNotNull(InstrumentationRegistry.getArguments().getString("alignmentCorpus"))
        val root = File(instrumentation.targetContext.cacheDir, relative).canonicalFile
        require(root.toPath().startsWith(instrumentation.targetContext.cacheDir.canonicalFile.toPath()))
        val archives = root.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }
        assertTrue(archives.isNotEmpty())
        for (archive in archives) ZipFile(archive).use { zip ->
            val old = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics.json"))
                .bufferedReader().use { it.readText() }).jsonObject
            val streamed = AlignmentReplayManifest.read(zip)
            assertEquals(archive.name, JsonObject(old.filterKeys { it in streamed }), streamed)
        }
    }
}
