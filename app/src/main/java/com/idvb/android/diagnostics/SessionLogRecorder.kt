package com.idvb.android.diagnostics

import android.os.Process
import android.util.Log
import java.io.File

/** Streams only this app's process logs into private session storage. */
class SessionLogRecorder(private val root: File) {
    private var process: java.lang.Process? = null
    var sessionId: String? = null
        private set

    fun begin() {
        stop()
        sessionId = null
        runCatching {
            val folder = DiagnosticHistory(root).begin()
            sessionId = folder.name
            process = ProcessBuilder("logcat", "--pid=${Process.myPid()}", "-v", "threadtime", "-T", "1")
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(folder, "app.log")))
                .redirectError(File(folder, "logcat-error.txt")).start()
        }.onFailure { Log.e("IDVB-Diagnostics", "Session log recording unavailable", it) }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
