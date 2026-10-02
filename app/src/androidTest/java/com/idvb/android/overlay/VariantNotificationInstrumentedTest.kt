package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.UsageConsent
import com.idvb.android.idvm.ClassRecord
import com.idvb.android.idvm.FloorRecord
import com.idvb.android.idvm.MapCatalogDocument
import com.idvb.android.idvm.MapRecord
import com.idvb.android.idvm.MapVariantGroupRecord
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.RecognitionCandidate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VariantNotificationInstrumentedTest {
    @Test
    fun selectingVariantCandidateAndSwitchingVariantsShowAmberWarningNotifications() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val consent = base.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val previousConsent = consent.all["accepted_revision"] as? Int
        val previousCatalog = AppServices.repository.loadCatalog()
        val previousMapId = AppServices.prefs.lastMapId
        val previousFloorKey = AppServices.prefs.lastFloorKey

        try {
            assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
            val floor1 = FloorRecord("1f", "一层", 1, "m1/floor.png", 100, 100)
            val floor2 = FloorRecord("1f", "一层", 1, "m2/floor.png", 100, 100)
            val floorSingle = FloorRecord("1f", "一层", 1, "m3/floor.png", 100, 100)
            val mapVar1 = MapRecord("var-1", "class-1", "军工厂 · 变体A", "var-1", 1, listOf(floor1))
            val mapVar2 = MapRecord("var-2", "class-1", "军工厂 · 变体B", "var-2", 1, listOf(floor2))
            val mapSingle = MapRecord("single-1", "class-1", "红教堂", "single-1", 1, listOf(floorSingle))
            val variantGroup = MapVariantGroupRecord("group-1", "class-1", 0, listOf("var-1", "var-2"))
            val testCatalog = MapCatalogDocument(
                classes = listOf(ClassRecord("class-1", "常规匹配")),
                maps = listOf(mapVar1, mapVar2, mapSingle),
                variantGroups = listOf(variantGroup)
            )
            AppServices.repository.saveCatalog(testCatalog)

            instrumentation.runOnMainSync {
                val display = base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(0))
                val context = if (Build.VERSION.SDK_INT >= 30)
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else display

                val service = OverlayService()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, base)
                fun field(name: String) = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }
                fun set(name: String, value: Any?) = field(name).set(service, value)

                set("overlayContext", context)
                for (name in listOf("window", "guideWindow", "scanProgressWindow", "candidateWindow")) {
                    set(name, OverlayWindowManager(context))
                }
                val balls = OverlayBallView(context)
                set("balls", balls)
                val notifications = OverlayNotifications(context) { 1280 to 720 }
                set("notifications", notifications)

                val lockSelectedMap = OverlayService::class.java.getDeclaredMethod(
                    "lockSelectedMap",
                    RecognitionCandidate::class.java,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }

                val nextVariant = OverlayService::class.java.getDeclaredMethod("nextVariant")
                    .apply { isAccessible = true }

                // 1. 普通地图被候选界面选中：应为默认灰色通知
                val candidateSingle = RecognitionCandidate(mapSingle, "1f", CandidateDisposition.RELIABLE, evidenceLabel = "测试证据")
                lockSelectedMap.invoke(service, candidateSingle, true)
                assertEquals(listOf("结构已确认并锁定：红教堂"), notifications.messages())
                assertEquals(listOf(NotificationTone.DEFAULT), notifications.tones())
                assertFalse(balls.variantsAvailable)

                // 2. 属于任意变体组合的地图被候选界面选中：应弹出橙黄色状态通知
                val candidateVar1 = RecognitionCandidate(mapVar1, "1f", CandidateDisposition.NEEDS_VERIFICATION, evidenceLabel = "测试证据")
                lockSelectedMap.invoke(service, candidateVar1, true)
                assertEquals("你选择了一张变体地图，如果贴合异常请尝试切换变体（⇆）", notifications.messages().first())
                assertEquals(NotificationTone.WARNING, notifications.tones().first())
                assertTrue(balls.variantsAvailable)

                // 3. 点击切换变体按钮（第 1 次切换：至 2/2）
                nextVariant.invoke(service)
                assertEquals("已切换至变体地图 2/2", notifications.messages().first())
                assertEquals(NotificationTone.WARNING, notifications.tones().first())
                assertEquals("var-2", AppServices.prefs.lastMapId)

                // 4. 再次点击切换变体按钮（第 2 次循环切换：至 1/2）
                nextVariant.invoke(service)
                assertEquals("已切换至变体地图 1/2", notifications.messages().first())
                assertEquals(NotificationTone.WARNING, notifications.tones().first())
                assertEquals("var-1", AppServices.prefs.lastMapId)

                notifications.close()
            }
        } finally {
            AppServices.repository.saveCatalog(previousCatalog)
            AppServices.prefs.lastMapId = previousMapId
            AppServices.prefs.lastFloorKey = previousFloorKey
            val edit = consent.edit()
            if (previousConsent == null) edit.remove("accepted_revision") else edit.putInt("accepted_revision", previousConsent)
            assertTrue(edit.commit())
        }
    }
}
