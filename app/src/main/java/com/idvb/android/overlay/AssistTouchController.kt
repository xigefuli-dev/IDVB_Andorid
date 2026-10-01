package com.idvb.android.overlay

/** Serializes open/close clicks and rejects callbacks after lifecycle invalidation. */
class AssistTouchController(
    private val click: (Boolean, (Boolean) -> Unit) -> Unit,
    private val show: () -> Unit,
    private val hide: () -> Unit,
    private val failed: () -> Unit,
) {
    var active = false
        private set
    private var pending = false
    val busy: Boolean get() = pending
    private var generation = 0
    fun toggle() {
        if (active) {
            active = false; hide()
            if (!pending) close()
        } else if (!pending) {
            active = true; pending = true
            val token = generation
            click(true) { success ->
                if (token == generation) {
                    pending = false
                    if (!success) { active = false; hide(); failed() }
                    else if (active) show() else close()
                }
            }
        }
    }
    private fun close() {
        pending = true
        val token = generation
        click(false) { success -> if (token == generation) { pending = false; if (!success) failed() } }
    }
    fun cancel() { generation++; active = false; pending = false; hide() }
}
