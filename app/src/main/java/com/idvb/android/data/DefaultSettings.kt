package com.idvb.android.data

/** Feature defaults verified against the user's emulator settings on 2026-09-30.
 * Keep device calibration, selected maps, tutorial progress and consent out of this profile.
 */
object DefaultSettings {
    const val OPACITY = 0.46f
    const val LOCKED = false
    const val HOLD_TO_ACTIVATE = true
    const val ALIGNMENT_REPLAY_INPUTS = true
    const val AUTO_START_ON_BOOT = false
    const val DEBUG_MODE = false
    const val BACKGROUND_SCAN = true
    const val MANUAL_MAP_SELECTION = false
    const val SHOW_UNCONFIRMED_CANDIDATES = true
    const val RECOGNITION_DIAGNOSTICS = true
    const val SHOW_ALIGNMENT_OUTPUT = false
    const val CONSTRAIN_GUIDE_TO_SCREEN = false
    const val REMOVE_GUIDE_BACKGROUND = false
    const val SHOW_ROUTES = true
    const val ROUTE_LINE_THICKNESS = 1
}
