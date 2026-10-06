package com.idvb.android.alignment

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Host-only ZIP fixtures: no device storage, screenshot or media publication. */
class MapOpenReadinessReplayTest {
    private fun archive(sequences: List<Long?>, received: Long = 123_000_000L): File {
        val pixels = ByteBuffer.allocate(MapOpenReadiness.WIDTH * MapOpenReadiness.HEIGHT * 4).apply {
            repeat(MapOpenReadiness.WIDTH * MapOpenReadiness.HEIGHT) { putInt(0xff687580.toInt()) }
        }.array()
        val hash = MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it) }
        val document = buildJsonObject {
            put("readinessReplayReady", true)
            putJsonArray("events") {
                sequences.forEachIndexed { index, sequence -> add(buildJsonObject {
                    put("stage", "readiness.frame")
                    putJsonObject("measurements") {
                        put("attempt", index + 1)
                        sequence?.let { put("frameSequence", it); put("frameReceivedNanos", received + index) }
                    }
                    putJsonObject("labels") { put("decision", if (index == 0) "wait" else "ready") }
                }) }
            }
            putJsonObject("artifacts") {
                sequences.indices.forEach { index -> putJsonObject("readiness-${index + 1}.argb") { put("sha256", hash) } }
            }
        }
        return File.createTempFile("idvb-readiness-host-", ".zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.json")); zip.write(document.toString().toByteArray()); zip.closeEntry()
                sequences.indices.forEach { index ->
                    zip.putNextEntry(ZipEntry("readiness-${index + 1}.argb")); zip.write(pixels); zip.closeEntry()
                }
            }
        }
    }

    @Test fun projectionIdentitySurvivesRecordedInputReplay() {
        val zip = archive(listOf(10L, 11L))
        try {
            val frames = MapOpenReadinessReplay.run(zip)
            assertEquals(listOf(10L, 11L), frames.map { it.frameSequence })
            assertEquals(listOf(123_000_000L, 123_000_001L), frames.map { it.frameReceivedNanos })
            assertTrue(frames.all { it.recordedReady == it.replayed.ready })
        } finally { check(zip.delete()) }
    }

    @Test fun repeatedOrReversedPhysicalFramesCannotPassReplay() {
        for (sequences in listOf(listOf(10L, 10L), listOf(10L, 9L))) {
            val zip = archive(sequences)
            try {
                try { MapOpenReadinessReplay.run(zip); fail("Duplicate or reversed observations must not pass") }
                catch (error: IllegalArgumentException) { assertTrue(checkNotNull(error.message).contains("physical projection frame")) }
            } finally { check(zip.delete()) }
        }
    }

    @Test fun olderArchivesReplayWithoutInventingPhysicalFrameIdentity() {
        val zip = archive(listOf(null, null))
        try {
            val frames = MapOpenReadinessReplay.run(zip)
            assertTrue(frames.all { it.frameSequence == null && it.frameReceivedNanos == null })
            assertTrue(frames.all { it.recordedReady == it.replayed.ready })
        } finally { check(zip.delete()) }
    }
}
