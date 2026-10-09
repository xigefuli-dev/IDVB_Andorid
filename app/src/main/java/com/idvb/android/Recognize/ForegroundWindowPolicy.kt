package com.idvb.android.recognize

/** Window events describe dialogs too; they are not all changes of the underlying app. */
object ForegroundWindowPolicy {
    const val VERSION = "window-identity-v2"

    fun ignoredWindowReason(packageName: String?, className: String?, fullScreen: Boolean = false): String? {
        if (className?.startsWith("com.idvb.android.overlay.") == true) return "own-overlay"
        if (packageName == "com.android.systemui" && className != null) {
            val windowClass = className.substringAfterLast('.').substringBefore('$')
            if (windowClass.startsWith("VolumeDialog") || windowClass.startsWith("VolumePanel") ||
                windowClass == "OplusVolumeDialogView") {
                // Dismissal of the volume panel need not emit a new game window event.
                return "transient-volume-window"
            }
            // Lenovo feedback 2026-10-08: a generic widget event replaced the game
            // indefinitely. A non-fullscreen system widget is not an app identity.
            // Named notification/keyguard windows and fullscreen events still block.
            if (!fullScreen && isGenericWidget(className)) return "transient-system-widget"
        }
        if (!fullScreen && packageName in setOf("com.zui.freeform.sidebar", "com.zui.game.service") &&
            isGenericWidget(className)) return "transient-zui-tool-window"
        return null
    }

    private fun isGenericWidget(className: String?): Boolean = className in setOf(
        "android.widget.FrameLayout", "android.widget.LinearLayout", "android.view.View", "android.view.ViewGroup")
}
