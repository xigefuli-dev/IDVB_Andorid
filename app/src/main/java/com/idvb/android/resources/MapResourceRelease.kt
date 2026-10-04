package com.idvb.android.resources

/** One off-UI release at a time. Steps must stop/join owners before removing shared references. */
internal class MapResourceRelease(
    private val execute: (Runnable) -> Unit,
    private val postCompletion: (Runnable) -> Unit,
) {
    data class Step(val name: String, val action: () -> Unit)
    @Volatile var pending = false
        private set

    fun start(steps: List<Step>, record: (String, Long, Throwable?) -> Unit,
        complete: (Result<Unit>) -> Unit) {
        check(!pending) { "Map resources are already being released" }
        pending = true
        val queuedAt = System.nanoTime()
        try {
            execute(Runnable {
                val result = runCatching {
                    record("queue", System.nanoTime() - queuedAt, null)
                    for (step in steps) {
                        val began = System.nanoTime()
                        val outcome = runCatching(step.action)
                        record(step.name, System.nanoTime() - began, outcome.exceptionOrNull())
                        // An unfinished owner must never be treated as a successful release.
                        outcome.getOrThrow()
                    }
                }
                postCompletion(Runnable { pending = false; complete(result) })
            })
        } catch (failure: Exception) {
            pending = false
            complete(Result.failure(failure))
        }
    }
}
