package com.idvb.android.recognize.side

import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.alignment.*
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.*
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in private raw-frame corpus. Never touches the active catalog or shared media.
 * recognitionCorpus=<relative target-cache folder> with maps/maps.json,
 * recognition/ ZIP inputs and expectations.json keyed by archive name.
 * Each expectation may specify confirmedMapIds, requireAutomaticConfirmation,
 * and probeMapIds for independent selected-map alignment on the exact scan frame.
 */
class SparseGateFailureCorpusInstrumentedTest {
    @Test fun replayProductionScanAndIndependentSelectedMapAlignment() {
        val args=InstrumentationRegistry.getArguments()
        val relative=args.getString("recognitionCorpus")
        assumeTrue("No private recognition corpus supplied",!relative.isNullOrBlank())
        assertTrue(OpenCvRuntime.initialize())
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val corpus=File(target.cacheDir,relative!!).canonicalFile
        require(corpus.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
        val cases=File(corpus,"recognition").listFiles { file -> file.extension=="zip" }!!.sortedBy { it.name }
        assertTrue("Empty recognition corpus",cases.isNotEmpty())
        val expectations=JSONObject(File(corpus,"expectations.json").readText())
        val isolated=File(target.filesDir,"test-evidence/recognition-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object: ContextWrapper(target) {
            override fun getApplicationContext()=this
            override fun getFilesDir()=isolated
            override fun getCacheDir()=File(isolated,"cache").apply { mkdirs() }
        }
        val repository=MapRepository(context)
        assertTrue(File(corpus,"maps").copyRecursively(repository.mapsRoot,overwrite=false))
        val reports=JSONArray(); val failures=mutableListOf<String>()
        for (archive in cases) ZipFile(archive).use { zip ->
            val original=JSONObject(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().use { it.readText() })
            val frame=zip.getInputStream(zip.getEntry("captured.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try {
                val region=original.getJSONObject("captureRegion")
                val viewport=ScreenRect(region.getDouble("left"),region.getDouble("top"),
                    region.getDouble("right")-region.getDouble("left"),region.getDouble("bottom")-region.getDouble("top"))
                val classId=original.getString("classId")
                val expectation=expectations.getJSONObject(archive.name)
                val expected=expectation.optJSONArray("confirmedMapIds")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } }.orEmpty()
                SparseGateSearch.clear()
                repeat(2) { run ->
                    val started=System.nanoTime()
                    val result=MapScanRecognizer(context,repository).recognize(frame,viewport,
                        original.getInt("screenWidth"),original.getInt("screenHeight"),classId)
                    val algorithmMs=(System.nanoTime()-started)/1e6
                    val confirmed=result.automaticallyConfirmedCandidate()
                    val required=expectation.optBoolean("requireAutomaticConfirmation",false)
                    val passed=(confirmed==null || expected.isEmpty() || confirmed.map.id in expected) && (!required || confirmed!=null)
                    val diagnostic=RecognitionDiagnosticsStore(context).record(frame,result,CaptureDiagnosticsContext(
                        original.getInt("screenWidth"),original.getInt("screenHeight"),region.getInt("left"),region.getInt("top"),
                        region.getInt("right"),region.getInt("bottom"),classId,original.optString("className"))).getOrThrow()
                    reports.put(JSONObject().put("archive",archive.name).put("run",run).put("phase",if (run==0) "cold" else "warm")
                        .put("algorithmMs",algorithmMs).put("confirmedMapId",confirmed?.map?.id ?: JSONObject.NULL)
                        .put("expectedMapIds",JSONArray(expected)).put("requiresAutomaticConfirmation",required)
                        .put("expectationPassed",passed).put("diagnostics",diagnostic.relativeTo(isolated).path))
                    if (!passed) failures += "${archive.name} run=$run confirmed=${confirmed?.map?.id}; expected=$expected required=$required"
                }
                val probes=expectation.optJSONArray("probeMapIds") ?: JSONArray()
                for (i in 0 until probes.length()) {
                    val map=repository.loadCatalog().maps.single { it.id==probes.getString(i) }
                    val floor=map.floors.single { it.key=="1f" }
                    val trace=AlignmentTrace(captureArtifacts=true)
                    val result=Vpsg(repository).align(AlignmentRequest(frame,viewport,map,floor),trace)
                    val saved=AlignmentDiagnosticsStore(context).record(trace,AlignmentDiagnosticContext("vpsg",map,floor.key,
                        viewport,original.getInt("screenWidth"),original.getInt("screenHeight"),"exact-original-scan-frame"),
                        frame,result,"recognition-corpus-probe").getOrThrow()
                    reports.put(JSONObject().put("archive",archive.name).put("phase","independent-selected-map-probe")
                        .put("mapId",map.id).put("outcome",result.outcome.name).put("result",result.toString())
                        .put("diagnostics",saved.relativeTo(isolated).path))
                }
            } finally { frame.recycle() }
        }
        File(isolated,"summary.json").writeText(reports.toString(2))
        android.util.Log.i("IDVB-RecognitionCorpus","evidence=${isolated.path} failures=${failures.size}")
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
