package com.idvb.android.recognize

import android.graphics.Bitmap
import com.idvb.android.idvm.MapRecord

enum class CandidateDisposition { RELIABLE, NEEDS_VERIFICATION, CATALOG_ONLY }

data class RecognitionCandidate(
    val map: MapRecord,
    val floorKey: String,
    val disposition: CandidateDisposition,
    val templateScore: Double = 0.0,
    val templateMargin: Double = 0.0,
    val chamferPixels: Double = Double.POSITIVE_INFINITY,
    val edgeCoverage: Double = 0.0,
    val occupancyCoverage: Double = 0.0,
    val evidenceLabel: String,
)

data class RecognitionResult(
    val capturedRegion: Bitmap,
    val candidates: List<RecognitionCandidate>,
)

/** 后台扫描路线预留；当前只由前台触发实现调用。 */
interface RecognitionRoute {
    fun recognize(frame: Bitmap): RecognitionResult
}

interface BackgroundRecognitionRoute {
    fun prepare()
    fun submitFrame(frame: Bitmap, timestampMillis: Long)
    fun stop()
}
