package com.idvb.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class CommunityJobStatus { QUEUED, RUNNING, DONE, FAILED }

data class CommunityDownloadJob(
    val token: String,
    val publicationId: String,
    val name: String,
    val status: CommunityJobStatus,
    val phase: String = "等待下载",
    val fraction: Float? = null,
    val result: String? = null,
    val link: String,
)

/** One app-level worker preserves the queue when the subscription tab is no longer visible. */
class CommunityDownloadQueue(context: Context) {
    private val service = CommunitySubscriptions(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var workerRunning = false
    private val _jobs = MutableStateFlow<List<CommunityDownloadJob>>(emptyList())
    val jobs: StateFlow<List<CommunityDownloadJob>> = _jobs.asStateFlow()
    private val _completionRevision = MutableStateFlow(0)
    val completionRevision: StateFlow<Int> = _completionRevision.asStateFlow()

    fun enqueue(entry: CommunityEntry): Boolean {
        val link = entry.link ?: return false
        synchronized(lock) {
            if (_jobs.value.any { it.publicationId == entry.id && it.status in setOf(CommunityJobStatus.QUEUED, CommunityJobStatus.RUNNING) })
                return false
            _jobs.value = _jobs.value + CommunityDownloadJob(UUID.randomUUID().toString(), entry.id, entry.name,
                CommunityJobStatus.QUEUED, link = link)
            if (!workerRunning) {
                workerRunning = true
                scope.launch { drain() }
            }
        }
        return true
    }

    fun clearFinished() = synchronized(lock) {
        _jobs.value = _jobs.value.filter { it.status == CommunityJobStatus.QUEUED || it.status == CommunityJobStatus.RUNNING }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val queued = _jobs.value.firstOrNull { it.status == CommunityJobStatus.QUEUED }
                if (queued == null) workerRunning = false
                else update(queued.token) { it.copy(status = CommunityJobStatus.RUNNING, phase = "读取订阅信息") }
                queued
            } ?: return
            val outcome = runCatching {
                service.subscribe(next.link) { progress ->
                    synchronized(lock) {
                        update(next.token) { it.copy(phase = progress.phase, fraction = progress.fraction?.coerceIn(0f, 1f)) }
                    }
                }
            }
            synchronized(lock) {
                outcome.onSuccess { message ->
                    update(next.token) { it.copy(status = CommunityJobStatus.DONE, phase = "已完成", fraction = 1f, result = message) }
                    _completionRevision.value += 1
                }.onFailure { error ->
                    update(next.token) { it.copy(status = CommunityJobStatus.FAILED, phase = "下载失败", result = error.message ?: "请稍后重试") }
                }
            }
        }
    }

    private fun update(token: String, change: (CommunityDownloadJob) -> CommunityDownloadJob) {
        _jobs.value = _jobs.value.map { if (it.token == token) change(it) else it }
    }
}
