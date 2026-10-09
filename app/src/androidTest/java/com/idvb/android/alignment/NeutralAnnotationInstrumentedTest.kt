package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class NeutralAnnotationInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private fun mask(trace: AlignmentTrace, width: Int, height: Int): ByteArray {
        val bytes = trace.artifactSnapshot().getValue("occlusion-mask.gray8")
        assertEquals(width * height, bytes.size)
        return bytes
    }

    @Test fun feedbackChestAboveOldAreaLimitIsOccludedInBothDirections() {
        OpenCvRuntime.requireAvailable()
        val relative=requireNotNull(InstrumentationRegistry.getArguments().getString("recognitionCorpus"))
        val root=File(target.cacheDir,relative).canonicalFile
        require(root.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
        ZipFile(File(root,"recognition/recognition-1791429840230-e7f2a70c-f680-4984-b6d3-d1a111ec23c6.zip")).use { zip ->
            val encoded=MatOfByte(*zip.getInputStream(zip.getEntry("captured.png")).use { it.readBytes() })
            val original=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR)
            try {
                for ((dx,dy) in listOf(0 to 0,97 to 211)) {
                    val frame=Mat.zeros(original.rows()+dy,original.cols()+dx,CvType.CV_8UC3)
                    val roi=frame.submat(Rect(dx,dy,original.cols(),original.rows()))
                    try { original.copyTo(roi) } finally { roi.release() }
                    val trace=AlignmentTrace(captureArtifacts=true)
                    try {
                        VpsgLiveExtractor.extract(frame,trace,visibilityScopedReverse=true).use { live ->
                            val event=trace.snapshot().single { it.stage=="vpsg.extract.neutral-annotation-evidence" }
                            val chest=event.series.getValue("componentXYWHAreaDensityHolesExcluded").chunked(8)
                                .single { it[0]==327.0+dx && it[1]==121.0+dy }
                            assertEquals(1026.0,chest[4],0.0); assertEquals(2.0,chest[6],0.0); assertEquals(1.0,chest[7],0.0)
                            for (y in 118+dy..151+dy) for (x in 324+dx..381+dx) {
                                assertEquals(0.0,live.edges.get(y,x)[0],0.0)
                                assertEquals(0.0,live.revealed!!.get(y,x)[0],0.0)
                            }
                        }
                    } finally { trace.releaseWrittenArtifacts(); frame.release() }
                }
            } finally { encoded.release(); original.release() }
        }
    }

    @Test fun confirmedRealIconsAreExcludedBeforeVerificationAfterPaddingAndTranslation() {
        OpenCvRuntime.requireAvailable()
        val relative = InstrumentationRegistry.getArguments().getString("neutralIconCorpus")
        assertFalse("Original confirmed-icon corpus is required", relative.isNullOrBlank())
        val root = File(target.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(target.cacheDir.canonicalFile.toPath()))
        val reports = JSONArray()
        val files = root.listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }
        assertEquals(2, files.size)
        for (archive in files) ZipFile(archive).use { zip ->
            val d = JSONObject(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().use { it.readText() })
            val events = d.getJSONArray("events"); val pose = d.getJSONObject("result").getJSONObject("transform")
            val list = (0 until events.length()).map { events.getJSONObject(it) }
            val sampled = list.first { it.getString("stage") == "observation" }.getJSONObject("series").getJSONArray("sampledEdgesXY")
            val residual = list.last { e -> e.getString("stage") == "vpsg.verify.residual-data" &&
                listOf("scale", "offsetX", "offsetY").all { kotlin.math.abs(e.getJSONObject("measurements").getDouble(it) - pose.getDouble(it)) < 1e-9 }
            }.getJSONObject("series").getJSONArray("sampledEdgesDistances")
            val bad = (0 until residual.length()).filter { residual.getDouble(it) > 5.5 }.map { sampled.getInt(it * 2) to sampled.getInt(it * 2 + 1) }
            assertTrue(bad.size == 43 || bad.size == 52)
            val encoded = MatOfByte(*zip.getInputStream(zip.getEntry("captured.png")).use { it.readBytes() })
            val original = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
            try {
                for ((dx, dy) in listOf(0 to 0, 0 to 186, 97 to 211)) {
                    val frame = Mat.zeros(original.rows() + dy, original.cols() + dx, CvType.CV_8UC3)
                    val destination = frame.submat(Rect(dx, dy, original.cols(), original.rows()))
                    try { original.copyTo(destination) } finally { destination.release() }
                    val trace = AlignmentTrace(captureArtifacts = true)
                    try {
                        VpsgLiveExtractor.extract(frame, trace, visibilityScopedReverse = true).use { live ->
                            val occlusion = mask(trace, frame.cols(), frame.rows())
                            val excluded = bad.count { (x, y) -> occlusion[(y + dy) * frame.cols() + x + dx].toInt() and 255 != 0 }
                            val retained = bad.count { (x, y) -> live.edges.get(y + dy, x + dx)[0] != 0.0 }
                            val revealed = bad.count { (x, y) -> live.revealed!!.get(y + dy, x + dx)[0] != 0.0 }
                            assertEquals("All confirmed-icon points must be occluded: $archive dx=$dx dy=$dy", bad.size, excluded)
                            assertEquals(0, retained); assertEquals(0, revealed)
                            reports.put(JSONObject().put("input", archive.name).put("dx", dx).put("dy", dy)
                                .put("originalBadPoints", bad.size).put("occluded", excluded).put("retained", retained).put("revealed", revealed))
                        }
                    } finally { trace.releaseWrittenArtifacts(); frame.release() }
                }
            } finally { encoded.release(); original.release() }
        }
        File(target.filesDir, "test-evidence/neutral-icons").apply { mkdirs() }
            .resolve("real-icon-mask-${UUID.randomUUID()}.json").writeText(reports.toString(2))
    }

    @Test fun straightShortWallsSingleRoomsAndConnectedTwoRoomWallsRemainUnoccluded() {
        OpenCvRuntime.requireAvailable()
        val frame = Mat.zeros(280, 360, CvType.CV_8UC3)
        try {
            val white = Scalar.all(180.0)
            // Stay outside the preexisting top HUD detector: this test isolates the
            // new glyph policy rather than triggering the independent white HUD mask.
            Imgproc.rectangle(frame, Point(30.0, 130.0), Point(35.0, 147.0), white, -1)
            Imgproc.rectangle(frame, Point(80.0, 130.0), Point(112.0, 152.0), white, 2)
            Imgproc.rectangle(frame, Point(150.0, 130.0), Point(180.0, 150.0), white, 2)
            Imgproc.rectangle(frame, Point(180.0, 130.0), Point(210.0, 150.0), white, 2)
            val trace = AlignmentTrace(captureArtifacts = true)
            try {
                VpsgLiveExtractor.extract(frame, trace, visibilityScopedReverse = true).close()
                val bytes = mask(trace, frame.cols(), frame.rows())
                for (y in 125..157) for (x in 25..215) assertEquals("Real wall must not become an annotation at $x,$y", 0, bytes[y * frame.cols() + x].toInt() and 255)
            } finally { trace.releaseWrittenArtifacts() }
        } finally { frame.release() }
    }

    @Test fun productionNativeVerifierStillRejectsTheGenuineFourteenPixelShortWallMismatch() {
        OpenCvRuntime.requireAvailable()
        val reference = Mat.zeros(1001, 1001, CvType.CV_8UC1)
        val inverse = Mat(); val distance = Mat()
        try {
            val points = ArrayList<Int>()
            for (y in 130..147) for (x in 110..115) { points += x; points += y }
            Imgproc.rectangle(reference, Point(124.0, 130.0), Point(129.0, 147.0), Scalar.all(255.0), -1)
            Imgproc.rectangle(reference, Point(310.0, 130.0), Point(315.0, 320.0), Scalar.all(255.0), -1)
            for (y in 130..320) for (x in 310..315) { points += x; points += y }
            for (y in listOf(700, 760, 820, 880, 940)) for (x in 700..997 step 3) {
                reference.put(y, x, byteArrayOf(255.toByte())); points += x; points += y
            }
            Core.bitwise_not(reference, inverse)
            Imgproc.distanceTransform(inverse, distance, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
            val buffer = com.idvb.android.recognize.vpsg.VpsgNativeKernel.direct(FloatArray(1001 * 1001).also { distance.get(0, 0, it) })
            for ((dx, dy) in listOf(0 to 0, 0 to 186, 97 to 211)) {
                val shifted = IntArray(points.size) { points[it] + if (it % 2 == 0) dx else dy }
                val result = com.idvb.android.recognize.vpsg.VpsgNativeKernel.verifyForward(shifted, intArrayOf(), buffer,
                    1001, 1001, 1001 + dx, 1001 + dy, 1.0, dx.toDouble(), dy.toDouble(), true)
                assertEquals(1646.0, result[0], 0.0)
                assertTrue("Known wrong wall stays a spatial conflict after padding/translation", (0..15).any {
                    result[6 + it] >= 30 && result[22 + it] < result[6 + it] * .70 })
            }
        } finally { reference.release(); inverse.release(); distance.release() }
    }
}
