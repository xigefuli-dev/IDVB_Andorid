package com.idvb.android.diagnostics

import android.content.Context
import java.io.File
import java.util.Locale

data class CacheBreakdown(
    val diagnosticBytes: Long = 0L,
    val tempCacheBytes: Long = 0L,
) {
    val totalBytes: Long get() = diagnosticBytes + tempCacheBytes

    val formattedDiagnostics: String get() = CacheCleaner.formatBytes(diagnosticBytes)
    val formattedTempCache: String get() = CacheCleaner.formatBytes(tempCacheBytes)
    val formattedTotal: String get() = CacheCleaner.formatBytes(totalBytes)
}

/**
 * 计算和清理应用产生的非必要缓存（日志、诊断与截图数据、临时文件等）。
 * 严格保留用户地图数据（idvb/maps）、订阅（subscriptions.json）与自动打开参考模板（auto-map-open）。
 */
class CacheCleaner(
    private val cacheDir: File,
    private val codeCacheDir: File?,
    private val filesDir: File,
) {
    constructor(context: Context) : this(
        cacheDir = context.cacheDir,
        codeCacheDir = context.codeCacheDir,
        filesDir = context.filesDir,
    )

    private val diagnosticsDir: File get() = File(filesDir, "idvb/diagnostics")
    private val testEvidenceDir: File get() = File(filesDir, "test-evidence")

    fun calculateCache(): CacheBreakdown {
        val diagBytes = synchronized(DiagnosticHistory.lock) {
            directorySize(diagnosticsDir) + directorySize(testEvidenceDir)
        }
        val tempBytes = directorySize(cacheDir) + directorySize(codeCacheDir)
        return CacheBreakdown(
            diagnosticBytes = diagBytes,
            tempCacheBytes = tempBytes,
        )
    }

    fun clearCache(): Long {
        var freedBytes = 0L

        // 1. 日志与诊断数据（识别与贴合记录、日志流、截图帧与中间产物）
        synchronized(DiagnosticHistory.lock) {
            freedBytes += deleteDirectoryContents(diagnosticsDir)
            if (testEvidenceDir.exists()) {
                freedBytes += directorySize(testEvidenceDir)
                testEvidenceDir.deleteRecursively()
            }
        }

        // 2. 临时缓存目录（导入临时文件、对齐参考缓存、反馈临时导出等）
        freedBytes += deleteDirectoryContents(cacheDir)

        // 3. 代码与运行时临时缓存（若存在）
        if (codeCacheDir != null && codeCacheDir.exists()) {
            freedBytes += deleteDirectoryContents(codeCacheDir)
        }

        return freedBytes
    }

    private fun deleteDirectoryContents(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var bytes = 0L
        dir.listFiles()?.forEach { file ->
            bytes += directorySize(file)
            runCatching { file.deleteRecursively() }
        }
        return bytes
    }

    private fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        try {
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
        } catch (_: Exception) {
        }
        return size
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) {
                "$bytes B"
            } else {
                String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex])
            }
        }
    }
}
