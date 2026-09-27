package com.idvb.android.ui.screens.maplist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.idvb.android.AppServices
import com.idvb.android.idvm.IdvmImporter
import com.idvb.android.idvm.ImportResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipException

/** 地图列表的系统“用其他应用打开”与“分享”导入入口。 */
fun importIdvmUri(context: Context, uri: Uri): Pair<ImportResult, String> {
    val repo = AppServices.repository
    val rawName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "unknown.idvm"
    val name = runCatching { Uri.decode(rawName) }.getOrDefault(rawName)
    val tmpDir = File(context.cacheDir, "idvb/import").apply { mkdirs() }
    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
    val tmpFile = File(tmpDir, "${System.currentTimeMillis()}-$safeName")
    return try {
        val input = openUriInputStream(context, uri) ?: return ImportResult.Failure("无法读取所选文件") to name
        input.use { inStream ->
            tmpFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
        }
        val result = IdvmImporter().importPackage(
            packageFile = tmpFile,
            mapsRoot = repo.mapsRoot,
            currentCatalog = repo.loadCatalog(),
            catalogSaver = repo::saveCatalog,
        )
        result to name
    } catch (e: ZipException) {
        ImportResult.Failure("所选文件不是有效的 IDVM 地图包（非 ZIP 格式）", e.message) to name
    } catch (e: Exception) {
        ImportResult.Failure("导入异常：${e.message}", e.stackTraceToString().take(1200)) to name
    } finally {
        tmpFile.delete()
    }
}

/** 批量导入多个 Uri（多选文件或批量分享入口） */
fun importIdvmUris(context: Context, uris: List<Uri>): Pair<ImportResult, String> {
    if (uris.isEmpty()) return ImportResult.Failure("未选择任何文件") to ""
    if (uris.size == 1) return importIdvmUri(context, uris[0])

    val repo = AppServices.repository
    val tmpDir = File(context.cacheDir, "idvb/import").apply { mkdirs() }
    val tempFiles = mutableListOf<File>()
    val names = mutableListOf<String>()

    return try {
        for (uri in uris) {
            val rawName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "unknown.idvm"
            val name = runCatching { Uri.decode(rawName) }.getOrDefault(rawName)
            names += name
            val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            val tmpFile = File(tmpDir, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}-$safeName")
            val input = openUriInputStream(context, uri) ?: continue
            input.use { inStream ->
                tmpFile.outputStream().use { outStream -> inStream.copyTo(outStream) }
            }
            tempFiles += tmpFile
        }

        if (tempFiles.isEmpty()) {
            return ImportResult.Failure("无法读取所选文件") to names.joinToString(", ")
        }

        val result = IdvmImporter().importPackages(
            packageFiles = tempFiles,
            mapsRoot = repo.mapsRoot,
            currentCatalog = repo.loadCatalog(),
            catalogSaver = repo::saveCatalog,
        )
        val displayName = if (names.size <= 3) names.joinToString(", ") else "${names.first()} 等 ${names.size} 个文件"
        result to displayName
    } catch (e: Exception) {
        ImportResult.Failure("批量导入异常：${e.message}", e.stackTraceToString().take(1200)) to names.joinToString(", ")
    } finally {
        tempFiles.forEach { it.delete() }
    }
}

private fun openUriInputStream(context: Context, uri: Uri): InputStream? {
    return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: if (uri.scheme == "file") {
            uri.path?.let { path ->
                val f = File(path)
                if (f.exists() && f.canRead()) runCatching { FileInputStream(f) }.getOrNull() else null
            }
        } else null
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    if (uri.scheme == "file") return@runCatching uri.lastPathSegment
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
