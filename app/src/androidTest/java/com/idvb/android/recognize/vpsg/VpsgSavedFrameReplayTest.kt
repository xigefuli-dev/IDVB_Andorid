package com.idvb.android.recognize.vpsg

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.SideEntranceRecognizer
import com.idvb.android.recognize.MapScanRecognizer
import com.idvb.android.recognize.automaticallyConfirmedCandidate
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only replay of an existing diagnostic package. No screenshots/maps are bundled. */
@RunWith(AndroidJUnit4::class)
class VpsgSavedFrameReplayTest {
    @Test fun replayColdAndWarmWithSideFallback() {
        val name = InstrumentationRegistry.getArguments().getString("replayPackage")
        assumeNotNull(name)
        require(File(name!!).name == name)
        assertTrue(OpenCvRuntime.initialize())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = MapRepository(context)
        ZipFile(File(context.filesDir, "idvb/diagnostics/recognition/$name")).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("diagnostics.json"))
                .bufferedReader().use { it.readText() })
            val frame = zip.getInputStream(zip.getEntry("captured.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try {
                val r = manifest.getJSONObject("captureRegion")
                val viewport = ScreenRect(r.getDouble("left"), r.getDouble("top"),
                    r.getDouble("right") - r.getDouble("left"), r.getDouble("bottom") - r.getDouble("top"))
                val classId = manifest.getString("classId")
                val maps = repository.loadCatalog().maps.filter { it.classId == classId }
                val saved = manifest.getJSONArray("candidates")
                val expected = InstrumentationRegistry.getArguments().getString("expectedMapId")?.let { listOf(it) }
                    ?: (0 until saved.length()).map { saved.getJSONObject(it) }
                    .filter { it.getString("disposition") == "RELIABLE" }.map { it.getString("localMapId") }
                assertEquals(1, expected.size)
                com.idvb.android.recognize.side.SparseGateSearch.clear()
                if (InstrumentationRegistry.getArguments().getString("sparseOnly") != "true") {
                VpsgPreparedIndex.clear()
                repeat(2) { run ->
                    val start = System.nanoTime()
                    val result = requireNotNull(VpsgLineScanner(repository).recognize(frame, viewport, maps))
                    val confirmed = result.candidates.filter { it.disposition == CandidateDisposition.RELIABLE }
                    val diagnostics = requireNotNull(result.vpsgDiagnostics)
                    assertEquals(maps.size, diagnostics.evaluatedFloorCount)
                    assertEquals(maps.size, diagnostics.floorTimings.size)
                    Log.i("IDVB-Replay", "run=$run vpsgMs=${diagnostics.elapsedMilliseconds}" +
                        " prepareMs=${diagnostics.floorTimings.sumOf { it.prepareMilliseconds }}" +
                        " translationMs=${diagnostics.floorTimings.sumOf { it.translationMilliseconds }}" +
                        " refinementMs=${diagnostics.floorTimings.sumOf { it.refinementMilliseconds }}" +
                        " verificationMs=${diagnostics.floorTimings.sumOf { it.verificationMilliseconds }}" +
                        " refined=${diagnostics.floorTimings.sumOf { it.refinedCount }} best=" +
                        result.candidates.take(3).map { "${it.map.title}:${it.edgeCoverage}:${it.matchScale}" })
                    assertTrue("VPSG must not confirm a different map", confirmed.all { it.map.id in expected })
                    val final = if (confirmed.size == 1) result else SideEntranceRecognizer(context, repository, classId)
                        .recognize(frame, viewport, manifest.getInt("screenWidth"), manifest.getInt("screenHeight"))
                    assertEquals(expected, final.candidates.filter { it.disposition == CandidateDisposition.RELIABLE }.map { it.map.id })
                    Log.i("IDVB-Replay", "run=$run fullMs=${(System.nanoTime() - start) / 1e6} confirmed=$expected")
                }
                val serial = requireNotNull(VpsgLineScanner(repository, parallelism = 1).recognize(frame, viewport, maps))
                val parallel = requireNotNull(VpsgLineScanner(repository).recognize(frame, viewport, maps))
                assertTrue("Parallelism changed candidate evidence", serial.candidates == parallel.candidates)
                Log.i("IDVB-Replay", "equivalent serialMs=${serial.vpsgDiagnostics!!.elapsedMilliseconds}" +
                    " parallelMs=${parallel.vpsgDiagnostics!!.elapsedMilliseconds}")
                }
                repeat(2) { run ->
                    val start = System.nanoTime()
                    val result = MapScanRecognizer(context, repository).recognize(frame, viewport,
                        manifest.getInt("screenWidth"), manifest.getInt("screenHeight"), classId)
                    assertEquals(expected, result.candidates.filter { it.disposition == CandidateDisposition.RELIABLE }.map { it.map.id })
                    assertEquals(expected.single(), result.automaticallyConfirmedCandidate()?.map?.id)
                    if (InstrumentationRegistry.getArguments().getString("sparseOnly") == "true") {
                        assertEquals(com.idvb.android.recognize.side.SparseGateRecognizer.ROUTE,result.route)
                        val evidence=requireNotNull(result.sparseGateDiagnostics)
                        assertTrue(evidence.retrievalComplete)
                        assertTrue(evidence.identityUnique)
                        assertEquals(maps.size,evidence.evaluatedFloorCount)
                        assertEquals(1,evidence.supportedIdentityCount)
                    }
                    Log.i("IDVB-Replay", "hybrid run=$run fullMs=${(System.nanoTime() - start) / 1e6}" +
                        " usedVpsg=${result.vpsgDiagnostics != null} confirmed=$expected")
                }
            } finally { frame.recycle() }
        }
    }
}
