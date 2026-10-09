package com.idvb.android.recognize.vpsg

import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentLogSink
import org.opencv.core.Mat

internal object VpsgMaskEvidence {
    fun attach(log: AlignmentLogSink, name: String, mat: Mat) {
        if (VpsgNativeKernel.available && mat.isContinuous) {
            log.attachStream(name) { output ->
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.before")
                val size = Math.toIntExact(mat.total() * mat.elemSize())
                val input = VpsgNativeKernel.borrowBuffer(mat.dataAddr(), size.toLong())
                val scratch = ByteArray(64 * 1024)
                while (input.hasRemaining()) {
                    AlignmentCancellation.checkpoint("diagnostics.copy-mask.chunk")
                    val count = minOf(input.remaining(), scratch.size)
                    input.get(scratch, 0, count)
                    output.write(scratch, 0, count)
                }
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.after")
            }
        } else log.attachStream(name) { output ->
            val row = ByteArray(Math.toIntExact(mat.cols() * mat.elemSize()))
            repeat(mat.rows()) { y ->
                AlignmentCancellation.checkpoint("diagnostics.copy-mask.row")
                check(mat.get(y, 0, row) == row.size) { "Incomplete mask row: $name/$y" }
                output.write(row)
            }
        }
    }
}
