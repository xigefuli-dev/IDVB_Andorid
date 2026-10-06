package com.idvb.android.tutorial

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.MainActivity
import com.idvb.android.UsageConsent
import com.idvb.android.overlay.OverlayService
import androidx.lifecycle.Lifecycle
import com.idvb.android.ui.theme.IDVBTheme
import org.junit.Assert.*
import org.junit.Test

class TutorialFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun startStepPracticesBlockedButtonWithoutOpeningOverlay() {
        assertTrue(context.packageName.endsWith(".verification"))
        val consent = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val oldConsent = consent.getInt("accepted_revision", 0)
        val store = TutorialStore.get(context)
        val previous = store.state.value
        val tutorialPrefs = context.getSharedPreferences("beginner_tutorial_v1", Context.MODE_PRIVATE)
        val cooldown = tutorialPrefs.getLong("cooldown_until", 0)
        assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
        assertTrue(tutorialPrefs.edit().remove("cooldown_until").commit())
        store.update { TutorialProgress(step = TutorialStep.START, serviceStarted = true) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { IDVBTheme { TutorialPanel(store, permissionsReady = true) } }
                }
                waitUntil { label("检查") != null }
                click("检查")
                waitUntil { label(TutorialStep.START.hint) != null }
                assertEquals(TutorialStep.START, store.state.value.step)
                instrumentation.runOnMainSync {
                    OverlayService.start(context)
                    // The previous tutorial exception must also reject a direct service intent.
                    context.startForegroundService(android.content.Intent(context, OverlayService::class.java)
                        .putExtra("tutorial_start", true))
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                assertFalse(controlOverlayVisible())
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                instrumentation.runOnMainSync { store.update { it.copy(startButtonPracticed = true) } }
                click("检查")
                waitUntil { store.state.value.step == TutorialStep.LOBBY }
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
            }
        } finally {
            instrumentation.runOnMainSync { OverlayService.stop(context) }
            waitUntil { !controlOverlayVisible() }
            store.update { previous }
            tutorialPrefs.edit().putLong("cooldown_until", cooldown).commit()
            consent.edit().putInt("accepted_revision", oldConsent).commit()
        }
    }

    private fun controlOverlayVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync { visible = OverlayService.isControlOverlayVisible() }
        return visible
    }

    @Test fun failedCheckRevealsHintIncludingRepeatedFailureAfterScrollingBack() {
        val consent = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
        val oldConsent = consent.getInt("accepted_revision", 0)
        val store = TutorialStore.get(context)
        val previous = store.state.value
        val tutorialPrefs = context.getSharedPreferences("beginner_tutorial_v1", Context.MODE_PRIVATE)
        val cooldown = tutorialPrefs.getLong("cooldown_until", 0)
        // Run in the separate verification application, never reset the user's installation.
        assertTrue(context.packageName.endsWith(".verification"))
        assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
        assertTrue(tutorialPrefs.edit().remove("cooldown_until").commit())
        store.update { TutorialProgress(step = TutorialStep.IMPORT, active = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        IDVBTheme {
                            TutorialPanel(store, modifier = Modifier.width(320.dp).height(230.dp))
                        }
                    }
                }
                waitUntil { label("检查") != null }
                repeat(2) {
                    click("检查")
                    waitUntil { label(TutorialStep.IMPORT.hint)?.let(::fullyInsideScroll) == true }
                    if (it == 0) {
                        // One accessibility scroll moves only one viewport, not to the top.
                        repeat(8) {
                            find(root()) { it.isScrollable }
                                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                            SystemClock.sleep(200)
                        }
                        waitUntil { label(TutorialStep.IMPORT.title)?.let(::fullyInsideScroll) == true }
                        assertFalse(label(TutorialStep.IMPORT.hint)?.let(::fullyInsideScroll) == true)
                    }
                }
            }
        } finally {
            store.update { previous }
            tutorialPrefs.edit().putLong("cooldown_until", cooldown).commit()
            consent.edit().putInt("accepted_revision", oldConsent).commit()
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        automation.clearCache()
        return automation.rootInActiveWindow
    }
    private fun label(text: String) = find(root()) { it.isVisibleToUser && it.text?.toString() == text }
    private fun fullyInsideScroll(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) return Rect().also(parent::getBoundsInScreen).contains(bounds) && !bounds.isEmpty
            parent = parent.parent
        }
        return false
    }
    private fun click(text: String) {
        var node = checkNotNull(label(text))
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            if (check()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for tutorial feedback scrolling")
    }
}
