package com.idvb.android.recognize

import com.idvb.android.recognize.side.SparseGateRecognizer
import com.idvb.android.recognize.vpsg.VpsgLineScanner

/** Only a complete, verified route may commit one member of a resolved identity family. */
internal fun RecognitionResult.automaticallyConfirmedCandidate(): RecognitionCandidate? {
    val eligible = route == VpsgLineScanner.ROUTE ||
        route == SparseGateRecognizer.ROUTE && sparseGateDiagnostics?.let {
            it.retrievalComplete && it.identityUnique && it.supportedIdentityCount >= 1
        } == true
    return if (eligible) candidates.filter { it.disposition == CandidateDisposition.RELIABLE }.singleOrNull() else null
}
