package com.idvb.android.recognize.vpsg

import androidx.annotation.Keep
import com.idvb.android.alignment.AlignmentCancellation

/** Same integer votes, rounding, probe order and incumbent bounds as the managed solver.
 * The managed implementation remains the independent differential-test oracle. */
@Keep
internal object VpsgNativeKernel {
    val available: Boolean by lazy { runCatching { System.loadLibrary("idvb_vpsg") }.isSuccess }
    val backend: String get() = if (available) "native-exact-v2" else "managed-exact-v1"
    fun direct(values: LongArray) = java.nio.ByteBuffer.allocateDirect(values.size * 8)
        .order(java.nio.ByteOrder.nativeOrder()).also { it.asLongBuffer().put(values) }
    fun direct(values: IntArray) = java.nio.ByteBuffer.allocateDirect(values.size * 4)
        .order(java.nio.ByteOrder.nativeOrder()).also { it.asIntBuffer().put(values) }
    fun direct(values: FloatArray) = java.nio.ByteBuffer.allocateDirect(values.size * 4)
        .order(java.nio.ByteOrder.nativeOrder()).also { it.asFloatBuffer().put(values) }
    fun direct(values: ByteArray) = java.nio.ByteBuffer.allocateDirect(values.size).also { it.put(values); it.rewind() }
    /** Borrowed only while the caller's immutable continuous Mat remains alive; never retain in diagnostics. */
    fun borrowBuffer(address: Long, bytes: Long) = requireNotNull(borrowBufferNative(address, bytes))
        .order(java.nio.ByteOrder.nativeOrder())
    fun initializeStopFlag(buffer: java.nio.ByteBuffer, cancelled: Boolean): Long =
        initializeStopFlagNative(buffer, cancelled).also { check(it != 0L) }
    fun signalStopFlag(address: Long) = signalStopFlagNative(address)
    fun hsvDomains(source: org.opencv.core.Mat): IntArray {
        require(source.type() == org.opencv.core.CvType.CV_8UC3)
        val domains = hsvDomainsNative(source.dataAddr(), source.step1(), source.cols(), source.rows(), AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.extract.hsv.domains-native-exit")
        return requireNotNull(domains)
    }
    fun hsvConsumers(source: org.opencv.core.Mat, output: org.opencv.core.Mat): IntArray {
        require(source.type() == org.opencv.core.CvType.CV_8UC3)
        output.create(source.rows(), source.cols(), source.type())
        val result = hsvConsumersNative(source.dataAddr(), source.step1(), output.dataAddr(), output.step1(),
            source.cols(), source.rows(), AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.extract.hsv.consumer-native-exit")
        return requireNotNull(result)
    }
    fun semanticMasks(hsv: org.opencv.core.Mat, exclusion: org.opencv.core.Mat,
        room: org.opencv.core.Mat, corridor: org.opencv.core.Mat,
        red: org.opencv.core.Mat? = null, yellow: org.opencv.core.Mat? = null) {
        require(hsv.type() == org.opencv.core.CvType.CV_8UC3 && exclusion.type() == org.opencv.core.CvType.CV_8UC1)
        require(hsv.size() == exclusion.size())
        room.create(hsv.rows(), hsv.cols(), org.opencv.core.CvType.CV_8UC1)
        corridor.create(hsv.rows(), hsv.cols(), org.opencv.core.CvType.CV_8UC1)
        red?.create(hsv.rows(), hsv.cols(), org.opencv.core.CvType.CV_8UC1)
        yellow?.create(hsv.rows(), hsv.cols(), org.opencv.core.CvType.CV_8UC1)
        val completed = semanticMasksNative(hsv.dataAddr(), hsv.step1(), exclusion.dataAddr(), exclusion.step1(),
            room.dataAddr(), room.step1(), corridor.dataAddr(), corridor.step1(),
            red?.dataAddr() ?: 0L, red?.step1() ?: 0L, yellow?.dataAddr() ?: 0L, yellow?.step1() ?: 0L,
            hsv.cols(), hsv.rows(), AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.extract.semantic-masks-native-exit")
        check(completed)
    }
    fun scoreGrid(points: List<VpsgFastSolver.Point>, index: VpsgFastSolver.Index,
        minX: Int, maxX: Int, minY: Int, maxY: Int, stride: Int): IntArray {
        val coordinates = IntArray(points.size * 2) { if (it and 1 == 0) points[it / 2].x else points[it / 2].y }
        val result = scoreGridNative(coordinates, if (stride == 4) index.nativeStride4 else index.nativeK3, index.width, index.height,
            minX, maxX, minY, maxY, stride, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.translation.score-grid.native-exit")
        return requireNotNull(result) { "Native score grid failed" }
    }

    fun prepareStride4(words: LongArray, width: Int, height: Int): LongArray =
        requireNotNull(prepareStride4Native(words, width, height))

    fun translationCandidates(points: List<VpsgFastSolver.Point>, index: VpsgFastSolver.Index, scores: IntArray,
        minX: Int, maxX: Int, minY: Int, maxY: Int, scale: Double, minimumRivalDistance: Double = 10.0): DoubleArray {
        val xy = IntArray(points.size * 2) { if (it and 1 == 0) points[it / 2].x else points[it / 2].y }
        val result = translationCandidatesNative(xy, index.nativeK3, index.width, index.height, scores,
            minX, maxX, minY, maxY, scale, minimumRivalDistance, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.translation.candidates.native-exit")
        return requireNotNull(result)
    }

    fun precisionLosses(points: IntArray, poses: DoubleArray, distance: java.nio.ByteBuffer,
        width: Int, height: Int, cx: Double, cy: Double, rcx: Double, rcy: Double): DoubleArray {
        val result = precisionLossesNative(points, poses, distance, width, height, cx, cy, rcx, rcy, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.refine.precision.native-exit")
        return requireNotNull(result)
    }
    fun verifyForward(points: IntArray, segments: IntArray, distance: java.nio.ByteBuffer,
        referenceWidth: Int, referenceHeight: Int, width: Int, height: Int, scale: Double, x: Double, y: Double,
        capture: Boolean): DoubleArray {
        val tuning = VpsgAlignmentTuning
        val result = verifyForwardNative(points, segments, distance, referenceWidth, referenceHeight, width, height,
            scale, x, y, doubleArrayOf(tuning.DISTANCE_TOLERANCE, tuning.MIN_SUPPORT, tuning.MIN_CELL_POINTS.toDouble(), tuning.MIN_CELL_SUPPORT),
            capture, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.verify.forward.native-exit")
        return requireNotNull(result)
    }

    fun reverseCoverage(positions: IntArray, referenceWidth: Int, domain: ByteArray, width: Int, height: Int,
        distance: java.nio.ByteBuffer, scale: Double, x: Double, y: Double, legacy: Boolean, capture: Boolean): DoubleArray {
        return reverseCoverage(direct(positions), positions.size, referenceWidth, direct(domain), width, height,
            distance, scale, x, y, legacy, capture)
    }
    fun reverseCoverage(positions: java.nio.ByteBuffer, count: Int, referenceWidth: Int, domain: java.nio.ByteBuffer,
        width: Int, height: Int, distance: java.nio.ByteBuffer, scale: Double, x: Double, y: Double, legacy: Boolean, capture: Boolean,
        domainX: Int = 0, domainY: Int = 0, domainWidth: Int = width, domainHeight: Int = height): DoubleArray {
        val result = reverseCoverageNative(positions, count, referenceWidth, domain, width, height, distance,
            scale, x, y, legacy, capture, domainX, domainY, domainWidth, domainHeight, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.verify.reverse-native-exit")
        return requireNotNull(result) { "Native reverse verification failed" }
    }

    fun refine(points: List<VpsgFastSolver.Point>, index: VpsgFastSolver.Index,
        seed: VpsgFastSolver.Pose, centerX: Double, centerY: Double, maximumScale: Double,
        captureProbes: Boolean): DoubleArray {
        val coordinates = IntArray(points.size * 2) { if (it and 1 == 0) points[it / 2].x else points[it / 2].y }
        val result = refineNative(coordinates, index.nativeK3, index.nativeK5, index.width, index.height,
            seed.scale, seed.x, seed.y, centerX, centerY, maximumScale, captureProbes, AlignmentCancellation.current())
        AlignmentCancellation.checkpoint("vpsg.refine.discrete.native-exit")
        return requireNotNull(result) { "Native refinement failed" }
    }

    private external fun scoreGridNative(points: IntArray, k3: java.nio.ByteBuffer, width: Int, height: Int,
        minX: Int, maxX: Int, minY: Int, maxY: Int, stride: Int, cancellation: AlignmentCancellation?): IntArray?
    private external fun prepareStride4Native(k3: LongArray, width: Int, height: Int): LongArray?
    private external fun borrowBufferNative(address: Long, bytes: Long): java.nio.ByteBuffer?
    private external fun initializeStopFlagNative(buffer: java.nio.ByteBuffer, cancelled: Boolean): Long
    private external fun signalStopFlagNative(address: Long)
    private external fun hsvDomainsNative(source: Long, sourceStep: Long,
        width: Int, height: Int, cancellation: AlignmentCancellation?): IntArray?
    private external fun hsvConsumersNative(source: Long, sourceStep: Long, output: Long, outputStep: Long,
        width: Int, height: Int, cancellation: AlignmentCancellation?): IntArray?
    private external fun semanticMasksNative(hsv: Long, hsvStep: Long, exclusion: Long, exclusionStep: Long,
        room: Long, roomStep: Long, corridor: Long, corridorStep: Long,
        red: Long, redStep: Long, yellow: Long, yellowStep: Long,
        width: Int, height: Int, cancellation: AlignmentCancellation?): Boolean
    private external fun translationCandidatesNative(points: IntArray, k3: java.nio.ByteBuffer, width: Int,
        height: Int, scores: IntArray, minX: Int, maxX: Int, minY: Int, maxY: Int, scale: Double, minimumRivalDistance: Double,
        cancellation: AlignmentCancellation?): DoubleArray?
    private external fun precisionLossesNative(points: IntArray, poses: DoubleArray, distance: java.nio.ByteBuffer,
        width: Int, height: Int, cx: Double, cy: Double, rcx: Double, rcy: Double,
        cancellation: AlignmentCancellation?): DoubleArray?
    private external fun verifyForwardNative(points: IntArray, segments: IntArray, distance: java.nio.ByteBuffer,
        referenceWidth: Int, referenceHeight: Int, width: Int, height: Int, scale: Double, x: Double, y: Double,
        thresholds: DoubleArray, capture: Boolean, cancellation: AlignmentCancellation?): DoubleArray?
    private external fun reverseCoverageNative(positions: java.nio.ByteBuffer, count: Int, referenceWidth: Int, domain: java.nio.ByteBuffer,
        width: Int, height: Int, distance: java.nio.ByteBuffer, scale: Double, x: Double, y: Double,
        legacy: Boolean, capture: Boolean, domainX: Int, domainY: Int, domainWidth: Int, domainHeight: Int,
        cancellation: AlignmentCancellation?): DoubleArray?
    private external fun refineNative(points: IntArray, k3: java.nio.ByteBuffer, k5: java.nio.ByteBuffer, width: Int, height: Int,
        scale: Double, x: Double, y: Double, centerX: Double, centerY: Double, maximumScale: Double,
        captureProbes: Boolean, cancellation: AlignmentCancellation?): DoubleArray?
}
