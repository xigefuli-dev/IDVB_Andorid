package com.idvb.android.account

/** One audited origin; credentials never follow redirects or fall back to the old host. */
object OnlineEndpoints {
    const val ORIGIN = "https://156.239.51.171"
    const val DOWNLOAD_PATH = "/downloads/maps/"
    const val TOKEN = "/api/auth/publish-token"
    const val FEEDBACK = "/api/feedback"
    val authPaths = mapOf(
        "register.start" to "/api/desktop/auth/register",
        "register.confirm" to "/api/desktop/auth/register/confirm",
        "login" to "/api/desktop/auth/login",
        "login.confirm" to "/api/desktop/auth/login/confirm",
        "password_reset.request" to "/api/desktop/auth/password-reset",
        "password_reset.confirm" to "/api/desktop/auth/password-reset/confirm",
    )
}
