package com.idvb.android.recognize.vpsg

import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentLogSink
import org.opencv.core.Mat

internal object VpsgMaskEvidence {
    fun attach(log: AlignmentLogSink, name: String, mat: Mat) {
        if (VpsgNativeKernel.available && mat.isContinuous) {
            log.attachDirect(name) {
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.before")
                val size = Math.toIntExact(mat.total() * mat.elemSize())
                val copy = java.nio.ByteBuffer.allocateDirect(size)
                copy.put(VpsgNativeKernel.borrowBuffer(mat.dataAddr(), size.toLong())); copy.rewind()
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.after")
                copy.asReadOnlyBuffer()
            }
        } else log.attach(name) { ByteArray(Math.toIntExact(mat.total() * mat.elemSize())).also { mat.get(0, 0, it) } }
    }
}
