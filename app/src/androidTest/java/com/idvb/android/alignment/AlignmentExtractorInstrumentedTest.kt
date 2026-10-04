package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.opencv.core.Core
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AlignmentExtractorInstrumentedTest {
    @Test fun fusedSemanticMasksPreserveEveryHsvByteAndExclusion() {
        com.idvb.android.recognize.cv.OpenCvRuntime.requireAvailable()
        val kernel = com.idvb.android.recognize.vpsg.VpsgNativeKernel
        assertTrue(kernel.available)
        val hsv = org.opencv.core.Mat(256, 257, org.opencv.core.CvType.CV_8UC3)
        val exclusion = org.opencv.core.Mat(256, 257, org.opencv.core.CvType.CV_8UC1)
        val room = org.opencv.core.Mat(); val wrap = org.opencv.core.Mat(); val corridor = org.opencv.core.Mat()
        val nativeRoom = org.opencv.core.Mat(); val nativeCorridor = org.opencv.core.Mat()
        val red = org.opencv.core.Mat(); val highRed = org.opencv.core.Mat(); val yellow = org.opencv.core.Mat()
        val nativeRed = org.opencv.core.Mat(); val nativeYellow = org.opencv.core.Mat()
        val bytes = ByteArray(256 * 257 * 3)
        val invalid = ByteArray(256 * 257) { if (it % 3 == 0) 1 else if (it % 7 == 0) -1 else 0 }
        try {
            exclusion.put(0, 0, invalid)
            for (h in 0..255) {
                for (s in 0..255) for (v in 0..256) {
                    val at = (s * 257 + v) * 3
                    bytes[at] = h.toByte(); bytes[at + 1] = s.toByte(); bytes[at + 2] = v.toByte()
                }
                hsv.put(0, 0, bytes)
                Core.inRange(hsv, org.opencv.core.Scalar(0.0, 18.0, 82.0), org.opencv.core.Scalar(25.0, 165.0, 200.0), room)
                Core.inRange(hsv, org.opencv.core.Scalar(170.0, 18.0, 82.0), org.opencv.core.Scalar(179.0, 165.0, 200.0), wrap)
                Core.bitwise_or(room, wrap, room)
                Core.inRange(hsv, org.opencv.core.Scalar(95.0, 14.0, 82.0), org.opencv.core.Scalar(130.0, 105.0, 200.0), corridor)
                room.setTo(org.opencv.core.Scalar.all(0.0), exclusion)
                corridor.setTo(org.opencv.core.Scalar.all(0.0), exclusion)
                Core.inRange(hsv, org.opencv.core.Scalar(0.0, 65.0, 95.0), org.opencv.core.Scalar(5.0, 255.0, 255.0), red)
                Core.inRange(hsv, org.opencv.core.Scalar(160.0, 65.0, 95.0), org.opencv.core.Scalar(179.0, 255.0, 255.0), highRed)
                Core.bitwise_or(red, highRed, red)
                Core.inRange(hsv, org.opencv.core.Scalar(18.0, 100.0, 160.0), org.opencv.core.Scalar(38.0, 255.0, 255.0), yellow)
                kernel.semanticMasks(hsv, exclusion, nativeRoom, nativeCorridor, nativeRed, nativeYellow)
                for ((expected, actual) in listOf(room to nativeRoom, corridor to nativeCorridor, red to nativeRed, yellow to nativeYellow)) {
                    val a = ByteArray(invalid.size).also { expected.get(0, 0, it) }
                    val b = ByteArray(invalid.size).also { actual.get(0, 0, it) }
                    assertArrayEquals("h=$h", a, b)
                }
            }
        } finally { listOf(hsv, exclusion, room, wrap, corridor, nativeRoom, nativeCorridor,
            red, highRed, yellow, nativeRed, nativeYellow).forEach { it.release() } }
    }
    @Test fun ownedMaskStreamsRemainExactAfterSourceMatIsReleased() {
        com.idvb.android.recognize.cv.OpenCvRuntime.requireAvailable()
        assertTrue(com.idvb.android.recognize.vpsg.VpsgNativeKernel.available)
        val source = org.opencv.core.Mat(257, 513, org.opencv.core.CvType.CV_8UC1)
        val expected = ByteArray(257 * 513) { (it * 37 + 11).toByte() }
        val trace = AlignmentTrace(captureArtifacts = true)
        try {
            source.put(0, 0, expected)
            com.idvb.android.recognize.vpsg.VpsgMaskEvidence.attach(trace, "owned.gray8", source)
            source.setTo(org.opencv.core.Scalar.all(0.0))
        } finally { source.release() }
        val data = trace.artifactDataSnapshot().getValue("owned.gray8")
        assertTrue("A direct owned copy is required", data is AlignmentArtifact.Direct)
        repeat(2) {
            val output = java.io.ByteArrayOutputStream()
            data.writeTo(output, ByteArray(97))
            assertArrayEquals(expected, output.toByteArray())
        }
        assertArrayEquals(expected, trace.artifactSnapshot().getValue("owned.gray8"))
    }
    @Test fun hsvConsumerDomainsPreserveEveryEightBitBgrColor() {
        com.idvb.android.recognize.cv.OpenCvRuntime.requireAvailable()
        assertTrue(com.idvb.android.recognize.vpsg.VpsgNativeKernel.available)
        // 256 vector pixels plus one scalar tail on each row exercise both ARM
        // conversion paths while retaining the exhaustive 24-bit color domain.
        val source = org.opencv.core.Mat(256, 257, org.opencv.core.CvType.CV_8UC3)
        val expected = org.opencv.core.Mat(); val actual = org.opencv.core.Mat()
        val bytes = ByteArray(256 * 257 * 3)
        try {
            for (r in 0..255) for (offset in listOf(0, 200)) {
                for (g in 0..255) for (b in 0..255) {
                    val at = (((g + 174) % 256) * 257 + (b + offset) % 256) * 3
                    bytes[at] = b.toByte(); bytes[at + 1] = g.toByte(); bytes[at + 2] = r.toByte()
                }
                for (g in 0..255) {
                    val at = (((g + 174) % 256) * 257 + 256) * 3
                    bytes[at] = ((g * 7 + r + offset) % 256).toByte()
                    bytes[at + 1] = g.toByte(); bytes[at + 2] = r.toByte()
                }
                source.put(0, 0, bytes)
                org.opencv.imgproc.Imgproc.cvtColor(source, expected, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV)
                com.idvb.android.recognize.vpsg.VpsgNativeKernel.hsvConsumers(source, actual)
                val a = ByteArray(bytes.size).also { expected.get(0, 0, it) }
                val b = ByteArray(bytes.size).also { actual.get(0, 0, it) }
                val mismatch = a.indices.firstOrNull { index ->
                    val pixel = index / 3; val x = pixel % 257; val y = pixel / 257; val at = pixel * 3
                    val value = a[at + 2].toInt() and 255
                    val hue = a[at].toInt() and 255; val saturation = a[at + 1].toInt() and 255
                    val consumed = value >= 82 || (x < (257 * .28).toInt() && y >= (256 * .68).toInt() && value >= 45 && hue in 35..95 && saturation >= 55)
                    consumed && a[index] != b[index]
                }
                assertNull("r=$r offset=$offset mismatchByte=$mismatch expected=${mismatch?.let { a[it].toInt() and 255 }} actual=${mismatch?.let { b[it].toInt() and 255 }}", mismatch)
            }
        } finally { source.release(); expected.release(); actual.release() }
    }
    @Test fun sharedGradientsAndSemanticRegionsPreserveOriginalFrameMasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, requireNotNull(InstrumentationRegistry.getArguments().getString("alignmentCorpus")))
        for (archive in root.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name })
            AlignmentPackageReplay.open(context, archive).use { replay ->
                val bgr = CvImages.bitmapToBgr(replay.testCase.request.frame)
                fun masks(optimized: Boolean) = VpsgLiveExtractor.extract(bgr, visibilityScopedReverse = true, optimized = optimized).use { live ->
                    listOf(live.edges, live.valid, live.proposal, requireNotNull(live.revealed), requireNotNull(live.wallEdges)).map { mat ->
                        ByteArray(mat.rows() * mat.cols()).also { mat.get(0, 0, it) }
                    }
                }
                try {
                    val expected = masks(false); val actual = masks(true)
                    actual.forEachIndexed { i, mask -> assertArrayEquals("${archive.name} mask=$i", expected[i], mask) }
                    VpsgLiveExtractor.extract(bgr, visibilityScopedReverse = true).use { live ->
                        val full = org.opencv.core.Mat(); val inverted = org.opencv.core.Mat()
                        val local = com.idvb.android.recognize.vpsg.VpsgObservationDistance.create(live.edges, requireNotNull(live.revealed))
                        try {
                            Core.bitwise_not(live.edges, inverted)
                            org.opencv.imgproc.Imgproc.distanceTransform(inverted, full, org.opencv.imgproc.Imgproc.DIST_L2, org.opencv.imgproc.Imgproc.DIST_MASK_PRECISE)
                            val a = FloatArray(bgr.rows() * bgr.cols()).also { full.get(0, 0, it) }
                            val b = FloatArray(a.size).also { local.get(0, 0, it) }
                            val domain = org.opencv.core.Mat()
                            try {
                                Core.bitwise_or(live.edges, live.revealed, domain)
                                val bounds = org.opencv.imgproc.Imgproc.boundingRect(domain)
                                for (y in (bounds.y - 1).coerceAtLeast(0) until (bounds.y + bounds.height + 1).coerceAtMost(bgr.rows()))
                                    for (x in (bounds.x - 1).coerceAtLeast(0) until (bounds.x + bounds.width + 1).coerceAtMost(bgr.cols()))
                                        assertEquals("${archive.name} distance $x,$y", a[y * bgr.cols() + x], b[y * bgr.cols() + x], 0f)
                            } finally { domain.release() }
                        } finally { full.release(); inverted.release(); local.release() }
                    }
                } finally { bgr.release() }
            }
    }
    @Test fun threadBudgetsPreserveEveryObservationPixel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val root = File(context.cacheDir, requireNotNull(args.getString("alignmentCorpus")))
        val originalThreads = Core.getNumThreads()
        val rows = mutableListOf<String>()
        try {
            for (archive in root.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }.take(2))
                AlignmentPackageReplay.open(context, archive).use { replay ->
                    val bgr = CvImages.bitmapToBgr(replay.testCase.request.frame)
                    try {
                        var expected: List<ByteArray>? = null
                        for (threads in listOf(1, 2, 4, originalThreads).distinct()) {
                            Core.setNumThreads(threads)
                            repeat(4) { iteration ->
                                val trace = AlignmentTrace()
                                val start = System.nanoTime()
                                VpsgLiveExtractor.extract(bgr, trace, visibilityScopedReverse = true).use { live ->
                                    val elapsed = (System.nanoTime() - start) / 1e6
                                    val masks = listOf(live.edges, live.valid, live.proposal, requireNotNull(live.revealed), requireNotNull(live.wallEdges)).map { mat ->
                                        ByteArray(mat.rows() * mat.cols()).also { mat.get(0, 0, it) }
                                    }
                                    if (expected == null) expected = masks else masks.forEachIndexed { i, mask -> assertArrayEquals(expected!![i], mask) }
                                    rows += "${archive.name} threads=$threads iteration=$iteration extractMs=$elapsed"
                                }
                            }
                        }
                    } finally { bgr.release() }
                }
        } finally { Core.setNumThreads(originalThreads) }
        File(context.filesDir, "test-evidence/extractor-threads.txt").apply { parentFile!!.mkdirs() }.writeText(rows.joinToString("\n"))
    }
}
