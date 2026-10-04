package com.idvb.android.recognize.vpsg

import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.measure
import com.idvb.android.recognize.cv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Initialize native workers and observation classes before capture, once per process.
 * This synthetic input is initialization only and never supplies a pose or acceptance evidence. */
internal object VpsgRuntimePreparation {
    private var ready = false
    @Synchronized fun prepare(repository: com.idvb.android.data.MapRepository, log: AlignmentLogSink) {
        if (ready) return
        log.measure("vpsg.runtime.prepare") {
            OpenCvRuntime.requireAvailable()
            val width = 1000; val height = 800
            val bgr = Mat.zeros(height, width, CvType.CV_8UC3)
            try {
                for (y in listOf(110, 460)) for (x in listOf(130, 600))
                    Imgproc.rectangle(bgr, Point(x.toDouble(), y.toDouble()), Point((x + 220).toDouble(), (y + 220).toDouble()), Scalar(95.0, 110.0, 150.0), -1)
                val reference = java.io.File(repository.alignmentReferenceCacheRoot, "runtime-initialization-v2.png")
                VpsgLiveExtractor.extract(bgr, visibilityScopedReverse = true).use { live ->
                    check(org.opencv.imgcodecs.Imgcodecs.imwrite(reference.absolutePath, live.edges))
                }
                val floor = com.idvb.android.idvm.FloorRecord("runtime-init", "runtime-init", 0, "", width, height)
                val map = com.idvb.android.idvm.MapRecord("runtime-init", "", "runtime-init", "", 1, listOf(floor))
                val lines = com.idvb.android.data.ResolvedPrebuiltStructureLine(reference, width, height, "synthetic-initialization-only-v2")
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val initializedStages = linkedSetOf<String>()
                val initializationLog = AlignmentLogSink { event -> initializedStages += event.stage }
                try {
                    val rgba = Mat()
                    try { Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA); org.opencv.android.Utils.matToBitmap(rgba, bitmap) }
                    finally { rgba.release() }
                    repeat(8) {
                        AlignmentCancellation.checkpoint("vpsg.runtime.prepare.iteration")
                        // Exercise the structured-recording path too. Synthetic results
                        // are discarded and never enter a real request or reach the UI.
                        VpsgLineScanner(repository).alignSelected(bitmap,
                            com.idvb.android.recognize.gate.ScreenRect(0.0, 0.0, width.toDouble(), height.toDouble()), map, floor, lines, initializationLog)
                    }
                } finally { bitmap.recycle() }
                log.emit(AlignmentLogEvent("vpsg.runtime.initialized-stages", labels = mapOf("stages" to initializedStages.joinToString(","),
                    "evidence" to "synthetic-class-and-worker-initialization-only")))
                ready = true
            } finally { bgr.release() }
            log.emit(AlignmentLogEvent("vpsg.runtime.prepared", measurements = mapOf("width" to width.toDouble(), "height" to height.toDouble(), "iterations" to 8.0),
                series = mapOf("rectanglesXYWH" to listOf(130.0, 110.0, 220.0, 220.0, 600.0, 110.0, 220.0, 220.0,
                    130.0, 460.0, 220.0, 220.0, 600.0, 460.0, 220.0, 220.0), "rectangleBGR" to listOf(95.0, 110.0, 150.0)),
                labels = mapOf("costScope" to "initialization-before-capture", "path" to "complete-selected-floor-pipeline",
                    "evidence" to "synthetic-initialization-only; no-acceptance-result")))
        }
    }
}
