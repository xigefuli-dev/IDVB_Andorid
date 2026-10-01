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
            complete.artifactSnapshot().forEach { (name, bytes) -> missingSource.attach(name) { bytes } }
            val incomplete = AlignmentDiagnosticsStore(noSource).record(missingSource, input, frame, rejected, "rejected").getOrThrow()
            val incompleteJson = Json.parseToJsonElement(store.diagnosticsJson(incomplete).getOrThrow()).jsonObject
            assertFalse(incompleteJson.getValue("sourceSnapshotAvailable").jsonPrimitive.boolean)
            assertFalse(incompleteJson.getValue("replayReady").jsonPrimitive.boolean)
            assertEquals("source-provenance-unavailable", incompleteJson.getValue("replayUnavailableReason").jsonPrimitive.content)
            assertFalse(frame.isRecycled)
        } finally { frame.recycle(); root.deleteRecursively() }
    }
}
