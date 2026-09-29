package com.idvb.android.recognize.side

import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.data.OverlayPrefs
import com.idvb.android.idvm.*
import com.idvb.android.recognize.MapScanRecognizer
import com.idvb.android.recognize.automaticallyConfirmedCandidate
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Opt-in package replay. Imports into an isolated cache repository, never the user's
 * catalog. Only RGB pixels inside the explored circle reach the production scanner;
 * neither the radius, expected identity nor the authored gate is passed to it. */
@RunWith(AndroidJUnit4::class)
class FogRadiusReplayTest {
    @Test fun replayAuthoredDoorAtMultipleExplorationRadii() {
        val args = InstrumentationRegistry.getArguments()
        val packagePath = args.getString("fogPackage")
        assumeNotNull(packagePath)
        assertTrue(OpenCvRuntime.initialize())
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        com.idvb.android.AppServices.scanPreparation.awaitIdle()
        val isolated = File(target.cacheDir, "fog-replay-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val output = File(target.cacheDir, "fog-replay-results").apply { mkdirs() }
        val report = File(output, "results.csv")
        report.writeText("sourceMapId,title,radius,scale,run,totalMs,route,selectedSourceId,outcome,prepareMs,searchMs,registerMs,preloadMs,processKernelPreparationMs\n")
        try {
            val repo = MapRepository(context)
            val imported = IdvmImporter().importPackage(File(packagePath!!), repo.mapsRoot,
                MapCatalogDocument(), repo::saveCatalog)
            assertTrue(imported.toString(), imported is ImportResult.Success)
            val catalog = repo.loadCatalog()
            val width = requireNotNull(args.getString("screenWidth")).toInt()
            val height = requireNotNull(args.getString("screenHeight")).toInt()
            val region = requireNotNull(OverlayPrefs(target).captureRegion(width > height)) {
                "Current device has no calibration for this orientation"
            }
            // Match capture's integer crop coordinates, retaining screen origin.
            val left = (region[0]*width).toInt(); val top = (region[1]*height).toInt()
            val right = (region[2]*width).toInt(); val bottom = (region[3]*height).toInt()
            val viewport = ScreenRect(left.toDouble(),top.toDouble(),(right-left).toDouble(),(bottom-top).toDouble())
            val radii = (args.getString("radii") ?: "96,160,240,360").split(',').map(String::toInt)
            val scales = (args.getString("scales") ?: "1.0").split(',').map(String::toFloat)
            val limit = args.getString("mapLimit")?.toInt() ?: catalog.maps.size
            val offset = args.getString("mapOffset")?.toInt() ?: 0
            val numbers = args.getString("mapNumbers")?.split(',')?.map(String::toInt)
            val repeats = args.getString("repeats")?.toInt() ?: 2
            Log.i("IDVB-Fog", "screen=${width}x$height viewport=$viewport maps=${catalog.maps.size} radii=$radii scales=$scales")
            var incorrect = 0
            var overBudget = 0
            val maximumMs = args.getString("maxScanMs")?.toDouble()
            SparseGateSearch.clear()
            val selectedMaps = if (numbers == null) catalog.maps.drop(offset).take(limit)
                else numbers.map { catalog.maps[it-1] }
            for (map in selectedMaps) {
                val floorKey = catalog.classes.first { it.id == map.classId }.scanFloorKey
                val floor = if (floorKey == null) map.floors.minBy { it.sortOrder } else map.floors.first { it.key == floorKey }
                val assets = repo.loadRecognitionAssets(map.id, floor)
                val full = requireNotNull(BitmapFactory.decodeFile((assets.recognitionImageFile ?:
                    repo.floorImageFile(map.id, floor.imagePath)).path))
                val crop = assets.recognitionRegion.takeIf { assets.recognitionImageFile == null }
                val source = if (crop == null) full else {
                    val x = kotlin.math.floor(crop.x*full.width).toInt().coerceIn(0,full.width-1)
                    val y = kotlin.math.floor(crop.y*full.height).toInt().coerceIn(0,full.height-1)
                    val r = ceil((crop.x+crop.width)*full.width).toInt().coerceIn(x+1,full.width)
                    val b = ceil((crop.y+crop.height)*full.height).toInt().coerceIn(y+1,full.height)
                    Bitmap.createBitmap(full,x,y,r-x,b-y)
                }
                try {
                    val door = repo.loadSideDoors(map.id, floor.key).first()
                    val ax = ((door.x+door.width/2)*assets.recognitionWidth).toFloat()
                    val ay = ((door.y+door.height/2)*assets.recognitionHeight).toFloat()
                    for (scale in scales) for (radius in radii) {
                        val frame = Bitmap.createBitmap(right-left,bottom-top,Bitmap.Config.ARGB_8888)
                        try {
                            val gx = frame.width/2f; val gy = frame.height/2f
                            val canvas = Canvas(frame)
                            canvas.drawColor(Color.BLACK)
                            canvas.save()
                            canvas.clipPath(Path().apply { addCircle(gx,gy,radius.toFloat(),Path.Direction.CW) })
                            canvas.translate(gx-ax*scale,gy-ay*scale)
                            canvas.scale(scale,scale)
                            canvas.drawBitmap(source,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
                            canvas.restore()
                            if (map == selectedMaps.first() && scale == scales.first())
                                File(output,"radius-$radius.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                            repeat(repeats) { run ->
                                if (args.getString("coldEach") == "true") {
                                    SparseGateSearch.clear()
                                    com.idvb.android.recognize.vpsg.VpsgPreparedIndex.clear()
                                }
                                var preloadMs = 0.0
                                if (args.getString("preload") == "true") {
                                    com.idvb.android.recognize.RecognitionPreparation(repo) { map.classId }.use { preparation ->
                                        preparation.request()
                                        val state = preparation.awaitIdle()
                                        assertEquals("Preload must cover all identities",catalog.maps.count { it.classId == map.classId },state.ready)
                                        preloadMs = state.elapsedMs
                                    }
                                }
                                val started = System.nanoTime()
                                val concurrentPreparation = if (args.getString("preload") == "concurrent")
                                    com.idvb.android.recognize.RecognitionPreparation(repo) { map.classId }.also { it.request() }
                                    else null
                                var elapsed = 0.0
                                val result = try {
                                    MapScanRecognizer(target,repo).recognize(frame,viewport,width,height,map.classId)
                                } finally {
                                    elapsed = (System.nanoTime()-started)/1e6
                                    try { concurrentPreparation?.awaitIdle() }
                                    finally { concurrentPreparation?.close() }
                                }
                                if (maximumMs != null && elapsed >= maximumMs) overBudget++
                                val selected = result.automaticallyConfirmedCandidate()
                                val correct = selected?.map?.sourceMapId == map.sourceMapId || selected != null &&
                                    catalog.variantGroups.any { map.id in it.mapIds && selected.map.id in it.mapIds }
                                val outcome = if (selected == null) "unresolved" else if (correct) "correct" else "WRONG"
                                if (outcome == "WRONG") incorrect++
                                val diag = result.sparseGateDiagnostics
                                if (selected == null && run == 0) {
                                    val details = result.candidates.take(8).joinToString { candidate ->
                                        "${candidate.map.title}:cost=${candidate.structureCompositeCost}:support=${candidate.edgeCoverage}:scale=${candidate.matchScale}:occupancy=${candidate.occupancyCoverage}"
                                    }
                                    Log.i("IDVB-Fog-Detail", "expected=${map.title} radius=$radius supported=${diag?.supportedIdentityCount} complete=${diag?.retrievalComplete} top=$details")
                                }
                                val row = "${map.sourceMapId},${map.title},$radius,$scale,$run,$elapsed,${result.route},${selected?.map?.sourceMapId.orEmpty()},$outcome,${diag?.preparationMilliseconds},${diag?.searchAndVerificationMilliseconds},${diag?.registrationMilliseconds},$preloadMs"
                                report.appendText("$row,${SparseGateSearch.kernelPreparationMilliseconds}\n")
                                Log.i("IDVB-Fog",row)
                            }
                        } finally { frame.recycle() }
                    }
                } finally { if (source !== full) source.recycle(); full.recycle() }
            }
            assertEquals("Incorrect automatic identity; see ${report.path}",0,incorrect)
            assertEquals("Scans exceeded requested ${maximumMs}ms; see ${report.path}",0,overBudget)
            // Unresolved cases and >500ms cases are reported, never relabelled passes.
        } finally { SparseGateSearch.clear(); isolated.deleteRecursively() }
    }
}
