package com.idvb.android.consent

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.MainActivity
import com.idvb.android.UsageConsent
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsageConsentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
    private var previous: Int? = null

    @Before fun setUp() {
        previous = if (prefs.contains("accepted_revision")) prefs.getInt("accepted_revision", 0) else null
        assertTrue(prefs.edit().remove("accepted_revision").commit())
    }
    @After fun restore() {
        val editor = prefs.edit()
        previous?.let { editor.putInt("accepted_revision", it) } ?: editor.remove("accepted_revision")
        assertTrue(editor.commit())
    }

    @Test fun freshOrLegacyInstallMustWaitAndExplicitlyConfirm() {
        assertFalse(UsageConsent.isAccepted(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(UsageConsent.TITLE)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(not(isEnabled())))
            Thread.sleep(1500)
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(not(isEnabled())))
            scenario.recreate()
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(not(isEnabled())))
            Thread.sleep(5300)
            assertFalse(UsageConsent.isAccepted(context))
            onView(withText(UsageConsent.CONFIRM)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            assertTrue(UsageConsent.isAccepted(context))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(UsageConsent.isAccepted(context))
            onView(withText(UsageConsent.TITLE)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test fun exitWithoutConfirmingRequiresConsentOnNextLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("退出")).perform(click())
        }
        assertFalse(UsageConsent.isAccepted(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageConsent.TITLE)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(not(isEnabled())))
        }
    }

    @Test fun outdatedRevisionDoesNotAuthorizeUse() {
        assertTrue(prefs.edit().putInt("accepted_revision", UsageConsent.REVISION - 1).commit())
        assertFalse(UsageConsent.isAccepted(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(UsageConsent.TITLE)).check(matches(isDisplayed()))
        }
    }
}

