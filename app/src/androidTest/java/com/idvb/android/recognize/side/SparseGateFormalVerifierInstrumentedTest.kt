package com.idvb.android.recognize.side

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.recognize.structure.MapStructureRegistrar
import com.idvb.android.recognize.structure.StructureFeatures
import com.idvb.android.recognize.structure.StructureRegistrationResult
import com.idvb.android.recognize.vpsg.VpsgLiveExtractor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/** Independent StructureFeatures construction, executed through Android OpenCV JNI.
 * This proves retained-pose consumption; real captures remain in the opt-in corpus. */
@RunWith(AndroidJUnit4::class)
class SparseGateFormalVerifierInstrumentedTest {
    @Before fun initializeCv() { assertTrue(OpenCvRuntime.initialize()) }

    @Test fun rejectedBestScaleCannotExcludeAnotherSupportedScaleBasin() {
        Fixture().use { fixture ->
            val hypotheses = fixture.hypotheses()
            assertTrue(hypotheses.all { it.second.supported })
            assertTrue(hypotheses[0].second.cost < hypotheses[1].second.cost)
            val oldBestOnly = fixture.register(hypotheses[0].first)
            assertFalse(oldBestOnly.toString(),oldBestOnly.accepted)
            assertTrue(oldBestOnly.best!!.occupancyCoverage < .42)

            val result = fixture.evaluate(hypotheses)
            assertTrue(result.toString(),result.complete)
            assertNotNull(result.accepted)
            assertEquals(2,result.attempts.size)
            assertEquals(0,result.attempts[0].seedHypothesisIndex)
            assertFalse(result.attempts[0].registrationAccepted)
            assertFalse(result.attempts[0].accepted)
            assertTrue(result.attempts[0].decisionReason.startsWith("formal:"))
            assertTrue(result.attempts[0].elapsedMilliseconds > 0.0)
            assertNotNull(result.attempts[0].registrationBest)
            assertEquals(1,result.attempts[1].seedHypothesisIndex)
            assertTrue(result.attempts[1].registrationAccepted)
            assertTrue(result.attempts[1].accepted)
            assertNotNull(result.attempts[1].checked)
            assertEquals("supported",result.attempts[1].decisionReason)
            assertEquals(1.08,result.accepted!!.first.scale,.001)
            assertEquals(result.attempts[1].elapsedMilliseconds,result.registration!!.elapsedMilliseconds,0.0)
        }
    }

    @Test fun exceptionKeepsEvaluationIncompleteEvenWhenAnotherPosePasses() {
        Fixture().use { fixture ->
            val result = fixture.evaluate(fixture.hypotheses()) { pose ->
                if (pose.scale == 1.0) throw IllegalStateException("constructed first-basin failure")
                fixture.register(pose)
            }
            assertNotNull(result.accepted)
            assertFalse(result.complete)
            assertEquals(2,result.attempts.size)
            assertEquals("formal:verification-error",result.attempts[0].decisionReason)
            assertEquals("VERIFICATION_ERROR",result.attempts[0].registrationRejectionReason)
            assertTrue(result.attempts[0].verificationFailureReason.contains("constructed first-basin failure"))
            assertTrue(result.attempts[1].accepted)
        }
    }

    @Test fun everyRetainedSupportedPoseMustRejectBeforeIdentityIsExcluded() {
        Fixture(emptyOccupancy=true).use { fixture ->
            val hypotheses=fixture.hypotheses()
            assertTrue(hypotheses.all { it.second.supported })
            val result=fixture.evaluate(hypotheses)
            assertTrue(result.complete)
            assertNull(result.accepted)
            assertEquals(2,result.attempts.size)
            assertTrue(result.attempts.all { !it.registrationAccepted && !it.accepted })
        }
    }

