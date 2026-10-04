package com.idvb.android.resources

import com.idvb.android.alignment.VpsgReferenceGeometry
import com.idvb.android.recognize.side.SparseGateSearch
import com.idvb.android.recognize.vpsg.VpsgPreparedIndex

/** Called by the map release boundary after producer/consumer barriers, never during normal alignment. */
internal object MapRuntimeCaches {
    fun snapshot(): Map<String, Double> = mapOf(
        "sideIndexBytes" to SparseGateSearch.retainedBytes.toDouble(),
        "vpsgIndexBudgetBytes" to VpsgPreparedIndex.retainedBytes.toDouble(),
        "referenceGeometryEntries" to VpsgReferenceGeometry.retainedEntries.toDouble(),
        "previewBytes" to MapBitmapCaches.previews.retainedBytes.toDouble(),
        "coverBytes" to MapBitmapCaches.covers.retainedBytes.toDouble(),
    )

    fun release() {
        SparseGateSearch.clear()
        VpsgPreparedIndex.clear()
        VpsgReferenceGeometry.clear()
        MapBitmapCaches.previews.clear()
        MapBitmapCaches.covers.clear()
    }
}
