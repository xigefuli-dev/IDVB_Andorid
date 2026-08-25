package com.idvb.android.overlay

import com.idvb.android.data.OverlayPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗可观察状态，供 Compose UI 与 OverlayService 共享。
 * 服务写入，UI 收集。
 */
object OverlayState {

    data class Snapshot(
        val running: Boolean = false,
        val visible: Boolean = false,
        val locked: Boolean = false,
        val opacity: Float = OverlayPrefs.DEFAULT_OPACITY,
        val mapTitle: String? = null,
        val floorLabel: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Snapshot(opacity = _state.value.opacity)
    }
}