    @Test fun finalGateRejectionContinuesToNextRetainedPose() {
        Fixture().use { fixture ->
            val hypotheses=fixture.hypotheses()
            val validRegistration=fixture.register(hypotheses[1].first)
            assertTrue(validRegistration.accepted)
            val result=SparseGateFormalVerifier.evaluate(hypotheses,fixture.viewport,fixture.config,
                gateResidual={ if (it.gate==0) 43.0 else 0.0 },
                register={ validRegistration },verify=fixture::verify)
            // Both seeds reach the same valid geometry, but the first gate is incompatible.
            assertNull(result.accepted)
            assertEquals(2,result.attempts.size)
            assertTrue(result.attempts.all { it.registrationAccepted && it.decisionReason=="gate-residual" })
            val secondGate=hypotheses[1].let { it.first.copy(gate=1) to it.second }
            val accepted=SparseGateFormalVerifier.evaluate(listOf(hypotheses[0],secondGate),fixture.viewport,fixture.config,
                gateResidual={ if (it.gate==0) 43.0 else 0.0 },
                register={ validRegistration },verify=fixture::verify)
            assertTrue(accepted.complete)
            assertEquals(2,accepted.attempts.size)
            assertFalse(accepted.attempts[0].accepted)
            assertTrue(accepted.attempts[1].accepted)
            assertEquals(1,accepted.accepted!!.first.gate)
        }
    }

    private class Fixture(emptyOccupancy: Boolean = false): AutoCloseable {
        val viewport=ScreenRect(0.0,0.0,192.0,192.0)
        val config=SideEntranceScanConfig(minimumScale=.8,maximumScale=1.2)
        private val edges=Mat.zeros(192,192,CvType.CV_8UC1)
        private val referenceMask=Mat.zeros(192,192,CvType.CV_8UC1)
        private val domain=Mat.ones(192,192,CvType.CV_8UC1)
        private val reference: StructureFeatures
        private val live: StructureFeatures
        private val observation: VpsgLiveExtractor.Observation
        private val index: SparseGateSearch.Index
        private val reverse: SparseGateSearch.ReverseObservation
        private val dense: List<SparseGateSearch.Pixel>
        private val contours: List<List<SparseGateSearch.Pixel>>

        init {
            domain.setTo(Scalar.all(255.0))
            for (position in listOf(32,44,56,69,82,96,109,122,135,150)) {
                Imgproc.line(edges,Point(position.toDouble(),32.0),Point(position.toDouble(),150.0),Scalar.all(255.0),1)
                Imgproc.line(edges,Point(32.0,position.toDouble()),Point(150.0,position.toDouble()),Scalar.all(255.0),1)
            }
            if (!emptyOccupancy) {
                val scaled=Mat()
                val roi=referenceMask.submat(Rect(7,7,178,178))
                try {
                    Imgproc.resize(edges,scaled,Size(178.0,178.0),0.0,0.0,Imgproc.INTER_NEAREST)
                    scaled.copyTo(roi)
                } finally { scaled.release(); roi.release() }
            }
            // Immutable prebuilt edges and separately derived occupancy are valid inputs.
            reference=StructureFeatures(referenceMask.clone(),edges.clone())
            live=StructureFeatures(edges.clone(),edges.clone())
            observation=VpsgLiveExtractor.Observation(edges.clone(),domain.clone(),edges.clone(),domain.clone())
            index=SparseGateSearch.build(edges)
            reverse=SparseGateSearch.ReverseObservation(observation)
            dense=SparseGateSearch.pixels(edges)
            contours=SparseGateSearch.contours(edges)
        }

        fun verify(pose: SparseGateSearch.Pose) =
            SparseGateSearch.verify(index,pose,dense,contours,192,192,reverse=reverse)

        fun hypotheses() = listOf(SparseGateSearch.Pose(1.0,0.0,0.0),
            SparseGateSearch.Pose(1.08,-7.68,-7.68)).map { it to verify(it) }

        fun register(pose: SparseGateSearch.Pose): StructureRegistrationResult =
            MapStructureRegistrar().register(reference,live,viewport,pose.scale,pose.x,pose.y,
                NormalizedRect(0.0,0.0,1.0,1.0),allowStrongSeedEarlyExit=true)

        fun evaluate(hypotheses: List<Pair<SparseGateSearch.Pose,SparseGateSearch.Evidence>>,
            registration: (SparseGateSearch.Pose) -> StructureRegistrationResult = ::register) =
            SparseGateFormalVerifier.evaluate(hypotheses,viewport,config,
                gateResidual={ hypot(it.x+96*it.scale-96,it.y+96*it.scale-96) },
                register=registration,verify=::verify)

        override fun close() {
            observation.close(); live.close(); reference.close()
            edges.release(); referenceMask.release(); domain.release()
        }
    }
}
