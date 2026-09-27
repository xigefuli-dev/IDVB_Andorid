package com.idvb.android.recognize

import android.app.Activity
import android.content.Intent

/** 当前进程内的 MediaProjection 授权；服务启动后立即消费并保持捕获会话。 */
object ScreenCaptureGrant {
    @Volatile var revision: Long = 0
        private set
    private var consumed = false
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
        private set
    @Volatile var data: Intent? = null
        private set

    @Synchronized
    fun update(code: Int, intent: Intent?) {
        revision++
        consumed = false
        resultCode = code
        data = intent?.let(::Intent)
    }

    val available: Boolean get() = resultCode == Activity.RESULT_OK && data != null

    @Synchronized
    fun consume(expectedRevision: Long): Intent? {
        if (revision != expectedRevision || consumed || !available) return null
        consumed = true
        return data?.let(::Intent)
    }

    @Synchronized
    fun invalidate(expectedRevision: Long) {
        if (revision != expectedRevision) return
        data = null
        resultCode = Activity.RESULT_CANCELED
    }
}
