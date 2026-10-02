package com.idvb.android.recognize.side

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.*
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.automaticallyConfirmedCandidate
import com.idvb.android.recognize.cv.*
import com.idvb.android.recognize.gate.*
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SparseGateRecognizerInstrumentedTest {
    @Test fun wholeIdentityVerificationRejectsDuplicateAndIncompleteCatalogs() {
        assertTrue(OpenCvRuntime.initialize())
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir,"sparse-gate-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object: ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val frame = Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color=Color.rgb(160,120,105) }
        canvas.drawRect(30f,25f,160f,165f,paint)
        canvas.drawRect(185f,55f,295f,218f,paint)
        paint.color=Color.rgb(110,132,160)
        canvas.drawRect(150f,95f,205f,135f,paint)
        val color = CvImages.bitmapToBgr(frame)
        try {
            VpsgLiveExtractor.extract(color).use { observed ->
                val lines = Mat.zeros(400,500,CvType.CV_8UC1)
                val reference = Mat.zeros(400,500,CvType.CV_8UC3)
                try {
                    val lineRoi=lines.submat(Rect(90,70,320,240)); try { observed.edges.copyTo(lineRoi) } finally { lineRoi.release() }
                    val imageRoi=reference.submat(Rect(90,70,320,240)); try { color.copyTo(imageRoi) } finally { imageRoi.release() }
                    val repo = MapRepository(context)
                    fun add(id: String): MapRecord {
                        val root=File(repo.mapsRoot,id).apply { mkdirs() }
                        File(root,"data").mkdirs()
                        assertTrue(Imgcodecs.imwrite(File(root,"floor.png").path,reference))
                        val lineFile=File(root,"line.png")
                        assertTrue(Imgcodecs.imwrite(lineFile.path,lines))
                        val gates=GatesDocument(1,listOf(Gate("side","1f","sideEntrance",NormalizedRect(.26,.45,.04,.05))))
                        File(root,"data/gates.json").writeText(IdvmJson.instance.encodeToString(GatesDocument.serializer(),gates))
                        return MapRecord(id=id,classId="c",title=id,sourceMapId=id,mapVersion=1,
                            floors=listOf(FloorRecord(key="1f",displayName="1F",sortOrder=1,
                                imagePath="$id/floor.png",imageWidth=500,imageHeight=400,recognitionWidth=500,recognitionHeight=400,
                                prebuiltStructureLine=PrebuiltStructureLineRecord(imagePath="$id/line.png",sha256="fixture",
                                    width=500,height=400,fileLength=lineFile.length(),algorithmId="fixture"))))
                    }
                    val a=add("a"); val b=add("b")
                    val viewport=ScreenRect(100.0,50.0,320.0,240.0)
                    val gate=GateDetection(.95,1.0,ScreenRect(145.0,165.0,10.0,10.0))
                    val rules=SideEntranceScanConfig(minimumScale=.8,maximumScale=1.2)
                    fun run(maps: List<MapRecord>, detected: List<GateDetection> = listOf(gate),
                        groups: List<MapVariantGroupRecord> = emptyList()):
                        com.idvb.android.recognize.RecognitionResult {
                        repo.saveCatalog(MapCatalogDocument(classes=listOf(ClassRecord("c","test",scanFloorKey="1f")),maps=maps,variantGroups=groups))
                        return requireNotNull(SparseGateRecognizer(repo).recognize(frame,viewport,maps,GateDetectionResult(gates=detected),rules))
                    }
                    SparseGateSearch.clear()
                    var activeClass = "c"
                    com.idvb.android.recognize.RecognitionPreparation(repo) { activeClass }.use { preparation ->
                        repo.onCatalogChanged = { preparation.request() }
                        try {
                            repo.saveCatalog(MapCatalogDocument(classes=listOf(
                                ClassRecord("c","test",scanFloorKey="1f"),
                                ClassRecord("other","other",scanFloorKey="1f")),
                                maps=listOf(a,b.copy(classId="other"))))
                            val prepared = preparation.awaitIdle()
                            assertEquals("c",prepared.classId)
                            assertEquals(1,prepared.ready)
                            assertEquals(1,prepared.total)
                            activeClass = "other"
                            repeat(8) { preparation.request() }
                            assertEquals("other",preparation.awaitIdle().classId)
                            repo.saveCatalog(repo.loadCatalog().copy(maps=listOf(a)))
                            val updated = preparation.awaitIdle()
                            assertEquals("other",updated.classId)
                            assertEquals(0,updated.total)
                        } finally { repo.onCatalogChanged = null }
                    }
                    val single=run(listOf(a))
                    assertEquals(single.candidates.toString(),1,single.candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                    assertEquals("a",single.automaticallyConfirmedCandidate()?.map?.id)
                    assertEquals(SparseGateRecognizer.FORMAL_INPUT_POLICY,single.sparseGateDiagnostics!!.formalInputPolicy)
                    val actualFormal=single.sparseGateDiagnostics!!.floorEvidence.single().formalAttempts.last()
                    assertTrue(actualFormal.accepted)
                    assertTrue(actualFormal.registrationBest!!.occupancyCoverage >= .42)
                    assertEquals(com.idvb.android.recognize.structure.QUERY_KNOWN_REFERENCE_COVERAGE_DOMAIN,
                        actualFormal.registrationBest!!.referenceCoverageDomain)
                    assertTrue(actualFormal.registrationBest!!.referenceKnownPixels > 0)
                    assertTrue(actualFormal.registrationBest!!.queryKnownPixels > 0)
                    assertNull(single.copy(sparseGateDiagnostics=single.sparseGateDiagnostics!!.copy(retrievalComplete=false))
                        .automaticallyConfirmedCandidate())
                    val warm=run(listOf(a))
                    // Timings are measured per execution; every decision field remains equal.
                    assertEquals(single.candidates.map { it.copy(structureElapsedMilliseconds=0.0) },
                        warm.candidates.map { it.copy(structureElapsedMilliseconds=0.0) })
                    val shiftedGate=gate.copy(screenBounds=gate.screenBounds.copy(x=gate.screenBounds.x+14))
                    val jittered=run(listOf(a),listOf(shiftedGate))
                    assertEquals(jittered.candidates.toString(),1,jittered.candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                    val multipleGates=run(listOf(a),listOf(gate.copy(screenBounds=ScreenRect(380.0,60.0,10.0,10.0)),gate))
                    assertEquals(1,multipleGates.candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                    assertEquals(1,multipleGates.candidates.first().associatedGateIndex)
                    val duplicate=run(listOf(a,b))
                    assertEquals(0,duplicate.candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                    assertEquals(2,duplicate.sparseGateDiagnostics!!.supportedIdentityCount)
                    assertNull(duplicate.automaticallyConfirmedCandidate())
                    val family = run(listOf(a,b),groups=listOf(MapVariantGroupRecord("ab","c",0,listOf("a","b"))))
                    assertEquals(2,family.sparseGateDiagnostics!!.supportedIdentityCount)
                    assertEquals("a",family.automaticallyConfirmedCandidate()?.map?.id)
                    val c=add("c")
                    val overlap=run(listOf(a,b,c),groups=listOf(
                        MapVariantGroupRecord("ab","c",0,listOf("a","b")),
                        MapVariantGroupRecord("ac","c",1,listOf("a","c"))))
                    assertNull(overlap.automaticallyConfirmedCandidate())
                    val missing=b.copy(floors=b.floors.map { it.copy(key="2f") })
                    val incomplete=run(listOf(a,missing))
                    assertEquals(0,incomplete.candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                    assertFalse(incomplete.sparseGateDiagnostics!!.retrievalComplete)
                    // A missing full reference must not be treated as an excluded competitor.
                    File(repo.mapsRoot,"b/floor.png").delete()
                    assertEquals(0,run(listOf(a,b)).candidates.count { it.disposition==CandidateDisposition.RELIABLE })
                } finally { lines.release(); reference.release() }
            }
        } finally { SparseGateSearch.clear(); color.release(); frame.recycle(); isolated.deleteRecursively() }
    }

    @Test fun denseReferenceAliasCannotPassBidirectionalVisibleEvidence() {
        assertTrue(OpenCvRuntime.initialize())
        val observed=Mat.zeros(160,200,CvType.CV_8UC1)
        val reference=Mat.zeros(160,200,CvType.CV_8UC1)
        val domain=Mat.ones(160,200,CvType.CV_8UC1)
        domain.setTo(Scalar.all(255.0))
        try {
            Imgproc.line(observed,Point(30.0,5.0),Point(30.0,150.0),Scalar.all(255.0),1)
            observed.copyTo(reference)
            for (x in 50..190 step 20) Imgproc.line(reference,Point(x.toDouble(),5.0),Point(x.toDouble(),150.0),Scalar.all(255.0),1)
            val points=SparseGateSearch.pixels(observed)
            val contours=listOf(listOf(points.first(),points.last()))
            val pose=SparseGateSearch.Pose(1.0,0.0,0.0)
            val index=SparseGateSearch.build(reference)
            assertTrue("The old one-way alias has perfect observed support",
                SparseGateSearch.verify(index,pose,points,contours,200,160).supported)
            VpsgLiveExtractor.Observation(observed.clone(),domain.clone(),observed.clone(),domain.clone()).use { live ->
                val evidence=SparseGateSearch.verify(index,pose,points,contours,200,160,
                    reverse=SparseGateSearch.ReverseObservation(live))
                assertEquals(1.0,evidence.support,0.0)
                assertFalse(evidence.supported)
                assertEquals("visible-reference-conflict",evidence.rejectionCode)
                assertTrue(evidence.reverseSupport<.65)
            }
            // The same extra walls in unexplored pixels are unknown, not contradictions.
            domain.setTo(Scalar.all(0.0))
            Imgproc.rectangle(domain,Rect(24,0,13,160),Scalar.all(255.0),-1)
            VpsgLiveExtractor.Observation(observed.clone(),domain.clone(),observed.clone(),domain.clone()).use { live ->
                val evidence=SparseGateSearch.verify(index,pose,points,contours,200,160,
                    reverse=SparseGateSearch.ReverseObservation(live))
                assertTrue(evidence.toString(),evidence.supported)
                assertTrue(evidence.reversePoints>=80)
                assertEquals(1.0,evidence.reverseSupport,0.0)
            }
        } finally { observed.release(); reference.release(); domain.release() }
    }

    @Test fun distanceQuantizationAndDenseConflictsRetainDesktopBoundaries() {
        assertTrue(OpenCvRuntime.initialize())
        val line=Mat.zeros(160,200,CvType.CV_8UC1)
        try {
            Imgproc.line(line,Point(30.0,0.0),Point(30.0,159.0),Scalar.all(255.0),1)
            val index=SparseGateSearch.build(line)
            assertEquals(2.0,index.distance(32.0,50.0,1.0),.07)
            assertEquals(5.5,index.distance(32.75,50.0,2.0),.13)
            assertEquals(20.0,index.distance(50.0,50.0,1.0),.3)
            assertEquals(50.0,index.distance(-1.0,50.0,1.0),0.0)
            val points=(5..150).map { SparseGateSearch.Pixel(30,it) }
            for (x in -12..12) {
                val exact = index.score(points,1.0,x.toDouble(),0.0)
                for (cutoff in listOf(0.0,.4,.7,.95)) {
                    val bounded = index.score(points,1.0,x.toDouble(),0.0,cutoff)
                    if (exact >= cutoff) assertEquals(exact,bounded,0.0)
                    else assertTrue(bounded == exact || bounded == Double.NEGATIVE_INFINITY)
                }
            }
            val contour=listOf(points.first(),points.last())
            assertTrue(SparseGateSearch.verify(index,SparseGateSearch.Pose(1.0,0.0,0.0),points,listOf(contour),200,160).supported)
            assertFalse(SparseGateSearch.verify(index,SparseGateSearch.Pose(-1.0,0.0,0.0),points,listOf(contour),200,160).supported)
            val displaced=points.map { it.copy(x=45) }
            val conflict=SparseGateSearch.verify(index,SparseGateSearch.Pose(1.0,0.0,0.0),displaced,
                listOf(listOf(displaced.first(),displaced.last())),200,160)
            assertFalse(conflict.supported)
            assertTrue(conflict.longest>=30)
            assertTrue(conflict.spatialConflict)
            // A colored annotation may hide a reference wall. Ignore only that span,
            // retaining enough visible structure and rejecting genuinely displaced walls.
            val partial = line.clone()
            val excluded = Mat.zeros(160,200,CvType.CV_8UC1)
            try {
                Imgproc.rectangle(partial,Point(25.0,50.0),Point(35.0,95.0),Scalar.all(0.0),-1)
                Imgproc.rectangle(excluded,Point(25.0,48.0),Point(35.0,97.0),Scalar.all(255.0),-1)
                val occluded = SparseGateSearch.build(partial,excluded)
                assertFalse(SparseGateSearch.verify(SparseGateSearch.build(partial),SparseGateSearch.Pose(1.0,0.0,0.0),
                    points,listOf(contour),200,160).supported)
                assertTrue(SparseGateSearch.verify(occluded,SparseGateSearch.Pose(1.0,0.0,0.0),
                    points,listOf(contour),200,160).supported)
                assertFalse(SparseGateSearch.verify(occluded,SparseGateSearch.Pose(1.0,0.0,0.0),
                    displaced,listOf(listOf(displaced.first(),displaced.last())),200,160).supported)
                excluded.setTo(Scalar.all(255.0))
                assertFalse(SparseGateSearch.verify(SparseGateSearch.build(line,excluded),SparseGateSearch.Pose(1.0,0.0,0.0),
                    points,listOf(contour),200,160).supported)
            } finally { partial.release(); excluded.release() }
            val sparse=points.take(30)
            assertFalse(SparseGateSearch.verify(index,SparseGateSearch.Pose(1.0,0.0,0.0),sparse,listOf(contour),200,160).supported)
        } finally { line.release() }
    }
}
