package com.idvb.android.alignment

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.UsageConsent
import com.idvb.android.data.EyeButtonAction
import com.idvb.android.tutorial.TutorialStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OperationSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun settingsDefaultAndChoiceSurviveRecreationAndReopening() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
        val consent = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val oldAction = prefs.getString("eye_button_action", null)
        val oldConsent = if (consent.contains("accepted_revision")) consent.getInt("accepted_revision", 0) else null
        val tutorial = TutorialStore.get(context)
        val oldTutorial = tutorial.state.value
        try {
            consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit()
            prefs.edit().remove("eye_button_action").commit()
            tutorial.update { it.copy(active = false, practiceOpen = false) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                SystemClock.sleep(450)
                click("设置"); click("操作")
                waitLabel("按下“🔍”时的操作"); waitLabel("扫描地图")
                waitLabel("按下“👁”时的操作")
                assertEquals(EyeButtonAction.SHOW_AND_ALIGN, AppServices.prefs.eyeButtonAction)
                assertTrue(choice("展示并自动贴合").isChecked)
                click("只展示")
                assertEquals(EyeButtonAction.SHOW_ONLY, AppServices.prefs.eyeButtonAction)
                assertTrue(choice("只展示").isChecked)
                scenario.recreate()
                // MainActivity intentionally starts on the home page after recreation.
                click("设置"); click("操作")
                assertTrue(choice("只展示").isChecked)
                click("返回"); click("操作")
                assertTrue(choice("只展示").isChecked)
                click("展示并自动贴合")
                assertEquals(EyeButtonAction.SHOW_AND_ALIGN, AppServices.prefs.eyeButtonAction)
                automation.takeScreenshot()?.let { bitmap ->
                    try {
                        val evidence = File(context.filesDir, "test-evidence/alignment-operation-settings.png")
                        check(evidence.parentFile!!.mkdirs() || evidence.parentFile!!.isDirectory)
                        evidence.outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            prefs.edit().apply { if (oldAction == null) remove("eye_button_action") else putString("eye_button_action", oldAction) }.commit()
            consent.edit().apply { if (oldConsent == null) remove("accepted_revision") else putInt("accepted_revision", oldConsent) }.commit()
            tutorial.update { oldTutorial }
        }
    }

    private fun choice(label: String): AccessibilityNodeInfo {
        var node = waitLabel(label)
        while (!node.isCheckable && node.parent != null) node = node.parent
        assertTrue("Expected radio choice: $label", node.isCheckable)
        return node
    }

    private fun click(label: String) {
        var node = scrollToLabel(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        val bounds = Rect().also(node::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(450)
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        else automation.serviceInfo = automation.serviceInfo
        return automation.rootInActiveWindow
    }

    private fun scrollToLabel(label: String): AccessibilityNodeInfo {
        repeat(10) {
            find(freshRoot(), label)?.let { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                var parent = node.parent
                var inside = !bounds.isEmpty
                while (parent != null) {
                    if (parent.isScrollable && !Rect().also(parent::getBoundsInScreen).contains(bounds)) inside = false
                    parent = parent.parent
                }
                if (inside) return node
            }
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                node ?: return false
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            scroll(freshRoot())
            instrumentation.waitForIdleSync()
            SystemClock.sleep(450)
        }
        return waitLabel(label)
    }

    private fun waitLabel(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            find(freshRoot(), label)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing UI label: $label")
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true ||
                node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
}
