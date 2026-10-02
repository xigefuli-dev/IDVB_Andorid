package com.idvb.android.recognize.structure

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.idvb.android.idvm.NormalizedRect
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.recognize.gate.ScreenRect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Independent binary geometry through Android OpenCV JNI; no private game corpus is bundled. */
@RunWith(AndroidJUnit4::class)
class MapStructureKnownDomainInstrumentedTest {
    @Before fun initializeCv() { assertTrue(OpenCvRuntime.initialize()) }

    @Test fun referenceWallsOutsideKnownDomainDoNotChangeCoverageOrCost() {
        Fixture().use { fixture ->
            val before = fixture.register().best!!
            fixture.addUnknownWall()
            val after = fixture.register().best!!
            assertEquals(QUERY_KNOWN_REFERENCE_COVERAGE_DOMAIN, after.referenceCoverageDomain)
            assertEquals(1.0, before.referenceCoverage, 0.0)
            assertEquals(before.referenceCoverage, after.referenceCoverage, 0.0)
            assertEquals(before.compositeCost, after.compositeCost, 0.0)
            assertEquals(before.referenceKnownPixels, after.referenceKnownPixels)
            assertEquals(before.referenceKnownOverlapPixels, after.referenceKnownOverlapPixels)
            assertTrue(after.referenceUnknownPixels > before.referenceUnknownPixels)
            assertEquals(before.chamferPixels, after.chamferPixels, 0.0)
            assertEquals(before.occupancyCoverage, after.occupancyCoverage, 0.0)
        }
    }

    @Test fun referenceWallsInsideKnownDomainLowerCoverage() {
        Fixture().use { fixture ->
            val before = fixture.register().best!!
            fixture.addKnownWall()
            val after = fixture.register().best!!
            assertTrue(after.referenceCoverage < before.referenceCoverage)
            assertTrue(after.referenceKnownPixels > before.referenceKnownPixels)
            assertEquals(before.referenceKnownOverlapPixels, after.referenceKnownOverlapPixels)
            assertTrue(after.compositeCost > before.compositeCost)
            assertEquals(before.chamferPixels, after.chamferPixels, 0.0)
            assertEquals(before.occupancyCoverage, after.occupancyCoverage, 0.0)
            assertEquals(before.consistentPartitions, after.consistentPartitions)
        }
    }

    @Test fun eachCandidateUsesItsOwnReferencePatchWithTheQueryLocalDomain() {
        Fixture(twoPoses = true).use { fixture ->
            val before = fixture.register(earlyExit = false)
            fixture.addUnknownWall()
            val after = fixture.register(earlyExit = false)
            fun exact(result: StructureRegistrationResult) = result.candidates.filter {
                abs(it.scale - 1.0) < 1e-9 && it.chamferPixels < 1e-9 && it.occupancyCoverage == 1.0
            }.sortedBy { it.referenceX }
            val original = exact(before)
            val changed = exact(after)
            assertEquals(before.toString(), 2, original.size)
            assertEquals(after.toString(), 2, changed.size)
            assertEquals(original.map { it.referenceX }, changed.map { it.referenceX })
            original.zip(changed).forEach { (old, current) ->
                assertEquals(old.referenceCoverage, current.referenceCoverage, 0.0)
                assertEquals(old.compositeCost, current.compositeCost, 0.0)
                assertEquals(old.referenceKnownPixels, current.referenceKnownPixels)
                assertTrue(current.referenceUnknownPixels > old.referenceUnknownPixels)
            }
            assertEquals(before.candidateMargin, after.candidateMargin, 0.0)
            assertEquals(StructureRejectionReason.AMBIGUOUS_CANDIDATES, after.rejectionReason)
        }
    }

    @Test fun knownDomainFollowsQueryResizeAndReciprocalReferenceResize() {
        Fixture().use { fixture ->
            fixture.addUnknownWall()
            val base = fixture.register().best!!
            val doubledLive = fixture.register(liveMultiplier = 2).best!!
            val doubledReference = fixture.register(referenceMultiplier = 2).best!!
            for (candidate in listOf(doubledLive, doubledReference)) {
                assertEquals(base.referenceCoverage, candidate.referenceCoverage, 0.0)
                assertEquals(base.referenceKnownPixels, candidate.referenceKnownPixels)
                assertEquals(base.referenceUnknownPixels, candidate.referenceUnknownPixels)
                assertEquals(base.referenceKnownOverlapPixels, candidate.referenceKnownOverlapPixels)
                assertEquals(base.queryKnownPixels, candidate.queryKnownPixels)
            }
            assertEquals(2.0, doubledLive.scale, 0.0)
            assertEquals(.5, doubledReference.scale, 0.0)
            // Registration owns resized copies, never the caller's shared visibility mask.
            assertEquals(96, fixture.known.rows())
            assertEquals(96, fixture.known.cols())
            assertTrue(Core.countNonZero(fixture.known) > 0)
        }
    }

