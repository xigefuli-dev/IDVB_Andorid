package com.idvb.android

import android.content.Context
import androidx.activity.ComponentActivity

/** Every functional entry point must require both independent, explicit confirmations. */
object StartupRequirements {
    fun isAccepted(context: Context): Boolean =
        UsageConsent.isAccepted(context) && UsageRules.isAccepted(context)

    /** The responsibility statement always precedes rules, tutorials and feature guides. */
    fun showIfRequired(activity: ComponentActivity): Boolean {
        when {
            !UsageConsent.isAccepted(activity) -> UsageConsent.show(activity)
            !UsageRules.isAccepted(activity) -> UsageRules.show(activity)
            else -> return false
        }
        return true
    }
}
