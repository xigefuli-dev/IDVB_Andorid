package com.idvb.android.recognize

import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentLogSink
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Keep complete numeric grids off the JSON object graph and stream them to private evidence.
 * Every large series is lossless IEEE754 little-endian doubles in a zlib stream. */
internal class RecognitionEvidenceLog(private val sink: AlignmentLogSink) : AlignmentLogSink {
    private val sequence = AtomicLong()
    override val enabled get() = sink.enabled
    override fun record(event: AlignmentLogEvent) {
        if (!enabled) return
        val labels = event.labels.toMutableMap()
        val small = event.series.filter { (key, values) ->
            if (values.size <= 128) true else {
                val name = "series-${sequence.incrementAndGet()}.f64le.zlib"
                sink.attachStream(name) { output ->
                    val deflater = Deflater(Deflater.BEST_SPEED)
                    try {
                        val encoded = DeflaterOutputStream(output, deflater, 64*1024)
                        val buffer = java.nio.ByteBuffer.allocate(8192).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (value in values) {
                            if (buffer.remaining() < 8) {
                                AlignmentCancellation.checkpoint("scan.diagnostics.series-chunk")
                                encoded.write(buffer.array(),0,buffer.position()); buffer.clear()
                            }
                            buffer.putDouble(value)
                        }
                        encoded.write(buffer.array(),0,buffer.position()); encoded.finish()
                    } finally { deflater.end() }
                }
                labels["seriesArtifact.$key"] = name
                labels["seriesCount.$key"] = values.size.toString()
                labels["seriesEncoding"] = "zlib-ieee754-f64-little-endian"
                false
            }
        }
        sink.record(event.copy(series=small,labels=labels))
    }
    override fun attach(name: String, bytes: () -> ByteArray) = sink.attach(name,bytes)
    override fun attachStream(name: String, encode: (java.io.OutputStream) -> Unit) = sink.attachStream(name,encode)
}