    @Test fun nullKnownDomainKeepsLegacyWholeRectangleCounting() {
        Fixture().use { fixture ->
            val before = fixture.register(known = false).best!!
            fixture.addUnknownWall()
            val legacy = fixture.register(known = false).best!!
            val visible = fixture.register().best!!
            assertEquals(LEGACY_REFERENCE_COVERAGE_DOMAIN, legacy.referenceCoverageDomain)
            assertTrue(legacy.referenceCoverage < before.referenceCoverage)
            assertEquals(0, legacy.referenceUnknownPixels)
            assertEquals(legacy.referenceKnownOverlapPixels / legacy.referenceKnownPixels.toDouble(),
                legacy.referenceCoverage, 0.0)
            assertEquals(1.0, visible.referenceCoverage, 0.0)
            assertEquals(before.chamferPixels, legacy.chamferPixels, 0.0)
            assertEquals(before.occupancyCoverage, legacy.occupancyCoverage, 0.0)
        }
    }

    private class Fixture(private val twoPoses: Boolean = false) : AutoCloseable {
        private val liveWalls = Mat.zeros(96, 96, CvType.CV_8UC1)
        private val referenceWalls = Mat.zeros(160, 260, CvType.CV_8UC1)
        val known = Mat(96, 96, CvType.CV_8UC1, Scalar.all(255.0))
        private val copies = if (twoPoses) listOf(20, 100) else listOf(20)

        init {
            Imgproc.rectangle(liveWalls, Rect(15, 15, 61, 61), Scalar.all(255.0), 2)
            Imgproc.line(liveWalls, Point(30.0, 15.0), Point(30.0, 36.0), Scalar.all(255.0), 2)
            Imgproc.line(liveWalls, Point(52.0, 75.0), Point(52.0, 64.0), Scalar.all(255.0), 2)
            Imgproc.rectangle(known, Rect(40, 40, 22, 22), Scalar.all(0.0), -1)
            copies.forEach { x ->
                val patch = referenceWalls.submat(Rect(x, 20, 96, 96))
                try { Core.bitwise_or(patch, liveWalls, patch) } finally { patch.release() }
            }
        }

        fun addUnknownWall() {
            copies.forEach { x -> Imgproc.line(referenceWalls, Point(x + 42.0, 68.0), Point(x + 58.0, 68.0), Scalar.all(255.0), 1) }
        }

        fun addKnownWall() {
            copies.forEach { x -> Imgproc.line(referenceWalls, Point(x + 32.0, 65.0), Point(x + 32.0, 82.0), Scalar.all(255.0), 1) }
        }

        fun register(known: Boolean = true, earlyExit: Boolean = true,
            liveMultiplier: Int = 1, referenceMultiplier: Int = 1): StructureRegistrationResult {
            val ref = resized(referenceWalls, referenceMultiplier)
            val live = resized(liveWalls, liveMultiplier)
            val domain = if (known) resized(this.known, liveMultiplier) else null
            StructureFeatures(ref.clone(), ref).use { reference ->
                StructureFeatures(live.clone(), live, domain).use { observed ->
                    val scale = liveMultiplier.toDouble() / referenceMultiplier
                    return MapStructureRegistrar(StructureRegistrationTuning(scaleSearchRadius = 0.0)).register(
                        reference, observed, ScreenRect(0.0, 0.0, live.cols().toDouble(), live.rows().toDouble()),
                        scale, -20.0 * liveMultiplier, -20.0 * liveMultiplier,
                        NormalizedRect(0.0, 0.0, 1.0, 1.0), allowStrongSeedEarlyExit = earlyExit)
                }
            }
        }

        private fun resized(source: Mat, multiplier: Int): Mat = Mat().also {
            Imgproc.resize(source, it, Size((source.cols() * multiplier).toDouble(),
                (source.rows() * multiplier).toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        }

        override fun close() { liveWalls.release(); referenceWalls.release(); known.release() }
    }
}
