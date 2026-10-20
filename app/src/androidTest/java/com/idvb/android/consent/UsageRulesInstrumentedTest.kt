package com.idvb.android.consent

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.MainActivity
import com.idvb.android.R
import com.idvb.android.StartupRequirements
import com.idvb.android.UsageConsent
import com.idvb.android.UsageRules
import com.idvb.android.feedback.FeedbackService
import com.idvb.android.keepalive.BootReceiver
import com.idvb.android.overlay.OverlayService
import com.idvb.android.tutorial.TutorialStore
import com.idvb.android.tutorial.TutorialProgress
import com.idvb.android.tutorial.TutorialQuiz
import com.idvb.android.tutorial.TutorialStep
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsageRulesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val consent get() = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
    private val rules get() = context.getSharedPreferences(UsageRules.PREFS, Context.MODE_PRIVATE)
    private var oldConsent: Int? = null
    private var oldRules: Int? = null
    private lateinit var oldTutorial: TutorialProgress
    private fun confirmButton() = onView(withId(android.R.id.button1)).inRoot(isDialog())
    private fun input() = onView(withId(R.id.usage_rules_confirmation)).inRoot(isDialog())

    @Before fun setUp() {
        oldConsent = consent.all["accepted_revision"] as? Int
        oldRules = rules.all[UsageRules.KEY] as? Int
        oldTutorial = TutorialStore.get(context).state.value
        assertTrue(consent.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
        assertTrue(rules.edit().remove(UsageRules.KEY).commit())
    }

    @After fun restore() {
        val first = consent.edit()
        oldConsent?.let { first.putInt("accepted_revision", it) } ?: first.remove("accepted_revision")
        assertTrue(first.commit())
        val second = rules.edit()
        oldRules?.let { second.putInt(UsageRules.KEY, it) } ?: second.remove(UsageRules.KEY)
        assertTrue(second.commit())
        TutorialStore.get(context).update { oldTutorial }
    }

    @Test fun legacyUserStillNeedsRulesAndOnlyExactTextEnablesConfirmation() {
        completeTutorial()
        assertTrue(UsageConsent.isAccepted(context))
        assertFalse(StartupRequirements.isAccepted(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageRules.TITLE)).check(matches(isDisplayed()))
            onView(withText(UsageConsent.TITLE)).check(doesNotExist())
            confirmButton().check(matches(not(isEnabled())))
            for (text in listOf("我已了解", "“${UsageRules.CONFIRMATION}”", " ${UsageRules.CONFIRMATION}", "${UsageRules.CONFIRMATION} ")) {
                input().perform(scrollTo(), replaceText(text), closeSoftKeyboard())
                confirmButton().check(matches(not(isEnabled())))
                assertFalse(UsageRules.isAccepted(context))
            }
            input().perform(scrollTo(), replaceText(UsageRules.CONFIRMATION), closeSoftKeyboard())
            confirmButton().check(matches(isEnabled()))
            input().perform(scrollTo(), replaceText(""), closeSoftKeyboard())
            confirmButton().check(matches(not(isEnabled())))
            input().perform(scrollTo(), replaceText(UsageRules.CONFIRMATION), closeSoftKeyboard())
            assertFalse(UsageRules.isAccepted(context))
            confirmButton().perform(click())
            assertTrue(UsageRules.isAccepted(context))
            assertFalse(StartupRequirements.isAccepted(context)) // Explicit account login is a separate gate.
            onView(withText(UsageRules.TITLE)).check(doesNotExist())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageRules.TITLE)).check(doesNotExist())
            assertTrue(UsageRules.isAccepted(context))
            assertFalse(StartupRequirements.isAccepted(context))
        }
    }

    @Test fun filledTextIsNotConsentOnExitOrRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            input().perform(scrollTo(), replaceText(UsageRules.CONFIRMATION), closeSoftKeyboard())
            scenario.recreate()
            onView(withText(UsageRules.TITLE)).inRoot(isDialog()).check(matches(isDisplayed()))
            input().check(matches(withText("")))
            confirmButton().check(matches(not(isEnabled())))
            assertFalse(UsageRules.isAccepted(context))
            input().perform(scrollTo(), replaceText(UsageRules.CONFIRMATION), closeSoftKeyboard())
            onView(withText("退出")).inRoot(isDialog()).perform(click())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageRules.TITLE)).check(matches(isDisplayed()))
            confirmButton().check(matches(not(isEnabled())))
            assertFalse(UsageRules.isAccepted(context))
        }
    }

    @Test fun responsibilityStatementAlwaysComesFirstEvenWithRulesRecord() {
        assertTrue(rules.edit().putInt(UsageRules.KEY, UsageRules.REVISION).commit())
        assertTrue(consent.edit().remove("accepted_revision").commit())
        assertFalse(StartupRequirements.isAccepted(context))
        assertFalse(UsageRules.accept(context, UsageRules.CONFIRMATION))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageConsent.TITLE)).check(matches(isDisplayed()))
            onView(withText(UsageRules.TITLE)).check(doesNotExist())
        }
    }

    @Test fun outdatedRulesAndWrongTextDoNotAuthorizeUse() {
        assertTrue(rules.edit().putInt(UsageRules.KEY, UsageRules.REVISION - 1).commit())
        assertFalse(UsageRules.accept(context, "${UsageRules.CONFIRMATION}。"))
        assertFalse(StartupRequirements.isAccepted(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageRules.TITLE)).check(matches(isDisplayed()))
        }
    }

    @Test fun importRemainsPendingUntilRulesAreConfirmed() {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("content://com.idvb.test/pending.idvm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            onView(withText(UsageRules.TITLE)).check(matches(isDisplayed()))
            scenario.onActivity { assertEquals(Intent.ACTION_VIEW, it.intent.action) }
            val second = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.idvb.test/second.idvm"))
            }
            scenario.onActivity { activity ->
                val trackedIntent = activity.intent
                try {
                    MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
                        isAccessible = true
                    }.invoke(activity, second)
                    assertEquals(Intent.ACTION_SEND, activity.intent.action)
                } finally {
                    // ActivityScenario matches lifecycle events by the original Intent.
                    // Restore its tracking identity only after checking production onNewIntent behavior.
                    activity.intent = trackedIntent
                }
            }
            assertFalse(StartupRequirements.isAccepted(context))
        }
    }

    @Test fun feedbackExportCannotBypassRules() {
        assertTrue(UsageConsent.isAccepted(context))
        val error = runCatching { FeedbackService(context).createExportPackage() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("使用规则"))
    }

    @Test fun completedTutorialDoesNotAllowOverlayOrBootRecoveryWithoutRules() {
        completeTutorial()
        val guarded = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw AssertionError("Must not start foreground service before confirming rules")
            override fun startService(service: Intent): ComponentName? =
                throw AssertionError("Must not start service before confirming rules")
        }
        assertTrue(UsageConsent.isAccepted(context))
        assertFalse(StartupRequirements.isAccepted(context))
        OverlayService.start(guarded)
        BootReceiver().onReceive(guarded, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    private fun completeTutorial() {
        TutorialStore.get(context).update {
            it.copy(step = TutorialStep.DONE, active = false, practiceOpen = false,
                passed = TutorialStep.activeSteps.toSet(),
                quizAnswered = TutorialQuiz.questions.size, completionRevision = TutorialQuiz.REVISION,
                practiceFinished = true)
        }
        assertTrue(TutorialStore.get(context).state.value.completed)
    }
}
