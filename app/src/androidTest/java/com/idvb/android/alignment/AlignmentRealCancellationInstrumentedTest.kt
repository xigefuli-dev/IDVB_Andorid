package com.idvb.android.alignment

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class AlignmentRealCancellationInstrumentedTest {
    @Test fun cancellingRealTranslationExitsAndNextRealRequestRunsOnTheSameWorker() {
        val args = InstrumentationRegistry.getArguments()
        val relative = args.getString("alignmentCorpus")
        assumeTrue("Original private inputs required", !relative.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun privatePath(path: String) = File(context.cacheDir, path).canonicalFile.also {
            require(it.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        }
        val archive = privatePath(relative!!).listFiles { file -> file.extension == "zip" }!!.sortedBy { it.name }.first { file ->
            ZipFile(file).use { zip ->
                Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().readText())
                    .jsonObject.getValue("result").jsonObject["outcome"]?.jsonPrimitive?.content == "ALIGNED"
            }
        }
        val supplements = args.getString("alignmentSupplementalReferences")?.let(::privatePath)
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                AlignmentPackageReplay.open(context, archive, supplements).use { replay ->
                    val trace = AlignmentTrace()
                    var requested = false
                    val log = AlignmentLogSink { event ->
                        trace.record(event)
                        if (event.stage == "vpsg.translation.search" && !requested) {
                            requested = true
                            replay.testCase.request.cancellation.cancel("eye-hidden-real-replay")
                        }
                    }
                    val failure = runCatching { replay.run(log) }.exceptionOrNull()
                    assertTrue(requested)
                    assertTrue("$failure", failure is AlignmentCancelledException)
                    val acknowledgement = trace.snapshot().single { it.stage == "cancelled" }
                    assertTrue(acknowledgement.measurements.getValue("acknowledgementMs") < 1000.0)
                    assertTrue(acknowledgement.labels.getValue("checkpoint").startsWith("vpsg.translation"))
                    assertFalse(trace.snapshot().any { it.stage == "finish" })
                }
                assertFalse(Thread.currentThread().isInterrupted)
                AlignmentPackageReplay.open(context, archive, supplements).use { replay ->
                    assertTrue("A new request must still complete", replay.run().passed)
                }
            }.get(15, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
    }
}
