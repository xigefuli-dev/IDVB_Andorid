package com.idvb.android.alignment

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AlignmentDiagnosticsInstrumentedTest {
    @Test fun replayFramesAreReleasedBeforeSlowWriterAndRoundTripExactly() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        // Instrumentation runs as the target UID; the separately installed test APK's UID is not writable.
        val root = File(target.cacheDir, "alignment-spool-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val store = AlignmentDiagnosticsStore(context)
        val writer = AlignmentDiagnosticsStore::class.java.getDeclaredField("writer")
            .apply { isAccessible = true }.get(store) as java.util.concurrent.ExecutorService
        val blocked = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val saved = java.util.concurrent.CopyOnWriteArrayList<Result<File>>()
        val floor = FloorRecord("1f", "test", 1, "fixture", 513, 257)
        val map = MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
        val input = AlignmentDiagnosticContext("vpsg", map, floor.key,
            ScreenRect(0.0, 0.0, 513.0, 257.0), 513, 257, "test")
        try {
            writer.execute { blocked.countDown(); check(proceed.await(30, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repeat(12) { index ->
                val frame = Bitmap.createBitmap(513, 257, Bitmap.Config.ARGB_8888)
                frame.eraseColor(android.graphics.Color.rgb(index * 17, 97, 211))
                frame.setPixel(512, 256, android.graphics.Color.WHITE)
                val trace = AlignmentTrace(captureArtifacts = true, spoolDirectory = File(root, "spool"))
                trace.attachStream("reference.png") { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                store.recordAsync(trace, input, frame, AlignmentResult.Rejected("fixture", "fixture"), "rejected") { saved += it }
                assertTrue("Writer backlog must never retain the original bitmap", frame.isRecycled)
            }
            proceed.countDown(); store.awaitIdle()
            assertEquals(12, saved.size)
            saved.forEachIndexed { index, result ->
                val file = result.getOrThrow()
                ZipFile(file).use { zip ->
                    val decoded = zip.getInputStream(zip.getEntry("captured.png")).use(android.graphics.BitmapFactory::decodeStream)
                    try {
                        assertEquals(513, decoded.width); assertEquals(257, decoded.height)
                        assertEquals(android.graphics.Color.rgb(index * 17, 97, 211), decoded.getPixel(0, 0))
                        assertEquals(android.graphics.Color.WHITE, decoded.getPixel(512, 256))
                    } finally { decoded.recycle() }
                }
                AlignmentPackageReplay.open(context, file).close()
                assertTrue(Json.parseToJsonElement(store.diagnosticsJson(file).getOrThrow()).jsonObject
                    .getValue("replayReady").jsonPrimitive.boolean)
            }
            assertTrue(File(root, "idvb/diagnostics/spool").listFiles().orEmpty().isEmpty())
            assertTrue(File(root, "spool").listFiles().orEmpty().isEmpty())
        } finally {
            proceed.countDown(); writer.shutdown(); writer.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun newStoreRecoversPreparedArchiveWithoutChangingRecordedSourceOrPixels() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "alignment-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val workers = mutableListOf<java.util.concurrent.ExecutorService>()
        fun store() = AlignmentDiagnosticsStore(context).also { value ->
            workers += AlignmentDiagnosticsStore::class.java.getDeclaredField("writer")
                .apply { isAccessible = true }.get(value) as java.util.concurrent.ExecutorService
        }
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff357fa1.toInt()) }
        try {
            val floor = FloorRecord("1f", "test", 1, "fixture", 100, 100)
            val map = MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
            val input = AlignmentDiagnosticContext("vpsg", map, floor.key,
                ScreenRect(0.0, 0.0, 100.0, 100.0), 100, 100, "test")
            val first = store()
            first.awaitIdle()
            val trace = AlignmentTrace(captureArtifacts = true)
            trace.attachStream("reference.png") { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val original = first.record(trace, input, frame, AlignmentResult.Rejected("fixture", "fixture"), "rejected").getOrThrow()
            val historyRoot = File(root, "idvb/diagnostics")
            val journal = AlignmentJournal(File(historyRoot, "spool/restart-fixture"))
            ZipFile(original).use { zip -> zip.entries().asSequence().forEach { entry ->
                val destination = File(journal.artifacts, entry.name)
                require(destination.canonicalFile.toPath().startsWith(journal.artifacts.canonicalFile.toPath()))
                AlignmentJournal.publish(destination) { output -> zip.getInputStream(entry).use { it.copyTo(output) } }
            } }
            val recovered = File(original.parentFile, "alignment-restored.zip")
            AlignmentArchiveTransaction.prepare(historyRoot, journal, recovered)
            // Only files survive this handoff; no original trace or write callback is reused.
            val second = store()
            second.awaitIdle()
            assertTrue(recovered.isFile)
            assertFalse(journal.directory.exists())
            ZipFile(original).use { before -> ZipFile(recovered).use { after ->
                before.entries().asSequence().forEach { entry ->
                    assertArrayEquals(before.getInputStream(entry).use { it.readBytes() },
                        after.getInputStream(after.getEntry(entry.name)).use { it.readBytes() })
                }
            } }
            AlignmentPackageReplay.open(context, recovered).use { assertTrue(it.sourceMatches) }
        } finally {
            workers.forEach { it.shutdown(); it.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }
            frame.recycle(); root.deleteRecursively()
        }
    }

    @Test fun asyncNumericRecordingDoesNotQueueAnUnusedFrameAndKeepsItsDimensions() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "alignment-release-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val store = AlignmentDiagnosticsStore(context)
        val writer = AlignmentDiagnosticsStore::class.java.getDeclaredField("writer")
            .apply { isAccessible = true }.get(store) as java.util.concurrent.ExecutorService
        val blocked = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicReference<Result<File>>()
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            writer.execute { blocked.countDown(); check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val floor = FloorRecord("1f", "test", 1, "fixture", 100, 100)
            val map = MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
            val input = AlignmentDiagnosticContext("vpsg", map, floor.key,
                ScreenRect(0.0, 0.0, 100.0, 100.0), 100, 100, "test")
            store.recordAsync(AlignmentTrace(captureArtifacts = false), input, frame,
                AlignmentResult.Rejected("fixture", "fixture"), "rejected") { saved.set(it) }
            assertTrue("Numeric diagnostics must not retain a frame behind a slow writer", frame.isRecycled)
            proceed.countDown()
            store.awaitIdle()
            val file = saved.get().getOrThrow()
            val json = Json.parseToJsonElement(store.diagnosticsJson(file).getOrThrow()).jsonObject
            assertEquals(100, json.getValue("frameWidth").jsonPrimitive.int)
            assertEquals(100, json.getValue("frameHeight").jsonPrimitive.int)
            assertEquals("input-retention-disabled", json.getValue("replayUnavailableReason").jsonPrimitive.content)
            ZipFile(file).use { assertNull(it.getEntry("captured.png")) }
        } finally {
            proceed.countDown(); writer.shutdown(); writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!frame.isRecycled) frame.recycle()
            root.deleteRecursively()
        }
    }

    @Test fun incompleteInputsStayExplicitAndTamperedInputsFailIntegrityChecks() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "alignment-diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val floor = FloorRecord("1f", "test", 1, "fixture", 100, 100)
            val map = MapRecord("map", "class", "fixture", "test", 1, listOf(floor))
            val input = AlignmentDiagnosticContext("vpsg", map, floor.key, ScreenRect(0.0, 0.0, 100.0, 100.0), 100, 100, "test")
            val store = AlignmentDiagnosticsStore(context)
            val numeric = AlignmentTrace(captureArtifacts = false)
            numeric.emit(AlignmentLogEvent("test.gate", thresholds = mapOf("minimum" to 2.0),
                gates = listOf(AlignmentGate("minimum", 1.0, ">=", 2.0, false)), durationNanos = 100L))
            val rejected = AlignmentResult.Rejected("not enough evidence", "fixture-rejection")
            val numericFile = store.record(numeric, input, frame, rejected, "rejected").getOrThrow()
            val numericJson = Json.parseToJsonElement(store.diagnosticsJson(numericFile).getOrThrow()).jsonObject
            assertFalse(numericJson.getValue("replayReady").jsonPrimitive.boolean)
            assertEquals("input-retention-disabled", numericJson.getValue("replayUnavailableReason").jsonPrimitive.content)
            assertTrue(numericJson.getValue("events").jsonArray.any { it.jsonObject["stage"]?.jsonPrimitive?.content == "test.gate" })
            assertTrue(numericJson.getValue("diagnosticsWrite").jsonObject.getValue("archiveWriteTotalMs").jsonPrimitive.double > 0)
            assertTrue(runCatching { AlignmentPackageReplay.open(context, numericFile).close() }.isFailure)

            val complete = AlignmentTrace(captureArtifacts = true)
            complete.attach("reference.png") { ByteArrayOutputStream().use { out ->
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            } }
            val archive = store.record(complete, input, frame, rejected, "rejected").getOrThrow()
            val corrupt = File(root, "corrupted.zip")
            ZipFile(archive).use { source -> ZipOutputStream(corrupt.outputStream()).use { destination ->
                source.entries().asSequence().forEach { entry ->
                    destination.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "reference.png") bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    destination.write(bytes); destination.closeEntry()
                }
            } }
            val failure = runCatching { AlignmentPackageReplay.open(context, corrupt).close() }.exceptionOrNull()
            assertTrue("Tampered reference must fail before algorithm execution: $failure",
                failure?.message?.contains("hash mismatch") == true)
            val noSource = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getAssets() = android.content.res.Resources.getSystem().assets
            }
            val missingSource = AlignmentTrace(captureArtifacts = true)
            // A published spool is consumed. Supply fresh reference bytes so this fixture
            // isolates missing provenance rather than accidentally omitting both inputs.
            missingSource.attach("reference.png") { ByteArrayOutputStream().use { out ->
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            } }
            val incomplete = AlignmentDiagnosticsStore(noSource).record(missingSource, input, frame, rejected, "rejected").getOrThrow()
            val incompleteJson = Json.parseToJsonElement(store.diagnosticsJson(incomplete).getOrThrow()).jsonObject
            assertFalse(incompleteJson.getValue("sourceSnapshotAvailable").jsonPrimitive.boolean)
            assertFalse(incompleteJson.getValue("replayReady").jsonPrimitive.boolean)
            assertEquals("source-provenance-unavailable", incompleteJson.getValue("replayUnavailableReason").jsonPrimitive.content)
            assertFalse(frame.isRecycled)
        } finally { frame.recycle(); root.deleteRecursively() }
    }
}
