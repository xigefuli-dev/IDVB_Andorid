package com.idvb.android.overlay

/** A pending scan choice takes precedence over a map retained from an earlier scan. */
internal data class OverlayControlPolicy(
    val automaticMapOpen: Boolean,
    val mapSelected: Boolean,
    val candidatesPending: Boolean,
) {
    val eyeEnabled: Boolean get() = candidatesPending || (!automaticMapOpen && mapSelected)
    val floorEnabled: Boolean get() = !automaticMapOpen && mapSelected
    val retainDismissedCandidates: Boolean get() = automaticMapOpen && candidatesPending
}
