package com.idvb.android.recognize

/** A duplicate or stale image must never count as a second physical observation. */
internal object ProjectionFrameFreshness {
    fun usable(sequence: Long, minimumSequence: Long, ageNanos: Long, maximumAgeMs: Long): Boolean =
        sequence > minimumSequence && ageNanos >= 0L && ageNanos / 1e6 <= maximumAgeMs
}
