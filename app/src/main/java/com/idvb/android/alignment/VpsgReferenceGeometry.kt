package com.idvb.android.alignment

import android.graphics.Bitmap
import com.idvb.android.data.MapRepository
import com.idvb.android.data.ResolvedPrebuiltStructureLine
import com.idvb.android.graphics.decodeMapRegion
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.cv.CvImages
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Prebuilt contours omit small enclosed voids. Restore only fully enclosed dark regions
 * from the actual reference color image, never from the live observation or its residuals. */
internal object VpsgReferenceGeometry {
    private data class Prepared(val line: ResolvedPrebuiltStructureLine, val color: ByteArray,
        val original: ByteArray, val holes: List<Double>, val sourceHash: String, val colorArtifact: String)
    private val cache = LinkedHashMap<String, Prepared>(8, .75f, true)

    @Synchronized fun prepare(repository: MapRepository, map: MapRecord, floor: FloorRecord,
        line: ResolvedPrebuiltStructureLine, log: AlignmentLogSink): ResolvedPrebuiltStructureLine {
        val assets = repository.loadRecognitionAssets(map.id, floor)
        val source = assets.recognitionImageFile ?: File(repository.mapsRoot, floor.imagePath)
        if (!source.isFile) {
            log.emit(AlignmentLogEvent("vpsg.reference.voids", "color-input-unavailable; using-prebuilt-only"))
            return line
        }
        val region = if (assets.recognitionImageFile == null) assets.recognitionRegion else null
        val crop = if (assets.recognitionImageFile == null) repository.loadFreeCropPoints(map.id, floor) else emptyList()
        val key = "${source.canonicalPath}|${source.length()}|${source.lastModified()}|$region|$crop|${line.file.canonicalPath}|${line.file.length()}|${line.file.lastModified()}"
        val prepared = cache[key]?.takeIf { it.line.file.isFile } ?: log.measure("vpsg.reference.prepare") {
            val bitmap = requireNotNull(decodeMapRegion(source, region, maxOf(line.width, line.height),
                crop))
            val sized = Bitmap.createScaledBitmap(bitmap, line.width, line.height, false)
            val color = source.readBytes()
            val bgr = try { CvImages.bitmapToBgr(sized) } finally {
                if (sized !== bitmap) sized.recycle(); bitmap.recycle()
            }
            val gray = Mat(); val filled = Mat(); val hierarchy = Mat()
            val contours = ArrayList<MatOfPoint>()
            val original = line.file.readBytes()
            val augmented = CvImages.loadGray(line.file)
            val records = ArrayList<Double>()
            try {
                Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
                Imgproc.threshold(gray, filled, 16.0, 255.0, Imgproc.THRESH_BINARY)
                Imgproc.findContours(filled, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
                for ((i, contour) in contours.withIndex()) {
                    AlignmentCancellation.checkpoint("vpsg.reference.void-contour")
                    val parent = hierarchy.get(0, i)[3].toInt()
                    val box = Imgproc.boundingRect(contour)
                    val area = Imgproc.contourArea(contour)
                    val include = parent >= 0 && area >= 100 && box.width >= 8 && box.height >= 8
                    records.addAll(listOf(box.x.toDouble(), box.y.toDouble(), box.width.toDouble(), box.height.toDouble(), area, parent.toDouble(), if (include) 1.0 else 0.0))
                    if (include) Imgproc.drawContours(augmented, contours, i, Scalar.all(255.0), 2, Imgproc.LINE_8)
                }
                val fingerprint = AlignmentDiagnosticsStore.sha256(original + color + "$region|$crop|${line.width}x${line.height}".toByteArray())
                val output = File(repository.alignmentReferenceCacheRoot, "$fingerprint-void-v1.png")
                if (!output.isFile) check(Imgcodecs.imwrite(output.path, augmented))
                Prepared(ResolvedPrebuiltStructureLine(output, line.width, line.height, line.algorithmId + "+enclosed-void-v1"),
                    color, original, records, AlignmentDiagnosticsStore.sha256(color),
                    if (assets.recognitionImageFile == null) "reference-source.png" else "reference-color.png")
            } finally { bgr.release(); gray.release(); filled.release(); hierarchy.release(); augmented.release(); contours.forEach(MatOfPoint::release) }
        }.also {
            if (cache.size >= 8) cache.remove(cache.keys.first())
            cache[key] = it
        }
        log.attach("reference-prebuilt.png") { prepared.original }
        log.attach(prepared.colorArtifact) { prepared.color }
        log.emit(AlignmentLogEvent("vpsg.reference.voids", "enclosed-void-v1",
            thresholds = mapOf("darkThreshold" to 16.0, "minimumHoleArea" to 100.0, "minimumHoleWidth" to 8.0,
                "minimumHoleHeight" to 8.0, "lineThickness" to 2.0),
            series = mapOf("holeXYWHAreaParentIncluded" to prepared.holes),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "colorArtifact" to prepared.colorArtifact,
                "originalArtifact" to "reference-prebuilt.png", "policy" to "reference-only-closed-dark-holes; no-live-residual-input")))
        return prepared.line
    }
}
