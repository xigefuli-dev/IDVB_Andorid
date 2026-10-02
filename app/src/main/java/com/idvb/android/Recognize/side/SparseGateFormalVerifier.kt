package com.idvb.android.recognize.side

import com.idvb.android.recognize.SparseGateFormalAttemptEvidence
import com.idvb.android.recognize.SparseGateHypothesisEvidence
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.structure.StructureRegistrationResult

/** Validate the finite retained basins before excluding an identity. Formal global
 * recovery only explores translations near one seed's scale, not other basins. */
internal object SparseGateFormalVerifier {
    data class Outcome(
        val accepted: Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>?,
        val registration: StructureRegistrationResult?,
        val complete: Boolean,
        val attempts: List<SparseGateFormalAttemptEvidence>,
        val decisionReason: String,
    )

    fun evaluate(
        hypotheses: List<Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>>,
        viewport: ScreenRect,
        config: SideEntranceScanConfig,
        gateResidual: (SparseGateSearch.Pose) -> Double,
        register: (SparseGateSearch.Pose) -> StructureRegistrationResult,
        verify: (SparseGateSearch.Pose) -> SparseGateSearch.Evidence,
        onFailure: (Exception) -> Unit = {},
        onAttempt: (SparseGateFormalAttemptEvidence) -> Unit = {},
    ): Outcome {
        val retained = hypotheses.withIndex().filter { it.value.second.supported }
            .sortedBy { it.value.second.cost }
        val attempts = ArrayList<SparseGateFormalAttemptEvidence>(retained.size)
        var complete = true
        var firstRegistration: StructureRegistrationResult? = null
        var firstReason = "formal:not-invoked"
        for ((index,hypothesis) in retained) {
            val (seed,seedEvidence) = hypothesis
            var registration: StructureRegistrationResult? = null
            var checked: Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>? = null
            var elapsed = 0.0
            var verificationElapsed = 0.0
            var accepted = false
            var reason = "formal:verification-error"
            var verificationFailure = ""
            try {
                val started = System.nanoTime()
                try { registration = register(seed) }
                finally { elapsed = (System.nanoTime()-started)/1e6 }
                val result = requireNotNull(registration).copy(elapsedMilliseconds=elapsed)
                registration = result
                val transform = result.transform
                reason = when {
                    !result.accepted -> "formal:${result.rejectionReason.name}"
                    transform == null -> "formal:transform-unavailable"
                    transform.scale !in config.minimumScale..config.maximumScale -> "formal:scale-out-of-range"
                    else -> {
                        val pose = seed.copy(scale=transform.scale,
                            x=transform.offsetX-viewport.x,y=transform.offsetY-viewport.y)
                        val verificationStarted = System.nanoTime()
                        try {
                            val evidence = verify(pose)
                            checked = pose to evidence
                            val residual = gateResidual(pose)
                            accepted = evidence.supported && residual <= config.maximumGateSpatialResidualPixels
                            when {
                                !evidence.supported -> evidence.rejectionCode
                                residual > config.maximumGateSpatialResidualPixels || !residual.isFinite() -> "gate-residual"
                                else -> "supported"
                            }
                        } finally { verificationElapsed = (System.nanoTime()-verificationStarted)/1e6 }
                    }
                }
            } catch (error: Exception) {
                complete = false
                verificationFailure = "${error.javaClass.name}: ${error.message.orEmpty()}"
                onFailure(error)
            }
            val attempt = SparseGateFormalAttemptEvidence(index,record(seed,seedEvidence,viewport,gateResidual),
                checked?.let { record(it.first,it.second,viewport,gateResidual) },elapsed,
                registration?.accepted == true,registration?.rejectionReason?.name ?: "VERIFICATION_ERROR",
                registration?.failureReason ?: "Formal verification threw before returning a result.",
                registration?.usedGlobalRecovery == true,registration?.candidateMargin ?: 0.0,
                accepted,reason,verificationElapsed,registration?.best,verificationFailure)
            if (attempts.isEmpty()) { firstRegistration=registration; firstReason=reason }
            attempts.add(attempt)
            onAttempt(attempt)
            if (accepted) return Outcome(checked,registration,complete,attempts,"supported")
        }
        return Outcome(null,firstRegistration,complete,attempts,firstReason)
    }

    private fun record(pose: SparseGateSearch.Pose, evidence: SparseGateSearch.Evidence,
        viewport: ScreenRect, gateResidual: (SparseGateSearch.Pose) -> Double) =
        SparseGateHypothesisEvidence(pose.scale,viewport.x+pose.x,viewport.y+pose.y,pose.gate,
            gateResidual(pose),evidence.support,evidence.reverseSupport,evidence.total,evidence.reversePoints,
            evidence.mean,evidence.longest,evidence.spatialConflict,evidence.rejectionCode)
}
