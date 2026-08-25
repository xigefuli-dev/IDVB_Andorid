package com.idvb.android.recognize

import android.app.Activity
import android.content.Intent

/** 当前进程内的 MediaProjection 授权；服务启动后立即消费并保持捕获会话。 */
object ScreenCaptureGrant {
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
        private set
    @Volatile var data: Intent? = null
        private set

    fun update(code: Int, intent: Intent?) {
        resultCode = code
        data = intent?.let(::Intent)
    }

    val available: Boolean get() = resultCode == Activity.RESULT_OK && data != null
}
