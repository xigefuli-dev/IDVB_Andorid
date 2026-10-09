package com.idvb.android.alignment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiagnosticLatencyInstrumentedTest {
    @Test fun scanNumericEvidenceStreamsLosslesslyWithoutLargeJsonSeries() {
        val root=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"scan-series-${java.util.UUID.randomUUID()}")
        val trace=AlignmentTrace(captureArtifacts=true,spoolDirectory=root)
        try {
            val values=List(100_003) { i -> when(i%4) { 0 -> -0.0; 1 -> Double.POSITIVE_INFINITY; 2 -> i/3.0; else -> -i.toDouble() } }
            com.idvb.android.recognize.RecognitionEvidenceLog(trace).record(AlignmentLogEvent("fixture",series=mapOf("grid" to values)))
            val event=trace.snapshot().single { it.stage=="fixture" }
            assertTrue(event.series.isEmpty())
            val bytes=trace.artifactSnapshot().getValue(event.labels.getValue("seriesArtifact.grid"))
            val decoded=java.util.zip.InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
            assertEquals(values.size*8,decoded.size)
            val buffer=java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            values.forEach { assertEquals(it.toRawBits(),buffer.double.toRawBits()) }
        } finally { trace.releaseWrittenArtifacts(); root.deleteRecursively() }
    }

    @Test fun fastStreamingPngPreservesPixelsAndCancellation() {
        val frame = Bitmap.createBitmap(513, 257, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(frame.width * frame.height) { index ->
                android.graphics.Color.argb(if (index % 7 == 0) 128 else 255,
                    index % 256, index / 13 % 256, index / 29 % 256)
            }
            frame.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val encoded = ByteArrayOutputStream().also { AlignmentFrameSpool.writePng(frame, it) }.toByteArray()
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
            try { assertTrue("Full PNG must round-trip the bitmap including alpha", frame.sameAs(decoded)) }
            finally { decoded.recycle() }
            val token = AlignmentCancellation().apply { cancel("test") }
            assertTrue(runCatching { token.run { AlignmentFrameSpool.writePng(frame, ByteArrayOutputStream()) } }
                .exceptionOrNull() is java.util.concurrent.CancellationException)
        } finally { frame.recycle() }
    }
}
