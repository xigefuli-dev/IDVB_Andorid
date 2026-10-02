package com.idvb.android.alignment

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.data.ResolvedPrebuiltStructureLine
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ReferenceHatchGeometryInstrumentedTest {
    private fun source(frame: Boolean, stripes: Boolean, reverse: Boolean = false): Mat {
        OpenCvRuntime.requireAvailable()
        val source = Mat(180, 220, CvType.CV_8UC3, Scalar(60.0, 73.0, 89.0))
        val hatch = source.submat(Rect(75, 60, 42, 48))
        try {
            hatch.setTo(Scalar(46.0, 57.0, 69.0))
            if (stripes) for (intercept in -45..90 step 6) {
                val a = if (reverse) Point(-48.0, -48.0 + intercept) else Point(-48.0, 48.0 + intercept)
                val b = if (reverse) Point(90.0, 90.0 + intercept) else Point(90.0, -90.0 + intercept)
                Imgproc.line(hatch, a, b, Scalar(73.0, 89.0, 110.0), 1)
            }
            if (frame) Imgproc.rectangle(hatch, Point(0.0, 0.0), Point(41.0, 47.0), Scalar(73.0, 89.0, 110.0), 1)
        } finally { hatch.release() }
        return source
    }
    private fun extract(source: Mat): ReferenceHatchGeometry.Result {
        val gray = Mat()
        try { Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY); return ReferenceHatchGeometry.extract(source, gray) }
        finally { gray.release() }
    }

    @Test fun restoresOnlySourceFrameForEitherDiagonalOrientation() {
        for (reverse in listOf(false, true)) {
            val source = source(frame = true, stripes = true, reverse = reverse)
            try {
                val result = extract(source)
                assertEquals(result.rectangles.toString(), 4, result.rectangles.size)
                assertEquals(75.0, result.rectangles[0], 2.0)
                assertEquals(60.0, result.rectangles[1], 2.0)
                assertEquals(116.0, result.rectangles[2], 2.0)
                assertEquals(107.0, result.rectangles[3], 2.0)
                assertTrue(result.edges.any { it.toInt() and 255 > 128 })
            } finally { source.release() }
        }
    }
    @Test fun unframedTextureAndUntexturedRoomAreNotInventedWalls() {
        for ((frame, stripes) in listOf(false to true, true to false)) {
            val source = source(frame, stripes)
            try { assertTrue(extract(source).rectangles.isEmpty()) }
            finally { source.release() }
        }
    }
    @Test fun coloredRouteNumeralsAndWatermarksRemainExcluded() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(180, 220, CvType.CV_8UC3, Scalar(60.0, 73.0, 89.0))
        try {
            Imgproc.putText(source, "13", Point(20.0, 100.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(0.0, 255.0, 255.0), 3)
            Imgproc.line(source, Point(15.0, 120.0), Point(190.0, 120.0), Scalar(0.0, 255.0, 0.0), 6)
            Imgproc.line(source, Point(190.0, 120.0), Point(190.0, 30.0), Scalar(0.0, 0.0, 255.0), 6)
            Imgproc.putText(source, "WM", Point(70.0, 40.0), Imgproc.FONT_HERSHEY_SIMPLEX, .8, Scalar(20.0, 28.0, 31.0), 1)
            assertTrue(extract(source).rectangles.isEmpty())
        } finally { source.release() }
    }
    @Test fun lShapedHatchCannotInventAnInternalClosingBorder() {
        OpenCvRuntime.requireAvailable()
        val source = Mat(180, 220, CvType.CV_8UC3, Scalar(60.0, 73.0, 89.0))
        val mask = Mat.zeros(180, 220, CvType.CV_8UC1)
        val stripes = Mat.zeros(180, 220, CvType.CV_8UC3)
        val outline = MatOfPoint(Point(75.0, 60.0), Point(96.0, 60.0), Point(96.0, 91.0),
            Point(116.0, 91.0), Point(116.0, 107.0), Point(75.0, 107.0))
        try {
            Imgproc.fillPoly(mask, listOf(outline), Scalar.all(255.0))
            source.setTo(Scalar(46.0, 57.0, 69.0), mask)
            for (intercept in -200..400 step 6) {
                Imgproc.line(stripes, Point(0.0, intercept.toDouble()), Point(219.0, intercept - 219.0),
                    Scalar(73.0, 89.0, 110.0), 1)
            }
            // Preserve the darker gaps; copying black stripe background would erase the source.
            val stripeMask = Mat()
            try {
                Core.inRange(stripes, Scalar(1.0, 1.0, 1.0), Scalar.all(255.0), stripeMask)
                Core.bitwise_and(stripeMask, mask, stripeMask)
                stripes.copyTo(source, stripeMask)
            } finally { stripeMask.release() }
            Imgproc.polylines(source, listOf(outline), true, Scalar(73.0, 89.0, 110.0), 1)
            // A low-contrast stretch of the real lower edge is not evidence of an inner wall.
            Imgproc.line(source, Point(75.0, 107.0), Point(95.0, 107.0), Scalar(46.0, 57.0, 69.0), 1)
            val result = extract(source)
            assertTrue(result.rectangles.toString(), result.rectangles.isEmpty())
            assertFalse(result.edges.any { it.toInt() and 255 > 128 })
        } finally { outline.release(); stripes.release(); mask.release(); source.release() }
    }

    @Test fun portableFloorSourceUsesTheRepositoryResolver() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(target.cacheDir, "portable-reference-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(isolated, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(isolated, "cache").apply { mkdirs() }
        }
        val source = source(frame = true, stripes = true)
        val original = Mat.zeros(180, 220, CvType.CV_8UC1)
        try {
            val repository = MapRepository(context)
            val sourceFile = File(repository.mapsRoot, "local-map/maps/floor-001.png").apply { parentFile!!.mkdirs() }
            val prebuiltFile = File(repository.mapsRoot, "local-map/data/prebuilt.png").apply { parentFile!!.mkdirs() }
            assertTrue(Imgcodecs.imwrite(sourceFile.path, source))
            assertTrue(Imgcodecs.imwrite(prebuiltFile.path, original))
            val floor = FloorRecord("1f", "1F", 1, "maps/upstream-map/maps/floor-001.png", 220, 180)
            val map = MapRecord("local-map", "fixture", "fixture", "fixture", 1, listOf(floor))
            assertFalse(File(repository.mapsRoot, floor.imagePath).isFile)
            assertEquals(sourceFile.canonicalFile, repository.floorImageFile(map.id, floor.imagePath).canonicalFile)
            val trace = AlignmentTrace(captureArtifacts = true)
            val prepared = VpsgReferenceGeometry.prepare(repository, map, floor,
                ResolvedPrebuiltStructureLine(prebuiltFile, 220, 180, "fixture"), trace)
            val binary = CvImages.loadGray(prepared.file)
            try { assertTrue("Portable source must contribute its measured hatch frame", Core.countNonZero(binary) > 0) }
            finally { binary.release() }
            assertTrue(trace.snapshot().any { it.stage == "vpsg.reference.hatched-frames" &&
                it.series["acceptedLTRB"]?.isNotEmpty() == true })
            assertArrayEquals(sourceFile.readBytes(), trace.artifactSnapshot().getValue("reference-source.png"))
        } finally { original.release(); source.release(); isolated.deleteRecursively() }
    }

}
