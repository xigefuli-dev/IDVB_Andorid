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
                // Raw doubles are copied on the scan thread; zlib runs on the diagnostics spool thread.
                sink.attachEncoded(name, capture = {
                    AlignmentCancellation.checkpoint("scan.diagnostics.series-copy")
                    val raw = java.nio.ByteBuffer.allocate(Math.multiplyExact(values.size, 8)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (value in values) raw.putDouble(value)
                    raw.array()
                }) { raw, output ->
                    val deflater = Deflater(Deflater.BEST_SPEED)
                    try {
                        val encoded = DeflaterOutputStream(output, deflater, 64*1024)
                        encoded.write(raw); encoded.finish()
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
    override fun attachEncoded(name: String, capture: () -> ByteArray, encode: (ByteArray, java.io.OutputStream) -> Unit) =
        sink.attachEncoded(name,capture,encode)
}
