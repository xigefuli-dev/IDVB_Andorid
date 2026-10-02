package com.idvb.android.alignment

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AutoMapOpenReplayTest {
    private val pixels = IntArray(768) { if (it / 32 % 4 < 2) 0xff687580.toInt() else 0xffc0c8d0.toInt() }
    private fun document(score: Double = 1.0, unknownOnly: Boolean = false, schema: Int = 3): String = buildJsonObject {
        put("schemaVersion", schema)
        put("replayReady", true)
        put("sourceFingerprint", "a".repeat(64))
        put("algorithmVersion", "builtin-sidebar-window-v2")
        putJsonObject("reference") { put("pixels", JsonArray(pixels.map(::JsonPrimitive))) }
        putJsonObject("configuration") {
            put("openThreshold", .85); put("closeThreshold", .72)
            put("openFrames", 2); put("closeFrames", 2); put("maximumAttempts", 3)
            put("retryCooldownMs", 1_000); put("maximumFrameAgeMs", 1_000)
            put("sidebarWidthFraction", 1.0)
        }
        putJsonArray("frames") {
            add(buildJsonObject { put("pixels", JsonNull) })
            if (!unknownOnly) add(buildJsonObject {
                put("pixels", JsonArray(pixels.map(::JsonPrimitive)))
                put("score", score); put("color", 1.0); put("brightness", 1.0); put("edge", 1.0)
                // This fixture has identical columns: the first tied best probe is x = -1.
                put("windowLeft", 0.0); put("windowWidth", 1.0); put("offsetX", -1); put("offsetY", 0)
            })
        }
    }.toString()

    @Test fun exactSpatialExportReplaysWhileUnknownCaptureRemainsUnknown() {
        val result = AutoMapOpenReplay.replay(document())
        assertEquals(1, result.knownFrames)
        assertEquals(1, result.unknownFrames)
    }

    @Test fun alteredScoresMissingEvidenceAndOldUnreliableConfigCannotPass() {
        for (input in listOf(document(score = .8), document(unknownOnly = true), document(schema = 2),
            document().replace("\"replayReady\":true", "\"replayReady\":false"),
            document().replace("\"offsetX\":-1", "\"offsetX\":0"),
            document().replace("\"sidebarWidthFraction\":1.0", "\"sidebarWidthFraction\":-1.0"))) {
            try {
                AutoMapOpenReplay.replay(input)
                fail("missing or modified replay evidence must not pass")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
