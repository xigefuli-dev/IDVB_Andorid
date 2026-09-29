package com.idvb.android.recognize.side

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.MapScanRecognizer
import com.idvb.android.recognize.automaticallyConfirmedCandidate
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** Opt-in, read-only original frame replay. Resampling is algorithm coverage,
 * not a substitute for real-device capture and UI validation. */
@RunWith(AndroidJUnit4::class)
class ResolutionReplayTest {
    @Test fun realFramesAcrossResolutions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("resolutionReplay") == "true")
        assertTrue(OpenCvRuntime.initialize())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.idvb.android.AppServices.scanPreparation.awaitIdle()
        val repo = MapRepository(context)
        val expected = requireNotNull(args.getString("expectedMapId"))
        val packages = requireNotNull(args.getString("replayPackages")).split(',')
        val safetyOnly = args.getString("safetyOnlyPackages").orEmpty().split(',').toSet()
        val factors = (args.getString("resolutionFactors") ?: "0.5,0.75,1.0,1.5,2.0").split(',').map(String::toDouble)
        val failures = mutableListOf<String>()
        val report = File(context.cacheDir,"resolution-replay.csv")
        report.writeText("package,factor,screenWidth,screenHeight,run,outcome,selected,scale,support,route,elapsedMs\n")
        for (name in packages) {
            require(File(name).name == name)
            ZipFile(File(context.cacheDir,"resolution-fixtures/files/idvb/diagnostics/recognition/$name")).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().use { it.readText() })
                val original = zip.getInputStream(zip.getEntry("captured.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    for (factor in factors) {
                        val frame = Bitmap.createScaledBitmap(original,(original.width*factor).roundToInt(),
                            (original.height*factor).roundToInt(),true)
                        try {
                            val r=manifest.getJSONObject("captureRegion")
                            val viewport=ScreenRect(r.getDouble("left")*factor,r.getDouble("top")*factor,
                                frame.width.toDouble(),frame.height.toDouble())
                            val width=(manifest.getInt("screenWidth")*factor).roundToInt()
                            val height=(manifest.getInt("screenHeight")*factor).roundToInt()
                            SparseGateSearch.clear()
                            repeat(2) { run ->
                                val start=System.nanoTime()
                                val result=MapScanRecognizer(context,repo).recognize(frame,viewport,width,height,manifest.getString("classId"))
                                val selected=result.automaticallyConfirmedCandidate()
                                assertSame("Preview must retain the original capture",frame,result.capturedRegion)
                                assertFalse("Scanner must not recycle the capture",frame.isRecycled)
                                assertEquals(viewport,result.viewportBounds)
                                assertEquals(minOf(2.0,1440.0/minOf(width,height)),result.processingScale,1e-9)
                                if (selected != null && result.sparseGateDiagnostics != null) {
                                    val map=selected.map
                                    val floor=map.floors.first { it.key == selected.floorKey }
                                    val assets=repo.loadRecognitionAssets(map.id,floor)
                                    val anchor=repo.loadSideDoors(map.id,floor.key).first()
                                    val gate=result.diagnostics!!.gateDetection.gates[selected.associatedGateIndex]
                                    val x=selected.structureOffsetX+(anchor.x+anchor.width/2)*assets.recognitionWidth*selected.structureScale
                                    val y=selected.structureOffsetY+(anchor.y+anchor.height/2)*assets.recognitionHeight*selected.structureScale
                                    assertEquals("Transform and gate must share original screen coordinates",
                                        selected.gateSpatialResidualPixels,kotlin.math.hypot(x-gate.screenBounds.centerX,y-gate.screenBounds.centerY),1e-6)
                                }
                                val outcome=if(selected == null) "UNRESOLVED" else if(selected.map.id == expected) "CORRECT" else "WRONG"
                                val candidate=result.candidates.firstOrNull { it.map.id == expected }
                                report.appendText("$name,$factor,$width,$height,$run,$outcome,${selected?.map?.id},${candidate?.structureScale},${candidate?.edgeCoverage},${result.route},${(System.nanoTime()-start)/1e6}\n")
                                if(outcome == "WRONG" || selected == null && name !in safetyOnly)
                                    failures += "$name factor=$factor run=$run $outcome expectedSupport=${candidate?.edgeCoverage}"
                            }
                        } finally { if(frame !== original) frame.recycle() }
                    }
                } finally { original.recycle() }
            }
        }
        assertTrue("${failures.size} failures; report=${report.path}\n${failures.joinToString("\n")}",failures.isEmpty())
    }
}
