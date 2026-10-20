package com.idvb.android

import android.content.Context
import androidx.activity.ComponentActivity

/** Confirmations precede account verification; none can substitute for another. */
object StartupRequirements {
    fun isAccepted(context: Context): Boolean =
        UsageConsent.isAccepted(context) && UsageRules.isAccepted(context) && AppServices.accounts.isAuthorized()

    /** The responsibility statement always precedes rules, tutorials and feature guides. */
    fun showIfRequired(activity: ComponentActivity): Boolean {
        when {
            !UsageConsent.isAccepted(activity) -> UsageConsent.show(activity)
            !UsageRules.isAccepted(activity) -> UsageRules.show(activity)
            !AppServices.accounts.isAuthorized() -> AppServices.accounts.show(activity)
            else -> return false
        }
        return true
    }
}
