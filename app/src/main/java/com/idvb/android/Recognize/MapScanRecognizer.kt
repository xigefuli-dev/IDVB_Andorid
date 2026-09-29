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
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/** Choose one complete route from the observation. Ambiguity is a retry outcome,
 * not a reason to repeat the whole catalog with another solver on the same frame. */
internal class MapScanRecognizer(private val context: Context, private val repository: MapRepository) {
    fun recognize(frame: Bitmap, viewport: ScreenRect, clientWidth: Int, clientHeight: Int,
        classId: String?, progress: ((Double, String) -> Unit)? = null): RecognitionResult {
        require(clientWidth > 0 && clientHeight > 0)
        // Wall stroke extraction and geometric tolerances operate in a stable
        // pixel domain. Merely widening scale bounds leaves morphology dependent
        // on device resolution (notably thick 4K walls and tiny 720p icons).
        // Very small captures cannot acquire more evidence through arbitrary
        // enlargement. Limit upsampling so line pitch/widths stay meaningful.
        val factor = min(2.0,1440.0 / min(clientWidth, clientHeight))
        if (kotlin.math.abs(factor - 1.0) < .001)
            return recognizePrepared(frame, viewport, clientWidth, clientHeight, classId, progress)
        val normalized = Bitmap.createScaledBitmap(frame, max(1,(frame.width*factor).roundToInt()),
            max(1,(frame.height*factor).roundToInt()),true)
        fun ScreenRect.scaled(s: Double) = ScreenRect(x*s,y*s,width*s,height*s)
        try {
            val result = recognizePrepared(normalized, viewport.scaled(factor),
                (clientWidth*factor).roundToInt(),(clientHeight*factor).roundToInt(),classId,progress)
            val inverse = 1.0/factor
            return result.copy(capturedRegion=frame,viewportBounds=viewport,processingScale=factor,
                candidates=result.candidates.map { c -> c.copy(
                    structureScale=c.structureScale*inverse,structureOffsetX=c.structureOffsetX*inverse,
                    structureOffsetY=c.structureOffsetY*inverse,matchScale=c.matchScale*inverse,
                    matchBounds=c.matchBounds?.scaled(inverse),gateSpatialResidualPixels=c.gateSpatialResidualPixels*inverse) },
                diagnostics=result.diagnostics?.let { d -> d.copy(gateDetection=d.gateDetection.copy(
                    gates=d.gateDetection.gates.map { g -> g.copy(scale=g.scale*inverse,screenBounds=g.screenBounds.scaled(inverse)) })) })
        } finally { if (normalized !== frame) normalized.recycle() }
    }

    private fun recognizePrepared(frame: Bitmap, viewport: ScreenRect, clientWidth: Int, clientHeight: Int,
        classId: String?, progress: ((Double, String) -> Unit)?): RecognitionResult {
        progress?.invoke(.02, "正在识别侧门…")
        val gray = CvImages.bitmapToGray(frame)
        // Fog often leaves most of the viewport exactly black. Template matching
        // need not slide over that empty margin. Keep 128px padding and preserve
        // absolute coordinates; no map identity or authored anchor enters this ROI.
        val occupied = Mat()
        val gateBounds = try {
            Imgproc.threshold(gray,occupied,0.0,255.0,Imgproc.THRESH_BINARY)
            val bounds = Imgproc.boundingRect(occupied)
            if (bounds.empty()) Rect(0,0,gray.cols(),gray.rows()) else {
                val x = max(0,bounds.x-128); val y = max(0,bounds.y-128)
                Rect(x,y,min(gray.cols(),bounds.x+bounds.width+128)-x,
                    min(gray.rows(),bounds.y+bounds.height+128)-y)
            }
        } finally { occupied.release() }
        val gateInput = gray.submat(gateBounds)
        val gates = try {
            GateTemplateDetector.fromAssets(context).use { detector -> detector.detect(
                liveMatchImage = gateInput, viewportBounds = ScreenRect(viewport.x+gateBounds.x,
                    viewport.y+gateBounds.y,gateBounds.width.toDouble(),gateBounds.height.toDouble()),
                clientWidth = min(clientWidth,clientHeight) * (2560.0/1440.0),
                scoreThreshold = GateTemplateRules.MATCH_THRESHOLD,
                searchContext = GateSearchContext(mode = GateSearchMode.FULL_SEARCH,
                    allowDualGateEarlyExit = false, allowSingleGateEarlyExit = false,
                    singleGateScoreThreshold = max(GateTemplateRules.MATCH_THRESHOLD, GateTemplateRules.EARLY_EXIT_SCORE_THRESHOLD))) }
        } finally { gateInput.release(); gray.release() }
        val sideRecognizer = SideEntranceRecognizer(context, repository, classId)
        fun side(start: Double, span: Double) = sideRecognizer.recognize(frame, viewport, clientWidth, clientHeight,
            progress = { progress?.invoke(start + span * it, "正在比较地图结构…") }, detectedGates = gates)
        val maps = repository.loadCatalog().maps.filter { classId == null || it.classId == classId }
        fun lines(start: Double, span: Double) = VpsgLineScanner(repository).recognize(frame, viewport, maps) {
            progress?.invoke(start + span * it, "正在比较地图线图…")
        }
        if (gates.gates.isNotEmpty()) {
            val anchored = SparseGateRecognizer(repository).recognize(frame, viewport, maps, gates,
                SideEntranceScanProfiles.resolve(clientWidth, clientHeight)) {
                progress?.invoke(.10 + .50 * it, "正在比较地图结构…")
            } ?: side(.10, .50)
            progress?.invoke(1.0, "正在确认结果…")
            return anchored
        }
        val vpsg = lines(.10, .85)
        // With no door the side route cannot add identity evidence; avoid preparing its templates.
        val result = vpsg ?: side(.95, .05)
        progress?.invoke(1.0, "正在确认结果…")
        return result
    }

}
