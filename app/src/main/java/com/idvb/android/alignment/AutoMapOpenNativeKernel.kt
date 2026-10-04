package com.idvb.android.alignment

import androidx.annotation.Keep

/** Exact double-precision implementation of the same windows, probes and score terms.
 * The managed implementation remains available as an independent differential oracle. */
@Keep
internal object AutoMapOpenNativeKernel {
    val available by lazy { runCatching { System.loadLibrary("idvb_vpsg") }.isSuccess }
    val backend get() = if (available) "native-spatial-exact-v1" else "managed-spatial-v2"

    fun compare(reference: AutoMapOpenSignature, candidate: AutoMapOpenSignature,
        widthFraction: Double): AutoMapOpenComparison {
        val values = requireNotNull(compareNative(reference.luminance, reference.redChroma, reference.blueChroma,
            candidate.luminance, candidate.redChroma, candidate.blueChroma, widthFraction))
        return AutoMapOpenComparison(values[0], values[1], values[2], values[3], values[4].toInt(),
            values[5].toInt(), values[6], values[7])
    }

    private external fun compareNative(referenceLuma: DoubleArray, referenceRed: DoubleArray, referenceBlue: DoubleArray,
        candidateLuma: DoubleArray, candidateRed: DoubleArray, candidateBlue: DoubleArray, widthFraction: Double): DoubleArray?
}
