package com.idvb.android.recognize

/** Window events describe dialogs too; they are not all changes of the underlying app. */
object ForegroundWindowPolicy {
    fun ignoredWindowReason(packageName: String?, className: String?): String? {
        if (className?.startsWith("com.idvb.android.overlay.") == true) return "own-overlay"
        if (packageName == "com.android.systemui" && className != null) {
            val windowClass = className.substringAfterLast('.').substringBefore('$')
            if (windowClass.startsWith("VolumeDialog") || windowClass.startsWith("VolumePanel")) {
                // Dismissal of the volume panel need not emit a new game window event.
                return "transient-volume-window"
            }
        }
        return null
    }
}
