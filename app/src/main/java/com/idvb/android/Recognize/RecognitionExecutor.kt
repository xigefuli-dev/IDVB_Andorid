package com.idvb.android.recognize

import android.os.Process
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 所有前台/后台识别共享的串行工作线程。OpenCV 可以在单次任务内部并行，
 * 但两次完整扫描不得同时占用 native Mat 与截图生命周期。
 */
class RecognitionExecutor : Closeable {
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "idvb-recognition").apply { isDaemon = true }
    }

    fun execute(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return runCatching {
            executor.execute(task)
            true
        }.getOrDefault(false)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    /** Resource-release worker only. Complete accepted tasks so their bitmap cleanup still runs. */
    fun awaitIdle() {
        if (executor.isShutdown) check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        else executor.submit {}.get(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}
