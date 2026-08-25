package com.idvb.android.recognize

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台扫描执行路线预留。暂不由服务启动；后续只需把连续帧送入 [submitFrame]，
 * 算法和前台扫描保持同一个 [RecognitionRoute]，避免两套识别结果发生偏差。
 */
class BackgroundSideEntranceRoute(
    private val recognizer: RecognitionRoute,
    private val onResult: (RecognitionResult) -> Unit,
) : BackgroundRecognitionRoute {
    private val prepared = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    override fun prepare() { prepared.set(true) }

    override fun submitFrame(frame: Bitmap, timestampMillis: Long) {
        if (!prepared.get() || !busy.compareAndSet(false, true)) return
        Thread {
            try { onResult(recognizer.recognize(frame)) }
            finally { busy.set(false) }
        }.start()
    }

    override fun stop() { prepared.set(false) }
}
