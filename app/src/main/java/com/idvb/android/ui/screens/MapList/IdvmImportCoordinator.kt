package com.idvb.android.ui.screens.maplist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.idvb.android.AppServices
import com.idvb.android.idvm.IdvmImporter
import com.idvb.android.idvm.ImportResult
import java.io.File

/** 地图列表的系统“用其他应用打开”导入入口。 */
fun importIdvmUri(context: Context, uri: Uri): Pair<ImportResult, String> {
    val repo = AppServices.repository
    val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "unknown.idvm"
    val tmpDir = File(context.cacheDir, "idvb/import").apply { mkdirs() }
    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
    val tmpFile = File(tmpDir, "${System.currentTimeMillis()}-$safeName")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmpFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return ImportResult.Failure("无法读取所选文件") to name
        IdvmImporter().importPackage(
            packageFile = tmpFile,
            mapsRoot = repo.mapsRoot,
            currentCatalog = repo.loadCatalog(),
            catalogSaver = repo::saveCatalog,
        ) to name
    } catch (e: Exception) {
        ImportResult.Failure("导入异常：${e.message}", e.stackTraceToString().take(1200)) to name
    } finally {
        tmpFile.delete()
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
