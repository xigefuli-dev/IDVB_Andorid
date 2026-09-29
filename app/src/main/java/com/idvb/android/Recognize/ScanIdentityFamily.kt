package com.idvb.android.recognize

import com.idvb.android.idvm.MapVariantGroupRecord

/** One explicitly declared family must contain every plausible identity. Never union
 * overlapping groups: A/B and B/C do not make A/C interchangeable. */
internal fun sameScanIdentityFamily(
    winnerId: String,
    classId: String,
    competingIds: Collection<String>,
    groups: List<MapVariantGroupRecord>,
): Boolean = competingIds.all { it == winnerId } || groups.any { group ->
    group.classId == classId && winnerId in group.mapIds &&
        competingIds.all { it in group.mapIds }
}
