package com.idvb.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheCleanerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun calculateAndClearDeletesCachesWhilePreservingEssentialData() {
        val cacheRoot = temporary.newFolder("cache")
        val codeCacheRoot = temporary.newFolder("code_cache")
        val filesRoot = temporary.newFolder("files")

        // 1. Set up essential user data in filesDir
        val mapsDir = File(filesRoot, "idvb/maps").apply { mkdirs() }
        val mapFile = File(mapsDir, "map_data.json").apply { writeText("critical-map-content") }

        val subFile = File(filesRoot, "idvb/subscriptions.json").apply {
            parentFile?.mkdirs()
            writeText("{\"subscriptions\":[]}")
        }

        val autoMapDir = File(filesRoot, "idvb/auto-map-open").apply { mkdirs() }
        val autoMapRef = File(autoMapDir, "reference-landscape.json").apply { writeText("template") }

        // 2. Set up non-essential caches
        // Diagnostics
        val diagSessionsDir = File(filesRoot, "idvb/diagnostics/sessions/session-1").apply { mkdirs() }
        val logFile = File(diagSessionsDir, "app.log").apply { writeBytes(ByteArray(1000)) }
        val captureFile = File(diagSessionsDir, "captured.png").apply { writeBytes(ByteArray(2000)) }

        val legacyDiagDir = File(filesRoot, "idvb/diagnostics/recognition").apply { mkdirs() }
        val legacyZip = File(legacyDiagDir, "recognition-1.zip").apply { writeBytes(ByteArray(3000)) }

        // Cache dir
        val importTmp = File(cacheRoot, "idvb/import/tmp.idvm").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4000))
        }
        val refCache = File(cacheRoot, "alignment-reference/ref.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(5000))
        }

        // Code cache dir
        val dexCache = File(codeCacheRoot, "test.dex").apply { writeBytes(ByteArray(6000)) }

        // Test evidence
        val evidenceFile = File(filesRoot, "test-evidence/evidence.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(7000))
        }

        val cleaner = CacheCleaner(
            cacheDir = cacheRoot,
            codeCacheDir = codeCacheRoot,
            filesDir = filesRoot,
        )

        // Verify calculation
        val breakdown = cleaner.calculateCache()
        val expectedDiagBytes = 1000L + 2000L + 3000L + 7000L // 13000
        val expectedTempBytes = 4000L + 5000L + 6000L // 15000
        assertEquals(expectedDiagBytes, breakdown.diagnosticBytes)
        assertEquals(expectedTempBytes, breakdown.tempCacheBytes)
        assertEquals(expectedDiagBytes + expectedTempBytes, breakdown.totalBytes)

        // Clear cache
        val freed = cleaner.clearCache()
        assertEquals(expectedDiagBytes + expectedTempBytes, freed)

        // Verify non-essential caches are deleted
        assertFalse(logFile.exists())
        assertFalse(captureFile.exists())
        assertFalse(legacyZip.exists())
        assertFalse(importTmp.exists())
        assertFalse(refCache.exists())
        assertFalse(dexCache.exists())
        assertFalse(evidenceFile.exists())

        // Verify essential user data is completely preserved
        assertTrue(mapFile.exists())
        assertEquals("critical-map-content", mapFile.readText())
        assertTrue(subFile.exists())
        assertEquals("{\"subscriptions\":[]}", subFile.readText())
        assertTrue(autoMapRef.exists())
        assertEquals("template", autoMapRef.readText())

        // Recalculating cache now should be 0
        val postBreakdown = cleaner.calculateCache()
        assertEquals(0L, postBreakdown.totalBytes)
    }

    @Test
    fun formatBytesFormatsProperly() {
        assertEquals("0 B", CacheCleaner.formatBytes(0L))
        assertEquals("0 B", CacheCleaner.formatBytes(-10L))
        assertEquals("512 B", CacheCleaner.formatBytes(512L))
        assertEquals("1.00 KB", CacheCleaner.formatBytes(1024L))
        assertEquals("1.50 KB", CacheCleaner.formatBytes(1536L))
        assertEquals("2.00 MB", CacheCleaner.formatBytes(2 * 1024 * 1024L))
        assertEquals("3.50 GB", CacheCleaner.formatBytes((3.5 * 1024 * 1024 * 1024L).toLong()))
    }
}
