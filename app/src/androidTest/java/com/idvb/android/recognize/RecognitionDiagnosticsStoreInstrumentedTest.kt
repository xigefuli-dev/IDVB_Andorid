package com.idvb.android.recognize

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionDiagnosticsStoreInstrumentedTest {
    @Test
    fun savedScanCanBeCopiedAsJsonAndExportedAsZip() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val frame = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val store = RecognitionDiagnosticsStore(context)
            val result = RecognitionResult(frame, emptyList(), sparseGateDiagnostics = SparseGateScanDiagnostics(
                12.0, 2.0, 8.0, 2.0, 1, 500, true, 1, true,
                listOf(SparseGateFloorEvidence("fixture", "1f", 2, .98, .8, 4.0, false, true))))
            val saved = store.record(frame, result,
                CaptureDiagnosticsContext(32, 32, 0, 0, 32, 32, null, null)).getOrThrow()
            assertEquals(saved, store.recentPackages().single())
            val json = store.diagnosticsJson(saved).getOrThrow()
            assertTrue(json.contains("\"capturedImage\":\"captured.png\""))
            val parsed = org.json.JSONObject(json)
            val sparse = parsed.getJSONObject("sparseGateScan")
            assertEquals(1, sparse.getInt("evaluatedFloorCount"))
            assertTrue(sparse.getBoolean("identityUnique"))
            assertEquals(.98, sparse.getJSONArray("floorEvidence").getJSONObject(0).getDouble("supportedFraction"), 0.0)
            assertTrue(parsed.isNull("desktopReferenceCommit"))
            val exported = File(isolated, "exported.zip")
            store.exportPackage(saved, target, Uri.fromFile(exported)).getOrThrow()
            assertArrayEquals(saved.readBytes(), exported.readBytes())
        } finally {
            frame.recycle()
            isolated.deleteRecursively()
        }
    }
}
