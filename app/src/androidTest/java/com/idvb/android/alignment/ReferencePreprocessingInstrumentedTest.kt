package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Compare borrowed preprocessing with independently recomputed detector inputs on private real sources.
 * This component test does not replace complete replay of source crop/region preparation.
 */
class ReferencePreprocessingInstrumentedTest {
    @Test fun sharedReadOnlyInputsPreserveEveryDetectorResultOnOriginalReferenceSources() {
        OpenCvRuntime.requireAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val relative = InstrumentationRegistry.getArguments().getString("referencePreprocessCorpus")
        assertFalse("Private original reference corpus is required", relative.isNullOrBlank())
        val root = File(context.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val files = root.listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }
        assertEquals(88, files.size)
        val rows = JSONArray()
        for (archive in files) ZipFile(archive).use { zip ->
            fun bytes(name: String) = zip.getInputStream(requireNotNull(zip.getEntry(name))).use { it.readBytes() }
            val source = if (zip.getEntry("reference-source.png") != null) "reference-source.png" else "reference-color.png"
            val prebuilt = if (zip.getEntry("reference-prebuilt.png") != null) "reference-prebuilt.png" else "reference.png"
            val encoded = MatOfByte(*bytes(prebuilt))
            val original = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE)
            val sourceBytes = bytes(source)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size))
            val sized = Bitmap.createScaledBitmap(bitmap, original.cols(), original.rows(), false)
            val bgr = try { CvImages.bitmapToBgr(sized) } finally {
                if (sized !== bitmap) sized.recycle()
                bitmap.recycle()
            }
            val gray = Mat(); val hsv = Mat(); val high = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            try {
                Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
                val oldStart = System.nanoTime()
                val oldHatch = ReferenceHatchGeometry.extract(bgr, gray)
                val oldPartition = ReferencePartitionGeometry.extract(bgr, gray, original)
                val oldStriped = ReferenceStripedFrameGeometry.extract(bgr, gray)
                val oldSemantic = ReferenceSemanticGeometry.extract(bgr, gray)
                val oldEnd = System.nanoTime()
                val sharedStart = System.nanoTime()
                Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
                Imgproc.morphologyEx(gray, high, Imgproc.MORPH_TOPHAT, kernel)
                fun pixels(mat: Mat) = ByteArray((mat.total() * mat.elemSize()).toInt()).also { mat.get(0, 0, it) }
                val hsvBefore = pixels(hsv); val highBefore = pixels(high)
                val hatch = ReferenceHatchGeometry.extract(bgr, gray, hsv, high)
                val partition = ReferencePartitionGeometry.extract(bgr, gray, original, hsv, high)
                val striped = ReferenceStripedFrameGeometry.extract(bgr, gray, hsv, preparedTopHat = high)
                val semantic = ReferenceSemanticGeometry.extract(bgr, gray, hsv)
                val sharedEnd = System.nanoTime()
                assertArrayEquals(oldHatch.edges, hatch.edges)
                assertEquals(oldHatch.candidates, hatch.candidates); assertEquals(oldHatch.rectangles, hatch.rectangles)
                assertArrayEquals(oldPartition.edges, partition.edges)
                assertEquals(oldPartition.candidates, partition.candidates); assertEquals(oldPartition.partitions, partition.partitions)
                assertArrayEquals(oldStriped.edges, striped.edges)
                assertEquals(oldStriped.candidates, striped.candidates); assertEquals(oldStriped.rectangles, striped.rectangles)
                assertEquals(oldStriped.components, striped.components); assertEquals(oldStriped.horizontalRunPairs, striped.horizontalRunPairs)
                assertArrayEquals(oldSemantic.edges, semantic.edges); assertArrayEquals(oldSemantic.photometricEdges, semantic.photometricEdges)
                assertEquals(oldSemantic.contours, semantic.contours); assertEquals(oldSemantic.points, semantic.points)
                assertEquals(oldSemantic.candidatePixels, semantic.candidatePixels); assertEquals(oldSemantic.supportedPixels, semantic.supportedPixels)
                assertArrayEquals(hsvBefore, pixels(hsv)); assertArrayEquals(highBefore, pixels(high))
                rows.put(JSONObject().put("input", archive.name).put("source", source).put("width", original.cols()).put("height", original.rows())
                    .put("independentDetectorMs", (oldEnd - oldStart) / 1e6).put("sharedDetectorMsIncludingInputCopies", (sharedEnd - sharedStart) / 1e6)
                    .put("allPixelsAndCandidateSeriesEqual", true).put("borrowedInputsUnmodified", true))
            } finally { encoded.release(); original.release(); bgr.release(); gray.release(); hsv.release(); high.release(); kernel.release() }
        }
        File(context.filesDir, "test-evidence/reference-preprocessing").apply { mkdirs() }
            .resolve("differential-${UUID.randomUUID()}.json").writeText(rows.toString())
    }
}
