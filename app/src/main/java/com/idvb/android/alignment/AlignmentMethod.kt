package com.idvb.android.alignment

import android.graphics.Bitmap
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.recognize.gate.ScreenRect

/** A single fresh capture, with screen coordinates and an explicitly selected floor. */
data class AlignmentRequest(
    val frame: Bitmap,
    val viewport: ScreenRect,
    val map: MapRecord,
    val floor: FloorRecord,
    val cancellation: AlignmentCancellation = AlignmentCancellation(),
)

/** Implementations borrow the frame and check request.cancellation in bounded loops and between
 * native calls. The caller owns the frame lifetime and commits accepted results. */
interface AlignmentMethod {
    val id: String
    fun align(request: AlignmentRequest, log: AlignmentLogSink = AlignmentLogSink.NONE): AlignmentResult
}

/** Common evidence plus named metrics for future algorithms; absent measurements stay absent. */
data class AlignmentEvidence(
    val visibleSupport: Double? = null,
    val referenceSupport: Double? = null,
    val meanResidualPixels: Double? = null,
    val measurements: Map<String, Double> = emptyMap(),
)

sealed interface AlignmentResult {
    data class Aligned(val transform: AlignmentTransform,
        val evidence: AlignmentEvidence = AlignmentEvidence()) : AlignmentResult
    data class Unavailable(val reason: String, val code: String = "unavailable") : AlignmentResult
    data class Rejected(val reason: String, val code: String = "structure-unresolved") : AlignmentResult
}

enum class AlignmentOutcome { ALIGNED, UNAVAILABLE, REJECTED }

val AlignmentResult.outcome: AlignmentOutcome get() = when (this) {
    is AlignmentResult.Aligned -> AlignmentOutcome.ALIGNED
    is AlignmentResult.Unavailable -> AlignmentOutcome.UNAVAILABLE
    is AlignmentResult.Rejected -> AlignmentOutcome.REJECTED
}

/** Desktop canonical coordinates: screen = reference * scale + offset. */
data class AlignmentTransform(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val referenceWidth: Int,
    val referenceHeight: Int,
) {
    init {
        require(scale.isFinite() && scale > 0 && offsetX.isFinite() && offsetY.isFinite())
        require(referenceWidth > 0 && referenceHeight > 0)
        require((offsetX + referenceWidth * scale).isFinite() &&
            (offsetY + referenceHeight * scale).isFinite())
    }

    val bounds: ScreenRect get() = ScreenRect(offsetX, offsetY,
        referenceWidth * scale, referenceHeight * scale)
}
