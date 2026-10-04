package com.idvb.android.resources

import android.graphics.Bitmap

/** Cache references only: visible Compose consumers may still borrow the same bitmap. */
internal object MapBitmapCaches {
    val previews = GenerationCache<String, Bitmap>(12L * 1024 * 1024) { it.allocationByteCount.toLong() }
    val covers = GenerationCache<String, Bitmap>(8L * 1024 * 1024) { it.allocationByteCount.toLong() }
}
