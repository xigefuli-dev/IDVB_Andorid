package com.idvb.android.recognize.side

import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** A cut from room fill directly to unexplored black is not an observed wall.
 * When the image carries explicit bright wall strokes, retain only color contours
 * backed by those strokes. Flat-color/legacy captures retain their old extractor.
 * This uses only live pixels, with no knowledge of the map or fog geometry. */
internal object VisibleWallEvidence {
    fun retainPhotometricWalls(color: Mat, live: VpsgLiveExtractor.Observation): Int {
        val gray = Mat(); val ridges = Mat(); val supported = Mat()
        val opening = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(7.0,7.0))
        val tolerance = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(5.0,5.0))
        try {
            Imgproc.cvtColor(color,gray,Imgproc.COLOR_BGR2GRAY)
            Imgproc.morphologyEx(gray,ridges,Imgproc.MORPH_TOPHAT,opening)
            Imgproc.threshold(ridges,ridges,6.0,255.0,Imgproc.THRESH_BINARY)
            Imgproc.dilate(ridges,ridges,tolerance)
            Core.bitwise_and(live.edges,ridges,supported)
            val total = Core.countNonZero(live.edges)
            val retained = Core.countNonZero(supported)
            if (retained < 80 || retained < total*.35) return 0
            supported.copyTo(live.edges)
            Core.bitwise_and(live.proposal,ridges,live.proposal)
            return total-retained
        } finally {
            gray.release(); ridges.release(); supported.release(); opening.release(); tolerance.release()
        }
    }
}
