package com.idvb.android.recognize

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.CvImages
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CandidatePreviewInstrumentedTest {
    @Test fun calibratedCaptureKeepsNativePixelsAtPhoneAndTabletResolutions() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = StringBuilder()
        for ((short, long) in listOf(720 to 1280, 1080 to 1920, 1080 to 2400,
            1440 to 2560, 1600 to 2560, 1262 to 1920)) {
            for ((width, height) in listOf(short to long, long to short)) {
                val session = ScreenCaptureSession(target)
                val factory = ScreenCaptureSession::class.java.getDeclaredMethod("newReader",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                val reader = factory.invoke(session, width, height) as ImageReader
                ScreenCaptureSession::class.java.getDeclaredField("reader")
                    .apply { isAccessible = true }.set(session, reader)
                try {
                    val crop = Rect((width * .2).toInt(), (height * .35).toInt(),
                        (width * .8).toInt(), (height * .65).toInt())
                    session.prepareCapture()
                    val canvas = reader.surface.lockCanvas(null)
                    canvas.drawColor(Color.GREEN)
                    val paint = Paint().apply { color = Color.RED; isAntiAlias = false }
                    canvas.drawRect(crop.left.toFloat(), crop.top.toFloat(), crop.left + 1f, crop.bottom.toFloat(), paint)
                    reader.surface.unlockCanvasAndPost(canvas)
                    val done = CountDownLatch(1)
                    val result = AtomicReference<Result<Bitmap>>()
                    session.capture(crop) { result.set(it); done.countDown() }
                    assertTrue("Capture timed out at ${width}x$height", done.await(4, TimeUnit.SECONDS))
                    val captured = result.get().getOrThrow()
                    try {
                        assertEquals(crop.width(), captured.width)
                        assertEquals(crop.height(), captured.height)
                        assertEquals(Color.RED, captured.getPixel(0, captured.height / 2))
                        assertEquals(Color.GREEN, captured.getPixel(1, captured.height / 2))
                        evidence.append("${width}x$height -> ${captured.width}x${captured.height}: native 1px boundary preserved\n")
                    } finally { captured.recycle() }
                } finally { session.close() }
            }
        }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        File(testContext.filesDir, "test-evidence/native-capture-sizes.txt").apply {
            check(parentFile!!.mkdirs() || parentFile!!.isDirectory)
            writeText(evidence.toString())
        }
    }

    @Test fun tinyTentativePoseDoesNotShrinkCardsOrRecognitionInputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val isolated = File(target.cacheDir, "candidate-preview-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        val source = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val frame = Bitmap.createBitmap(2560, 1600, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.RED)
            // A small selected region in a much larger source must be decoded first.
            for (y in 300 until 600) for (x in 400 until 800) source.setPixel(x, y, Color.GREEN)
            val repository = MapRepository(context)
            val folder = File(repository.mapsRoot, "preview/maps").apply { mkdirs() }
            val file = File(folder, "floor.png")
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val floor = FloorRecord("1f", "1F", 0, "preview/maps/floor.png", 1600, 1200,
                previewRegion = NormalizedRect(.25, .25, .25, .25))
            val map = MapRecord("preview", "class", "Preview", "preview", 1, listOf(floor))
            val candidate = RecognitionCandidate(map, "1f", CandidateDisposition.NEEDS_VERIFICATION,
                structureScale = .4, structureOffsetX = 2000.0, structureOffsetY = 1200.0,
                evidenceLabel = "tentative")
            instrumentation.runOnMainSync {
                for (manual in listOf(false, true)) {
                    val result = RecognitionResult(frame, listOf(candidate), ScreenRect(0.0, 0.0, 2560.0, 1600.0))
                    val view = CandidateSelectionView(context, result, repository, manual)
                    for (pose in listOf(candidate, candidate.copy(structureScale = 0.0))) {
                        val preview = requireNotNull(view.createCandidatePreview(pose))
                        try {
                            assertEquals(400, preview.width)
                            assertEquals(300, preview.height)
                            assertEquals(Color.GREEN, preview.getPixel(0, 0))
                            assertEquals(Color.GREEN, preview.getPixel(399, 299))
                        } finally { preview.recycle() }
                    }
                    assertSame(frame, result.capturedRegion)
                    assertFalse(frame.isRecycled)
                }
            }
            // Recognition still reads source pixels, independently of display downsampling.
            assertTrue(OpenCvRuntime.initialize())
            val live = CvImages.bitmapToBgr(frame)
            val reference = CvImages.loadGray(file)
            try {
                assertEquals(2560, live.cols())
                assertEquals(1600, live.rows())
                assertEquals(1600, reference.cols())
                assertEquals(1200, reference.rows())
            } finally { live.release(); reference.release() }
        } finally {
            source.recycle()
            frame.recycle()
            isolated.deleteRecursively()
        }
    }
}
