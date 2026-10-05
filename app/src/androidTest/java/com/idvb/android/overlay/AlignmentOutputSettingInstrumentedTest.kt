package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.UsageConsent
import com.idvb.android.data.OverlayPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlignmentOutputSettingInstrumentedTest {

    @Test
    fun showAlignmentOutputDefaultsToTrueAndPersistsUserChoices() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "alignment-output-setting-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(name, mode)
        }
        try {
            val prefs = OverlayPrefs(isolated)
            assertTrue("Default must be true", prefs.showAlignmentOutput)
            prefs.showAlignmentOutput = true
            assertTrue(OverlayPrefs(isolated).showAlignmentOutput)
            prefs.showAlignmentOutput = false
            assertFalse(OverlayPrefs(isolated).showAlignmentOutput)
            assertTrue(prefs.completeFeatureGuide("scan-preset-v1", "automatic"))
            assertTrue(OverlayPrefs(isolated).showAlignmentOutput)
            prefs.showAlignmentOutput = false
            assertTrue(prefs.completeFeatureGuide("scan-preset-v1", "traditional"))
            assertFalse(OverlayPrefs(isolated).showAlignmentOutput)
            assertTrue(prefs.completeFeatureGuide("scan-preset-v1", "automatic"))
            assertTrue(OverlayPrefs(isolated).showAlignmentOutput)
        } finally {
            target.deleteSharedPreferences(name)
        }
    }

    @Test
    fun alignmentNotificationsOnlyShowWhenEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val consent = target.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val previousConsent = consent.all["accepted_revision"] as? Int
        val previousSetting = AppServices.prefs.showAlignmentOutput
        var service: OverlayService? = null
        try {
            assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
            instrumentation.runOnMainSync {
                val display = target.createDisplayContext(
                    target.getSystemService(DisplayManager::class.java).getDisplay(0),
                )
                val context = if (Build.VERSION.SDK_INT >= 30) {
                    display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else display
                val s = OverlayService().also { service = it }
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                    isAccessible = true
                }.invoke(s, context)
                val field = { name: String -> OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true } }
                field("overlayContext").set(s, context)
                val notices = OverlayNotifications(context) { 1280 to 720 }
                field("notifications").set(s, notices)

                val showMethod = OverlayService::class.java.getDeclaredMethod("showAlignmentNotice", String::class.java, Long::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
                val updateMethod = OverlayService::class.java.getDeclaredMethod("updateAlignmentNotice", Long::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType).apply {
                    isAccessible = true
                }

                // 1. When disabled: showing alignment notice returns -1 and no message appears
                AppServices.prefs.showAlignmentOutput = false
                val idDisabled = showMethod.invoke(s, "正在准备自动贴合…", 0L) as Long
                assertEquals(-1L, idDisabled)
                updateMethod.invoke(s, idDisabled, "正在自动贴合 · 结构校验", 0L)
                assertEquals(emptyList<String>(), notices.messages())

                // 2. When enabled: showing alignment notice creates a card and update modifies it
                AppServices.prefs.showAlignmentOutput = true
                val idEnabled = showMethod.invoke(s, "正在准备自动贴合…", 0L) as Long
                assertTrue(idEnabled > 0L)
                assertEquals(listOf("正在准备自动贴合…"), notices.messages())
                updateMethod.invoke(s, idEnabled, "自动贴合成功 · 120ms", 3_500L)
                assertEquals(listOf("自动贴合成功 · 120ms"), notices.messages())

                notices.close()
            }
        } finally {
            AppServices.prefs.showAlignmentOutput = previousSetting
            val edit = consent.edit()
            if (previousConsent == null) edit.remove("accepted_revision") else edit.putInt("accepted_revision", previousConsent)
            assertTrue(edit.commit())
        }
    }
}
