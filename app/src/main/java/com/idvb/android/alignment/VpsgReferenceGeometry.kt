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

/** Repair semantic details omitted by the walkable contour profile using the immutable
 * reference source and the original prebuilt domain, never live observations or residuals. */
internal object VpsgReferenceGeometry {
    const val ALGORITHM_ID = "prepared-geometry-v16"
    private data class Prepared(val line: ResolvedPrebuiltStructureLine, val color: ByteArray,
        val original: ByteArray, val holes: List<Double>, val sourceHash: String, val colorArtifact: String,
        val hatch: ReferenceHatchGeometry.Result, val partition: ReferencePartitionGeometry.Result,
        val striped: ReferenceStripedFrameGeometry.Result, val semantic: ReferenceSemanticGeometry.Result,
        val proposalLine: ResolvedPrebuiltStructureLine, val structuralLine: ResolvedPrebuiltStructureLine,
        val precisionLine: ResolvedPrebuiltStructureLine)
    private val cache = LinkedHashMap<String, Prepared>(8, .75f, true)
    val retainedEntries: Int get() = synchronized(this) { cache.size }
    @Synchronized fun clear() { cache.clear() }
    @Synchronized fun proposalLine(line: ResolvedPrebuiltStructureLine): ResolvedPrebuiltStructureLine =
        cache.values.firstOrNull { it.line.file == line.file }?.proposalLine ?: line
    @Synchronized fun structuralLine(line: ResolvedPrebuiltStructureLine): ResolvedPrebuiltStructureLine =
        cache.values.firstOrNull { it.line.file == line.file }?.structuralLine ?: line
    @Synchronized fun precisionLine(line: ResolvedPrebuiltStructureLine): ResolvedPrebuiltStructureLine =
        cache.values.firstOrNull { it.line.file == line.file }?.precisionLine ?: proposalLine(line)

