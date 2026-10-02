package com.idvb.android.feedback

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.UsageConsent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class FeedbackExportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun createExportPackageBundlesLogsAndDiagnosticsAndIntegratesWithFileProvider() {
        val consent = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val oldConsent = consent.getInt("accepted_revision", 0)
        consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit()
        try {
            val service = FeedbackService(context)
            val testDescription = "这是一条用于测试合并导出包分享的问题描述信息。"
            val exportFile = service.createExportPackage(testDescription)

            assertTrue("导出文件应当存在", exportFile.isFile)
            assertTrue("导出文件应当以 .zip 结尾", exportFile.name.endsWith(".zip"))
            assertEquals("导出文件应当位于 cacheDir/feedback-export",
                File(context.cacheDir, "feedback-export").canonicalPath,
                exportFile.parentFile?.canonicalPath)

            // 检查导出的 ZIP 文件内容
            val entryNames = mutableSetOf<String>()
            ZipFile(exportFile).use { zip ->
                zip.entries().asSequence().forEach { entryNames.add(it.name) }
                assertTrue("导出包应当包含 logs.zip", entryNames.contains("logs.zip"))
                assertTrue("导出包应当包含 diagnostics.zip", entryNames.contains("diagnostics.zip"))
                assertTrue("导出包应当包含 description.txt", entryNames.contains("description.txt"))
                assertTrue("导出包应当包含 export-info.txt", entryNames.contains("export-info.txt"))

                val descEntry = zip.getEntry("description.txt")
                assertNotNull("应当能读取 description.txt", descEntry)
                val readDescription = zip.getInputStream(descEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertEquals(testDescription, readDescription)

                val infoEntry = zip.getEntry("export-info.txt")
                assertNotNull("应当能读取 export-info.txt", infoEntry)
                val readInfo = zip.getInputStream(infoEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertTrue("export-info 应当包含关键说明", readInfo.contains("IDVB Android 问题反馈合并导出包"))
                assertTrue("export-info 应当包含 logs.zip 描述", readInfo.contains("logs.zip"))
                assertTrue("export-info 应当包含 diagnostics.zip 描述", readInfo.contains("diagnostics.zip"))
            }

            // 验证 FileProvider URI 获取正常（配置正确）
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.diagnostics.files",
                exportFile,
            )
            assertNotNull("FileProvider 应能为导出包生成有效 Content URI", uri)
            assertEquals("content", uri.scheme)
            assertTrue("URI 应包含 feedback_export 路径标识", uri.toString().contains("feedback_export"))

            // 再次调用导出时，应清理旧导出文件并生成新导出文件
            val secondFile = service.createExportPackage("")
            assertTrue("第二次导出文件应存在", secondFile.isFile)
            val exportDir = File(context.cacheDir, "feedback-export")
            val filesInDir = exportDir.listFiles() ?: emptyArray()
            assertEquals("导出目录应当只保留最新一次的导出文件", 1, filesInDir.size)
            assertEquals(secondFile.name, filesInDir[0].name)

            ZipFile(secondFile).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(entries.contains("logs.zip"))
                assertTrue(entries.contains("diagnostics.zip"))
                assertFalse("空描述时不应包含 description.txt", entries.contains("description.txt"))
            }
        } finally {
            consent.edit().putInt("accepted_revision", oldConsent).commit()
            File(context.cacheDir, "feedback-export").deleteRecursively()
        }
    }

    @Test
    fun createExportPackageRequiresUsageConsent() {
        val consent = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val oldConsent = consent.getInt("accepted_revision", 0)
        consent.edit().putInt("accepted_revision", 0).commit()
        try {
            val service = FeedbackService(context)
            try {
                service.createExportPackage("测试")
                fail("未确认声明时不应当允许导出")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message?.contains("声明") == true)
            }
        } finally {
            consent.edit().putInt("accepted_revision", oldConsent).commit()
        }
    }
}
