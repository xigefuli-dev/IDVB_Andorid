package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Private opt-in real-frame replay. No user captures are bundled in the APK or source tree.
 * adb instrumentation arguments: alignmentCorpus=<target cache relative path>,
 * alignmentExpected=ALIGNED (omit to check the original recorded outcomes).
 */
class AlignmentFailureCorpusInstrumentedTest {
    @Test fun replaySavedFailuresWithCurrentProductionAlgorithm() {
        val args = InstrumentationRegistry.getArguments()
        val relative = args.getString("alignmentCorpus")
        assumeTrue("No private real-frame corpus supplied", !relative.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, relative!!).canonicalFile
        require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val archives = root.listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }
        assertTrue("Empty alignment corpus", archives.isNotEmpty())
        // Optional independently measured expectations; never silently bless the current output.
        val overrides = args.getString("alignmentExpectedTransforms")?.let { path ->
            val file = File(context.cacheDir, path).canonicalFile
            require(file.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
            Json.parseToJsonElement(file.readText()).jsonObject
        }
        val output = File(context.filesDir, "test-evidence/replay-${UUID.randomUUID()}").apply { mkdirs() }
        val summaries = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        val supplements = args.getString("alignmentSupplementalReferences")?.let {
            File(context.cacheDir, it).canonicalFile.also { file -> require(file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) }
        }
        for (archive in archives) AlignmentPackageReplay.open(context, archive, supplements).use { replay ->
            val trace = AlignmentTrace(captureArtifacts = true)
            val override = overrides?.get(archive.name)?.jsonObject
            val expected = override?.get("outcome")?.jsonPrimitive?.content?.let(AlignmentOutcome::valueOf)
                ?: args.getString("alignmentExpected")?.let(AlignmentOutcome::valueOf) ?: replay.testCase.expectedOutcome
            val transform = override?.get("transform")?.jsonObject?.let { value ->
                AlignmentTransform(value.getValue("scale").jsonPrimitive.double,
                    value.getValue("offsetX").jsonPrimitive.double, value.getValue("offsetY").jsonPrimitive.double,
                    value.getValue("referenceWidth").jsonPrimitive.int, value.getValue("referenceHeight").jsonPrimitive.int)
            } ?: replay.testCase.expectedTransform
            val report = replay.run(trace, expected, transform)
            val expectedCode = override?.get("code")?.jsonPrimitive?.content
            val actualCode = (report.result as? AlignmentResult.Rejected)?.code
                ?: (report.result as? AlignmentResult.Unavailable)?.code
            var passed = report.passed && (expectedCode == null || expectedCode == actualCode)
            val request = replay.testCase.request
            // Isolated output directory and inputs; never mutate the active map catalog.
            val storeContext = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext() = this
                override fun getFilesDir() = File(output, archive.nameWithoutExtension)
            }
            val saved = AlignmentDiagnosticsStore(storeContext).record(trace,
                AlignmentDiagnosticContext(replay.testCase.methodId, request.map, request.floor.key,
                    request.viewport, 0, 0, "saved-failure-replay"), request.frame, report.result,
                "replay").getOrThrow()
            val roundTrip = AlignmentPackageReplay.open(context, saved).use { recorded ->
                recorded.run(expectedOutcome = report.result.outcome,
                    expectedTransform = (report.result as? AlignmentResult.Aligned)?.transform)
            }
            val roundTripCode = (roundTrip.result as? AlignmentResult.Rejected)?.code
                ?: (roundTrip.result as? AlignmentResult.Unavailable)?.code
            val roundTripPassed = roundTrip.passed && roundTripCode == actualCode
            passed = passed && roundTripPassed
            summaries += buildJsonObject {
                put("input", archive.name); put("outcome", report.result.outcome.name)
                put("expected", expected.name); put("passed", passed)
                put("roundTripPassed", roundTripPassed)
                actualCode?.let { put("code", it) }
                put("sourceMatches", replay.sourceMatches)
                put("recordedSource", replay.recordedSourceFingerprint)
                put("runningSource", replay.runningSourceFingerprint)
                put("algorithmMs", report.elapsedMilliseconds); put("diagnostics", saved.relativeTo(output).path)
                put("expectationSource", if (override != null) "explicit-reviewed-expectation" else if (args.getString("alignmentExpected") != null)
                    "requested-outcome; original-transform-only-when-recorded" else "original-recording")
                put("supplementalReferenceHashes", JsonArray(replay.supplementedReferences.map(::JsonPrimitive)))
                override?.let { put("expectation", it) }
                report.maximumCornerErrorPixels?.let { put("cornerErrorPixels", it) }
                report.relativeScaleError?.let { put("relativeScaleError", it) }
                (report.result as? AlignmentResult.Aligned)?.transform?.let {
                    put("scale", it.scale); put("x", it.offsetX); put("y", it.offsetY)
                }
            }
            if (!passed) failures += "${archive.name}: ${report.result}; expected code=$expectedCode"
        }
        File(output, "summary.json").writeText(JsonArray(summaries).toString())
        android.util.Log.i("IDVB-Replay", "Saved ${archives.size} replay results: $output")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
