package com.idvb.android.alignment

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.recognize.gate.ScreenRect
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Opt-in original captures at different display sizes. Expectations are independently
 * measured corresponding image points; hidden whole-map corners are not ground truth. */
class AlignmentDisplayScaleCorpusInstrumentedTest {
    @Test fun originalFramesRemainCorrectAcrossDisplaySizes() {
        val args = InstrumentationRegistry.getArguments()
        val relative = args.getString("alignmentResolutionCorpus")
        assumeTrue("Private original-image corpus required", !relative.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val expectations = Json.parseToJsonElement(File(root, "expectations.json").readText()).jsonObject
        require(expectations.isNotEmpty())
        val output = File(context.filesDir, "test-evidence/display-scale-${UUID.randomUUID()}").apply { mkdirs() }
        val summaries = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        for ((name, value) in expectations) {
            val expected = value.jsonObject
            val reference = expected.getValue("referenceCorners").jsonArray
            val captured = expected.getValue("liveCorners").jsonArray
            require(expected.getValue("valid").jsonPrimitive.boolean && reference.size >= 8 && reference.size == captured.size)
            val archive = File(root, name).canonicalFile
            require(archive.toPath().startsWith(root.toPath()))
            AlignmentPackageReplay.open(context, archive).use { replay ->
                val original = replay.testCase.request
                for (factor in listOf(.75, 1.0, 1.5)) {
                    val bitmap = Bitmap.createScaledBitmap(original.frame,
                        (original.frame.width * factor).roundToInt(), (original.frame.height * factor).roundToInt(), true)
                    try {
                        val sx = bitmap.width.toDouble() / original.frame.width
                        val sy = bitmap.height.toDouble() / original.frame.height
                        val request = original.copy(frame = bitmap, cancellation = AlignmentCancellation(),
                            viewport = ScreenRect(original.viewport.x * sx, original.viewport.y * sy,
                                original.viewport.width * sx, original.viewport.height * sy))
                        val trace = AlignmentTrace(captureArtifacts = true)
                        trace.emit(AlignmentLogEvent("replay.display-scale", measurements = mapOf(
                            "requestedScale" to factor, "actualScaleX" to sx, "actualScaleY" to sy,
                            "sourceWidth" to original.frame.width.toDouble(), "sourceHeight" to original.frame.height.toDouble(),
                            "width" to bitmap.width.toDouble(), "height" to bitmap.height.toDouble()),
                            labels = mapOf("inputPolicy" to "original-captured-image-bilinear-display-resize; original-reference-assets",
                                "expectationPolicy" to "independently-measured-original-image-points; error-in-original-display-pixels")))
                        val report = replay.run(trace, AlignmentOutcome.ALIGNED, null, request)
                        val transform = (report.result as? AlignmentResult.Aligned)?.transform
                        val error = if (transform == null) Double.POSITIVE_INFINITY else reference.indices.maxOf { i ->
                            val p = reference[i].jsonArray; val q = captured[i].jsonArray
                            hypot((p[0].jsonPrimitive.double * transform.scale + transform.offsetX - request.viewport.x) / sx - q[0].jsonPrimitive.double,
                                (p[1].jsonPrimitive.double * transform.scale + transform.offsetY - request.viewport.y) / sy - q[1].jsonPrimitive.double)
                        }
                        val storeContext = object : ContextWrapper(context) {
                            override fun getApplicationContext() = this
                            override fun getFilesDir() = File(output, "${archive.nameWithoutExtension}-$factor")
                        }
                        val saved = AlignmentDiagnosticsStore(storeContext).record(trace,
                            AlignmentDiagnosticContext(replay.testCase.methodId, request.map, request.floor.key, request.viewport,
                                0, 0, "original-frame-display-scale-replay", floorSelectedByIndicator = request.floorSelectedByIndicator),
                            bitmap, report.result, "replay").getOrThrow()
                        val roundTrip = AlignmentPackageReplay.open(context, saved).use {
                            it.run(expectedOutcome = report.result.outcome, expectedTransform = transform)
                        }
                        val ambiguityAllowed = expected["allowAmbiguousAtScales"]?.jsonArray
                            ?.any { it.jsonPrimitive.double == factor } == true
                        val rejectionCode = (report.result as? AlignmentResult.Rejected)?.code
                        // Explicitly reviewed partial low-detail inputs may be ambiguous.
                        // This never permits an inaccurate accepted transform, or a different
                        // failure such as a resolution-dependent conflict-length gate.
                        val safelyAmbiguous = ambiguityAllowed && rejectionCode == "unique-pose"
                        val passed = ((report.passed && error <= 4.0) || safelyAmbiguous) && roundTrip.passed
                        summaries += buildJsonObject {
                            put("input", name); put("displayScale", factor); put("outcome", report.result.outcome.name)
                            put("passed", passed); put("roundTripPassed", roundTrip.passed)
                            put("reviewedAmbiguityAllowed", ambiguityAllowed); put("safelyAmbiguous", safelyAmbiguous)
                            rejectionCode?.let { put("code", it) }
                            put("maximumOriginalImagePointErrorPixels", if (error.isFinite()) JsonPrimitive(error) else JsonNull)
                            put("algorithmMs", report.elapsedMilliseconds); put("runningSource", replay.runningSourceFingerprint)
                            put("diagnostics", saved.relativeTo(output).path); put("expectation", expected)
                        }
                        if (!passed) failures += "$name at $factor: ${report.result}; observed error=$error"
                    } finally { if (bitmap !== original.frame) bitmap.recycle() }
                }
            }
        }
        File(output, "summary.json").writeText(JsonArray(summaries).toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
