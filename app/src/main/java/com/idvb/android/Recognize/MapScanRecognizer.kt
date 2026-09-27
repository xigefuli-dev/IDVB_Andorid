package com.idvb.android.recognize

import android.content.Context
import android.graphics.Bitmap
import com.idvb.android.data.MapRepository
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.gate.*
import com.idvb.android.recognize.vpsg.VpsgLineScanner
import com.idvb.android.recognize.side.SparseGateRecognizer
import com.idvb.android.recognize.side.SideEntranceScanProfiles
import kotlin.math.max

/** Probe the visible anchor first; run the second complete route only if identity remains unresolved. */
internal class MapScanRecognizer(private val context: Context, private val repository: MapRepository) {
    fun recognize(frame: Bitmap, viewport: ScreenRect, clientWidth: Int, clientHeight: Int,
        classId: String?, progress: ((Double, String) -> Unit)? = null): RecognitionResult {
        progress?.invoke(.02, "正在识别侧门…")
        val gray = CvImages.bitmapToGray(frame)
        val gates = try {
            GateTemplateDetector.fromAssets(context).use { detector -> detector.detect(
                liveMatchImage = gray, viewportBounds = viewport, clientWidth = clientWidth.toDouble(),
                scoreThreshold = GateTemplateRules.MATCH_THRESHOLD,
                searchContext = GateSearchContext(mode = GateSearchMode.FULL_SEARCH,
                    allowDualGateEarlyExit = false, allowSingleGateEarlyExit = false,
                    singleGateScoreThreshold = max(GateTemplateRules.MATCH_THRESHOLD, GateTemplateRules.EARLY_EXIT_SCORE_THRESHOLD))) }
        } finally { gray.release() }
        val sideRecognizer = SideEntranceRecognizer(context, repository, classId)
        fun side(start: Double, span: Double) = sideRecognizer.recognize(frame, viewport, clientWidth, clientHeight,
            progress = { progress?.invoke(start + span * it, "正在比较地图结构…") }, detectedGates = gates)
        val maps = repository.loadCatalog().maps.filter { classId == null || it.classId == classId }
        fun lines(start: Double, span: Double) = VpsgLineScanner(repository).recognize(frame, viewport, maps) {
            progress?.invoke(start + span * it, "正在比较地图线图…")
        }
        fun RecognitionResult.confirmed() = automaticallyConfirmedCandidate() != null
        if (gates.gates.isNotEmpty()) {
            val anchored = SparseGateRecognizer(repository).recognize(frame, viewport, maps, gates,
                SideEntranceScanProfiles.resolve(clientWidth, clientHeight)) {
                progress?.invoke(.10 + .50 * it, "正在比较地图结构…")
            } ?: side(.10, .50)
            if (anchored.confirmed()) { progress?.invoke(1.0, "正在确认结果…"); return anchored }
            val vpsg = lines(.60, .38) ?: return anchored
            progress?.invoke(1.0, "正在确认结果…")
            return if (vpsg.confirmed()) vpsg else merge(vpsg, anchored)
        }
        val vpsg = lines(.10, .85)
        // With no door the side route cannot add identity evidence; avoid preparing its templates.
        val result = vpsg ?: side(.95, .05)
        progress?.invoke(1.0, "正在确认结果…")
        return result
    }

    private fun merge(vpsg: RecognitionResult, side: RecognitionResult): RecognitionResult {
        val sideByKey = side.candidates.associateBy { it.map.id to it.floorKey }
        val merged = vpsg.candidates.map { line ->
            val candidate = sideByKey[line.map.id to line.floorKey]
            when {
                candidate == null -> line
                line.disposition == CandidateDisposition.CATALOG_ONLY && candidate.disposition != CandidateDisposition.CATALOG_ONLY -> candidate
                candidate.disposition == CandidateDisposition.RELIABLE && line.disposition != CandidateDisposition.RELIABLE -> candidate
                else -> line
            }
        }.sortedWith(compareByDescending<RecognitionCandidate> { it.disposition == CandidateDisposition.RELIABLE }
            .thenByDescending { it.disposition == CandidateDisposition.NEEDS_VERIFICATION }.thenByDescending { it.templateScore })
        return vpsg.copy(candidates = merged, diagnostics = side.diagnostics,
            sparseGateDiagnostics = side.sparseGateDiagnostics, route = "vpsg+side-entrance")
    }
}
