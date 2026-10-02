package com.idvb.android.feedback

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.idvb.android.BuildConfig
import com.idvb.android.UsageConsent
import com.idvb.android.diagnostics.HistoryArchive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only explicitly selected attachments leave private app storage. No account or device ID. */
class FeedbackService(private val context: Context) {
    fun submit(description: String, includeLogs: Boolean = true, includeDiagnostics: Boolean = true): String {
        check(UsageConsent.isAccepted(context)) { "请先确认软件性质及使用责任声明。" }
        require(FeedbackText.isValid(description)) { "问题描述需超过 10 个加权字符，且不超过 4000 字符。" }
        val directory = File(context.cacheDir, "feedback-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建反馈临时目录。" }
        try {
            val logs = if (includeLogs) collectLogs(directory) else null
            val diagnostics = if (includeDiagnostics) {
                File(directory, "diagnostics.zip").also { archive ->
                    HistoryArchive.write(File(context.filesDir, "idvb/diagnostics"), archive, true)
                }
            } else null
            return upload(description.trim(), logs, diagnostics)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * 将日志与诊断数据合并打包导出。
     * “两个和在一起”：将 logs.zip（应用与系统运行日志）和 diagnostics.zip（识别与对齐诊断数据）
     * 以及用户填写的问题描述统一打包至单个 ZIP，供用户直接分享给其他应用。
     */
    fun createExportPackage(description: String = ""): File {
        check(UsageConsent.isAccepted(context)) { "请先确认软件性质及使用责任声明。" }
        val exportDir = File(context.cacheDir, "feedback-export")
        if (exportDir.exists()) {
            exportDir.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            check(exportDir.mkdirs()) { "无法创建反馈导出目录。" }
        }
        val directory = File(context.cacheDir, "feedback-export-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建反馈临时目录。" }
        try {
            val logs = collectLogs(directory)
            val diagnostics = File(directory, "diagnostics.zip").also { archive ->
                HistoryArchive.write(File(context.filesDir, "idvb/diagnostics"), archive, true)
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val exportFile = File(exportDir, "idvb-feedback-$timestamp.zip")
            ZipOutputStream(exportFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("logs.zip"))
                logs.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("diagnostics.zip"))
                diagnostics.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                val desc = description.trim()
                if (desc.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry("description.txt"))
                    zip.write(desc.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                zip.putNextEntry(ZipEntry("export-info.txt"))
                val clientInfo = buildString {
                    appendLine("IDVB Android 问题反馈合并导出包")
                    appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_VERSION})")
                    appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("导出时间: $timestamp")
                    appendLine()
                    appendLine("包含内容:")
                    appendLine("1. logs.zip: 应用与系统运行日志（含最近场次日志及当前进程日志）")
                    appendLine("2. diagnostics.zip: 识别与对齐诊断数据（含最近 20 场识别与对齐截图与记录）")
                    if (desc.isNotEmpty()) {
                        appendLine("3. description.txt: 用户填写的反馈描述")
                    }
                }
                zip.write(clientInfo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return exportFile
        } finally {
            directory.deleteRecursively()
        }
    }

    fun launchShareChooser(activityContext: Context, packageFile: File) {
        val uri = FileProvider.getUriForFile(
            activityContext,
            "${activityContext.packageName}.diagnostics.files",
            packageFile,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IDVB 反馈合并导出包")
            clipData = ClipData.newRawUri("IDVB feedback package", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享反馈导出包").apply {
            if (activityContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching {
            activityContext.startActivity(chooser)
        }.onFailure { e ->
            throw IllegalStateException("无法启动系统分享：${e.message ?: "未找到支持分享的应用"}", e)
        }
    }

    fun shareFeedbackPackage(activityContext: Context, description: String = ""): File {
        val file = createExportPackage(description)
        launchShareChooser(activityContext, file)
        return file
    }

    private fun collectLogs(directory: File): File {
        val log = File(directory, "app.log")
        val capture = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "1000", "--pid=${Process.myPid()}")
                .redirectOutput(log).redirectError(File(directory, "logcat-error.txt")).start()
            try {
                check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    "当前进程日志读取失败或超时；已保存的场次日志仍包含在附件中。"
                }
            } finally {
                process.destroy()
            }
        }
        val history = File(directory, "session-logs.zip")
        HistoryArchive.write(File(context.filesDir, "idvb/diagnostics"), history, false)
        return File(directory, "logs.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("session-logs.zip"))
                history.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("app.log"))
                if (log.isFile) log.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("logcat-status.txt"))
                zip.write((capture.exceptionOrNull()?.message ?: "Current process log snapshot completed.").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("client.txt"))
                val clientInfo = buildString {
                    appendLine("IDVB Android ${BuildConfig.VERSION_NAME}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
                    append(DisplayDiagnostics.describe(context))
                    append("session-logs.zip 包含最近最多 20 场的已保存应用日志；app.log 补充当前进程最近最多 1000 条记录。")
                }
                zip.write(clientInfo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun upload(description: String, logs: File?, diagnostics: File?): String {
        val boundary = "idvb-${UUID.randomUUID()}"
        val connection = URL("https://community.idvb.xgflee.com/api/android/feedback").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.buffered().use { output ->
                fun write(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                fun attachment(name: String, file: File?) {
                    if (file == null) return
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name.zip\"\r\nContent-Type: application/zip\r\n\r\n")
                    file.inputStream().use { it.copyTo(output) }
                    write("\r\n")
                }
                field("description", description)
                field("clientVersion", "Android ${BuildConfig.VERSION_NAME}")
                attachment("logs", logs)
                attachment("diagnostics", diagnostics)
                write("--$boundary--\r\n")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8192)
                var total = 0
                while (total < buffer.size) {
                    val count = reader.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
                String(buffer, 0, total)
            }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status in 200..299 && json?.optBoolean("success") == true && !json.optString("feedbackId").isNullOrBlank()) {
                return "反馈已成功送达官方团队，感谢您的支持与协助！"
            }
            error(when (status) {
                413 -> "服务器拒绝了附件大小；本地记录仍保留，请导出诊断包或联系官方调整服务端限制。"
                429 -> "反馈提交过于频繁，请稍候再试。"
                404 -> "反馈服务尚未开通，请稍后重试。"
                else -> json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "提交未确认成功（HTTP $status），请稍后重试。"
            })
        } finally {
            connection.disconnect()
        }
    }
}
