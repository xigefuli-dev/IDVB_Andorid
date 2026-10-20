package com.idvb.android.account

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.idvb.android.AppServices
import com.idvb.android.UsageConsent
import com.idvb.android.UsageRules
import com.idvb.android.overlay.OverlayService
import com.idvb.android.ui.theme.IDVBTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Process-wide account owner, also active while the overlay is running in the background. */
class AccountAccess(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val credentials = AccountCredentials(appContext)
    private val session = AccountSession(AccountHttpApi(credentials), credentials, SystemClock::elapsedRealtime) {
        // Run the normal service teardown: it cancels actual recognition/alignment and releases capture.
        OverlayService.stop(appContext)
        AppServices.pauseRecognitionPreparation()
    }
    val state = session.state
    private var started = false
    private var refreshing = false
    fun isAuthorized() = session.isAuthorized()
    fun requireToken() = session.currentToken()
    fun reject(token: String) { scope.launch { session.reject(token) } }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                if (UsageConsent.isAccepted(appContext) && UsageRules.isAccepted(appContext) && session.hasSession()) refresh()
                delay(30_000)
            }
        }
        scope.launch {
            while (true) { session.expireIfNeeded(); delay(1_000) }
        }
    }

    fun refresh() {
        if (refreshing || !UsageConsent.isAccepted(appContext) || !UsageRules.isAccepted(appContext)) return
        refreshing = true
        scope.launch {
            try { session.refresh() }
            finally { refreshing = false }
        }
    }

    internal suspend fun authenticate(operation: String, fields: Map<String, String>): String =
        session.authenticate(operation, fields)

    fun logout() { scope.launch { session.logout() } }

    fun show(activity: ComponentActivity) {
        start()
        activity.setContent { IDVBTheme { AccountScreen(this, onExit = { activity.finishAndRemoveTask() }) } }
    }
}