    @Synchronized fun prepare(repository: MapRepository, map: MapRecord, floor: FloorRecord,
        line: ResolvedPrebuiltStructureLine, log: AlignmentLogSink): ResolvedPrebuiltStructureLine {
        val assets = repository.loadRecognitionAssets(map.id, floor)
        val source = assets.recognitionImageFile ?: repository.floorImageFile(map.id, floor.imagePath)
        if (!source.isFile) {
            log.emit(AlignmentLogEvent("vpsg.reference.voids", "color-input-unavailable; using-prebuilt-only"))
            return line
        }
        val region = if (assets.recognitionImageFile == null) assets.recognitionRegion else null
        val crop = if (assets.recognitionImageFile == null) repository.loadFreeCropPoints(map.id, floor) else emptyList()
        val key = "$ALGORITHM_ID|${map.id}|${floor.key}|${source.canonicalPath}|${source.length()}|${source.lastModified()}|$region|$crop|${line.file.canonicalPath}|${line.file.length()}|${line.file.lastModified()}"
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
            val originalMat = CvImages.loadGray(line.file)
            val augmented = originalMat.clone()
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
                val hatch = log.measure("vpsg.reference.hatch-frames") { ReferenceHatchGeometry.extract(bgr, gray) }
                val hatchMat = Mat(line.height, line.width, CvType.CV_8UC1)
                try { hatchMat.put(0, 0, hatch.edges); Core.bitwise_or(augmented, hatchMat, augmented) }
                finally { hatchMat.release() }
                val partition = log.measure("vpsg.reference.partitions") { ReferencePartitionGeometry.extract(bgr, gray, originalMat) }
                val partitionMat = Mat(line.height, line.width, CvType.CV_8UC1)
                try { partitionMat.put(0, 0, partition.edges); Core.bitwise_or(augmented, partitionMat, augmented) }
                finally { partitionMat.release() }
                val fingerprint = AlignmentDiagnosticsStore.sha256(original + color +
                    "$ALGORITHM_ID|${ReferenceStripedFrameGeometry.thresholds}|${ReferenceSemanticGeometry.thresholds}|${map.id}|${floor.key}|$region|$crop|${line.width}x${line.height}".toByteArray())
                val proposalFile = File(repository.alignmentReferenceCacheRoot, "$fingerprint-$ALGORITHM_ID-proposal.png")
                if (!proposalFile.isFile) check(Imgcodecs.imwrite(proposalFile.path, augmented))
                val striped = log.measure("vpsg.reference.striped-frames") { ReferenceStripedFrameGeometry.extract(bgr, gray) }
                val stripedMat = Mat(line.height, line.width, CvType.CV_8UC1)
                try { stripedMat.put(0, 0, striped.edges); Core.bitwise_or(augmented, stripedMat, augmented) }
                finally { stripedMat.release() }
                val structuralFile = File(repository.alignmentReferenceCacheRoot, "$fingerprint-$ALGORITHM_ID-structural.png")
                if (!structuralFile.isFile) check(Imgcodecs.imwrite(structuralFile.path, augmented))
                val semantic = log.measure("vpsg.reference.semantic-boundaries") { ReferenceSemanticGeometry.extract(bgr, gray) }
                val semanticMat = Mat(line.height, line.width, CvType.CV_8UC1)
                val precisionFile = File(repository.alignmentReferenceCacheRoot, "$fingerprint-$ALGORITHM_ID-precision.png")
                try {
                    semanticMat.put(0, 0, semantic.photometricEdges)
                    if (!precisionFile.isFile) check(Imgcodecs.imwrite(precisionFile.path, semanticMat))
                } catch (error: Throwable) { semanticMat.release(); throw error }
                try { semanticMat.put(0, 0, semantic.edges); Core.bitwise_or(augmented, semanticMat, augmented) }
                finally { semanticMat.release() }
                val output = File(repository.alignmentReferenceCacheRoot, "$fingerprint-$ALGORITHM_ID.png")
                if (!output.isFile) check(Imgcodecs.imwrite(output.path, augmented))
                Prepared(ResolvedPrebuiltStructureLine(output, line.width, line.height, line.algorithmId + "+$ALGORITHM_ID"),
                    color, original, records, AlignmentDiagnosticsStore.sha256(color),
                    if (assets.recognitionImageFile == null) "reference-source.png" else "reference-color.png", hatch, partition, striped, semantic,
                    ResolvedPrebuiltStructureLine(proposalFile, line.width, line.height, line.algorithmId + "+$ALGORITHM_ID-proposal"),
                    ResolvedPrebuiltStructureLine(structuralFile, line.width, line.height, line.algorithmId + "+$ALGORITHM_ID-structural"),
                    ResolvedPrebuiltStructureLine(if (semantic.photometricEdges.count { it.toInt() and 255 > 128 } >= 300)
                        precisionFile else proposalFile, line.width, line.height, line.algorithmId + "+$ALGORITHM_ID-precision"))
            } finally { bgr.release(); gray.release(); filled.release(); hierarchy.release(); originalMat.release(); augmented.release(); contours.forEach(MatOfPoint::release) }
        }.also {
            if (cache.size >= 8) cache.remove(cache.keys.first())
            cache[key] = it
        }
        log.attach("reference-prebuilt.png") { prepared.original }
        log.attach("reference-proposal.png") { prepared.proposalLine.file.readBytes() }
        log.attach("reference-structural.png") { prepared.structuralLine.file.readBytes() }
        log.attach("reference-precision.png") { prepared.precisionLine.file.readBytes() }
        log.emit(AlignmentLogEvent("vpsg.reference.geometry-domains", "wall-proposals-and-full-feature-verification",
            labels = mapOf("proposalArtifact" to "reference-proposal.png", "verificationArtifact" to "reference.png",
                "structuralArtifact" to "reference-structural.png", "precisionArtifact" to "reference-precision.png",
                "policy" to "wall-void-hatch-partition-model-for-pose-search; measured-source-appearance-explains-forward-contours; mandatory-structural-lines-only-for-reverse-absence-evidence")))
        log.attach(prepared.colorArtifact) { prepared.color }
        log.emit(AlignmentLogEvent("vpsg.reference.voids", "enclosed-void-v1",
            thresholds = mapOf("darkThreshold" to 16.0, "minimumHoleArea" to 100.0, "minimumHoleWidth" to 8.0,
                "minimumHoleHeight" to 8.0, "lineThickness" to 2.0),
            series = mapOf("holeXYWHAreaParentIncluded" to prepared.holes),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "colorArtifact" to prepared.colorArtifact,
                "originalArtifact" to "reference-prebuilt.png", "policy" to "reference-only-closed-dark-holes; no-live-residual-input")))
        log.attach("reference-hatched-frames.gray8") { prepared.hatch.edges }
        log.emit(AlignmentLogEvent("vpsg.reference.hatched-frames", "reference-hatched-frame-v1",
            thresholds = ReferenceHatchGeometry.thresholds,
            series = ReferenceHatchGeometry.colorRanges + mapOf("candidateLTRBSupportsDensityBandsContinuousSidesAccepted" to prepared.hatch.candidates,
                "acceptedLTRB" to prepared.hatch.rectangles),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "artifact" to "reference-hatched-frames.gray8",
                "policy" to "source-only-parallel-diagonal-texture-with-continuous-measured-rectangular-frame; prebuilt-walls-preserved")))
        log.attach("reference-partitions.gray8") { prepared.partition.edges }
        log.attach("reference-striped-frames.gray8") { prepared.striped.edges }
        log.attach("reference-semantic-boundaries.gray8") { prepared.semantic.edges }
        log.attach("reference-photometric.gray8") { prepared.semantic.photometricEdges }
        log.emit(AlignmentLogEvent("vpsg.reference.precision-domain", "undilated-source-photometric-index",
            thresholds = mapOf("minimumSourceEdges" to 300.0),
            measurements = mapOf("sourceEdges" to prepared.semantic.photometricEdges.count { it.toInt() and 255 > 128 }.toDouble()),
            labels = mapOf("artifact" to "reference-precision.png", "sourceSha256" to prepared.sourceHash,
                "fallback" to (prepared.precisionLine.file == prepared.proposalLine.file).toString(),
                "policy" to "source-measured-photometric-distance-for-precision; binary-dilated-proposal-score-is-not-precision; mandatory-structural-final-gates-unchanged")))
        log.emit(AlignmentLogEvent("vpsg.reference.semantic-boundaries", "source-semantic-photometric-boundaries-v2",
            thresholds = ReferenceSemanticGeometry.thresholds,
            measurements = mapOf("candidatePixels" to prepared.semantic.candidatePixels.toDouble(),
                "supportedPixels" to prepared.semantic.supportedPixels.toDouble()),
            series = ReferenceSemanticGeometry.colorRanges + mapOf(
                "contourDomainIndexParentArcAreaAccepted" to prepared.semantic.contours,
                "polygonsDomainIndexVertexCountThenXY" to prepared.semantic.points),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "artifact" to "reference-semantic-boundaries.gray8",
                "policy" to "immutable-cropped-source-only; static-source-value-floor50-independent-of-live-brightness; concave-polygons-and-measured-interior-photometric-seams; source-photometric-support-required; explanatory-forward-only; no-live-residual-or-missing-border-extrapolation; mandatory-walls-and-final-gates-preserved")))
        log.emit(AlignmentLogEvent("vpsg.reference.striped-frames", "reference-room-framed-vertical-stripes-v6",
            thresholds = ReferenceStripedFrameGeometry.thresholds,
            series = ReferenceStripedFrameGeometry.colorRanges + mapOf(
                "candidateLTRBLeftRightTopBottomSupportBandsDensityAccepted" to prepared.striped.candidates,
                "componentXYWHAreaEligible" to prepared.striped.components,
                "horizontalRunPairsLeftRightYLeftRightYOverlapEndpointsAgreeProposed" to prepared.striped.horizontalRunPairs,
                "acceptedLTRB" to prepared.striped.rectangles),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "artifact" to "reference-striped-frames.gray8",
                "policy" to "immutable-source-only-room-grates; corridor-stair-decoration-excluded; dim-end-horizontal-runs-use-measured-union-only-with-75-percent-overlap; measured-four-full-span-borders-max-fixed-flank-or-directional-top-hat-continuity-and-four-stripe-bands; no-endpoint-extrapolation-or-live-residual; original-walls-preserved")))
        log.emit(AlignmentLogEvent("vpsg.reference.partitions", "reference-anchored-partition-v1",
            thresholds = ReferencePartitionGeometry.thresholds,
            series = ReferencePartitionGeometry.colorRanges + mapOf("candidateVerticalXYWHDomainFlankMissingAnchorIncluded" to prepared.partition.candidates,
                "acceptedBranchXYWHParentXYWHMissingAnchorFlankDomain" to prepared.partition.partitions),
            labels = mapOf("sourceSha256" to prepared.sourceHash, "artifact" to "reference-partitions.gray8",
                "policy" to "source-only-measured-thin-T-partition; original-prebuilt-domain-and-parent-anchor; no-endpoint-extrapolation")))
        return prepared.line
    }
}
