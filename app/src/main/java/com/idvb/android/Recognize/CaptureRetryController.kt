package com.idvb.android.recognize

/** Request-local, cancellable backoff for Android screenshot rate limits. Never a UI debounce. */
internal class CaptureRetryController(private val schedule: (Long, () -> Unit) -> (() -> Unit)) {
    private var stopped = false
    private var attempts = 0
    private var removePending: (() -> Unit)? = null

    @Synchronized fun retry(action: () -> Unit): Long? {
        if (stopped || attempts >= 8) return null
        val delay = (80L shl attempts.coerceAtMost(2)).also { attempts++ }
        removePending?.invoke()
        removePending = schedule(delay) {
            synchronized(this) {
                removePending = null
                if (!stopped) action()
            }
        }
        return delay
    }

    @Synchronized fun stop() {
        stopped = true
        removePending?.invoke()
        removePending = null
    }
}
