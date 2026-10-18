package com.idvb.android.consent

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.UsageRules
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/** Existing feature tests model a user who already confirmed rules; preserve their record. */
class AcceptedUsageRulesFixture : ExternalResource() {
    private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(UsageRules.PREFS, Context.MODE_PRIVATE)
    private var previous: Int? = null

    override fun before() {
        previous = prefs.all[UsageRules.KEY] as? Int
        assertTrue(prefs.edit().putInt(UsageRules.KEY, UsageRules.REVISION).commit())
    }

    override fun after() {
        val edit = prefs.edit()
        previous?.let { edit.putInt(UsageRules.KEY, it) } ?: edit.remove(UsageRules.KEY)
        assertTrue(edit.commit())
    }
}
