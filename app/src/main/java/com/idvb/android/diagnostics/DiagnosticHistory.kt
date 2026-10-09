package com.idvb.android.diagnostics

import java.io.File
import java.util.UUID

/** Private app storage. A session begins when the overlay starts or map identity is reset.
 * Repeated scans/floor changes stay together. Legacy files form one unknown session.
 */
class DiagnosticHistory(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private val sessions get() = File(root, "sessions")

    fun begin(): File = beginAt(now())

    private fun beginAt(time: Long): File = synchronized(lock) {
        val previous = sessionDirectories().firstOrNull()?.name?.substringBefore('-')?.toLongOrNull() ?: 0L
        val start = maxOf(time, previous + 1)
        val folder = File(sessions, "$start-${UUID.randomUUID()}")
        check(folder.mkdirs()) { "无法创建场次诊断目录" }
        prune()
        folder
    }

    fun directoryAt(time: Long, kind: String): File = synchronized(lock) {
        require(kind in setOf("recognition", "alignment"))
        val all = sessionDirectories()
        val session = all.firstOrNull { it.name.substringBefore('-').toLong() <= time }
            ?: if (all.isEmpty()) beginAt(time) else error("该请求所属场次已过期，未写入其他场次")
        File(session, kind).apply { check(mkdirs() || isDirectory) }
    }

    fun directoryFor(sessionId: String, kind: String): File = synchronized(lock) {
        require(kind in setOf("recognition", "alignment"))
        val session = sessionDirectories().firstOrNull { it.name == sessionId }
            ?: error("该请求所属场次已过期，未写入其他场次")
        File(session, kind).apply { check(mkdirs() || isDirectory) }
    }

    fun files(kind: String): List<File> = synchronized(lock) {
        (sessionDirectories().take(MAX_SESSIONS).map { File(it, kind) } +
            if (sessionDirectories().size < MAX_SESSIONS) listOf(File(root, kind)) else emptyList())
            .flatMap { it.listFiles()?.filter(File::isFile).orEmpty() }
    }

    /** Keep snapshot files alive without blocking the UI on a potentially large ZIP write. */
    fun <T> preservingFiles(block: () -> T): T {
        return retainFiles().use { block() }
    }

    /** Lease may span capture, enqueue and archive commit without holding the history monitor. */
    fun retainFiles(): AutoCloseable {
        val key = root.canonicalPath
        synchronized(lock) { readers[key] = (readers[key] ?: 0) + 1 }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) {
            synchronized(lock) {
                val remaining = readers.getValue(key) - 1
                if (remaining == 0) { readers.remove(key); drainCommittedCleanup(key); prune() }
                else readers[key] = remaining
            }
        } }
    }

    /** An export may still be reading a just-committed journal. Delete only after all leases close. */
    fun discardCommittedJournal(directory: File) = synchronized(lock) {
        val key = root.canonicalPath
        val spool = File(root, "spool").canonicalFile.toPath()
        val target = directory.canonicalFile
        require(target.toPath().startsWith(spool) && target.toPath() != spool)
        committedCleanup.getOrPut(key) { linkedSetOf() }.add(target)
        if ((readers[key] ?: 0) == 0) drainCommittedCleanup(key)
    }

    fun sessionDirectories(): List<File> = sessions.listFiles()?.filter {
        it.isDirectory && it.name.substringBefore('-').toLongOrNull() != null
    }?.sortedByDescending { it.name.substringBefore('-').toLong() }.orEmpty()

    private fun prune() {
        if ((readers[root.canonicalPath] ?: 0) > 0) return
        val all = sessionDirectories()
        all.drop(MAX_SESSIONS).forEach { check(it.deleteRecursively()) { "无法清理过期场次" } }
        // Unknown legacy history counts as one oldest session until 20 new sessions exist.
        if (all.size >= MAX_SESSIONS) listOf("recognition", "alignment").forEach {
            val old = File(root, it)
            check(!old.exists() || old.deleteRecursively()) { "无法清理旧版诊断" }
        }
    }

    companion object {
        const val MAX_SESSIONS = 20
        val lock = Any()
        private val readers = mutableMapOf<String, Int>()
        private val committedCleanup = mutableMapOf<String, MutableSet<File>>()
        private fun drainCommittedCleanup(key: String) {
            committedCleanup.remove(key)?.forEach { it.deleteRecursively() }
        }
        /** Caller holds lock; clearing must not invalidate an active writer/export. */
        fun requireIdle(root: File) {
            check((readers[root.canonicalPath] ?: 0) == 0) { "诊断正在写入或导出，请稍后清理" }
        }
    }
}
