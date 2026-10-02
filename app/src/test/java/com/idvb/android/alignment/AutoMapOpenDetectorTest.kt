package com.idvb.android.alignment

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class AutoMapOpenDetectorTest {
    @Test fun continuousOneFramePresenceDoesNotRequireAnyTouchOrSecondOpenSample() {
        val pixels = IntArray(AutoMapOpenDetector.PIXELS) { i ->
            if (i / 32 % 4 < 2) 0xff687580.toInt() else 0xffc0c8d0.toInt()
        }
        val reference = AutoMapOpenDetector.signature(pixels)
        val absent = AutoMapOpenDetector.signature(IntArray(pixels.size) { 0xff000000.toInt() })
        val detector = AutoMapOpenDetector(reference, AutoMapOpenConfig(openFrames = 1, closeFrames = 1))
        repeat(200) { detector.observeComparison(detector.compareCandidate(absent), it * 16L) }
        assertFalse(detector.isOpen)
        assertEquals(AutoMapOpenTransition.OPENED, detector.observeComparison(detector.compareCandidate(reference), 3_200L).transition)
        assertTrue(detector.shouldAttemptAlignment(3_200L))
        detector.manualClose()
        detector.observeComparison(null, 3_216L)
        assertTrue(detector.manualSuppressed)
        assertEquals(AutoMapOpenTransition.CLOSED, detector.observeComparison(detector.compareCandidate(absent), 3_232L).transition)
        assertEquals(AutoMapOpenTransition.OPENED, detector.observeComparison(detector.compareCandidate(reference), 3_248L).transition)
        assertTrue(detector.shouldAttemptAlignment(3_248L))
    }

    private val referencePixels = IntArray(AutoMapOpenDetector.PIXELS) { i ->
        val x = i % AutoMapOpenDetector.WIDTH
        val y = i / AutoMapOpenDetector.WIDTH
        rgb(72 + ((x / 4 + y / 4) % 4) * 32,
            80 + ((x / 4 * 3 + y / 4) % 4) * 26,
            90 + ((x / 4 + y / 4 * 3) % 4) * 24)
    }
    private val reference = AutoMapOpenDetector.signature(referencePixels)
    private val closed = AutoMapOpenDetector.signature(IntArray(AutoMapOpenDetector.PIXELS) { rgb(104, 117, 128) })
    private val middle = AutoMapOpenDetector.signature(referencePixels.map { pixel ->
        fun fade(channel: Int) = (.6 * channel + .4 * 104).roundToInt()
        rgb(fade(pixel ushr 16 and 255), fade(pixel ushr 8 and 255), fade(pixel and 255))
    }.toIntArray())
    private fun rgb(r: Int, g: Int, b: Int) = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    private fun detector(config: AutoMapOpenConfig = AutoMapOpenConfig()) = AutoMapOpenDetector(reference, config)
    private fun open(detector: AutoMapOpenDetector, time: Long = 0L) {
        assertEquals(AutoMapOpenTransition.NONE, detector.observe(reference, time).transition)
        assertEquals(AutoMapOpenTransition.OPENED, detector.observe(reference, time + 250L).transition)
    }
    private fun close(detector: AutoMapOpenDetector, time: Long = 1_000L) {
        assertEquals(AutoMapOpenTransition.NONE, detector.observe(closed, time).transition)
        assertEquals(AutoMapOpenTransition.CLOSED, detector.observe(closed, time + 250L).transition)
    }

    @Test fun identicalSpatialReferencePassesAndPureReferencesAreRejected() {
        assertEquals(1.0, AutoMapOpenDetector.compare(reference, reference).score, .00001)
        assertTrue(AutoMapOpenDetector.isUsableReference(reference))
        assertFalse(AutoMapOpenDetector.isUsableReference(closed))
        try { AutoMapOpenDetector(closed); fail("uniform reference must not activate detection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun sameColorHistogramWithShuffledSpatialArrangementIsClosed() {
        // 181 is coprime to 768: the array has exactly the same pixels, each used once.
        val shuffledPixels = IntArray(referencePixels.size) { referencePixels[(it * 181 + 101) % referencePixels.size] }
        assertArrayEquals(referencePixels.sorted().toIntArray(), shuffledPixels.sorted().toIntArray())
        val shuffled = AutoMapOpenDetector.signature(shuffledPixels)
        assertTrue(AutoMapOpenDetector.compare(reference, shuffled).score < .72)
        val detector = detector()
        repeat(8) { detector.observe(shuffled, it * 250L) }
        assertFalse(detector.isOpen)
    }

    @Test fun SmallBrightnessChangeAndOneCellMovementRemainOpen() {
        val brighter = AutoMapOpenDetector.signature(referencePixels.map { pixel ->
            rgb((pixel ushr 16 and 255) + 12, (pixel ushr 8 and 255) + 12, (pixel and 255) + 12)
        }.toIntArray())
        assertTrue(AutoMapOpenDetector.compare(reference, brighter).score >= .85)
        val shifted = AutoMapOpenDetector.signature(IntArray(referencePixels.size) { i ->
            val x = i % AutoMapOpenDetector.WIDTH
            val y = i / AutoMapOpenDetector.WIDTH
            referencePixels[y * AutoMapOpenDetector.WIDTH + (x - 1).coerceAtLeast(0)]
        })
        val comparison = AutoMapOpenDetector.compare(reference, shifted)
        assertTrue(comparison.score >= .85)
        assertEquals(1, comparison.offsetX)
        assertTrue(comparison.edge >= .85)
    }

    @Test fun LargeFadeAndUniformSceneDoNotMasqueradeAsOpenMap() {
        val black = AutoMapOpenDetector.signature(IntArray(referencePixels.size) { rgb(0, 0, 0) })
        assertTrue(AutoMapOpenDetector.compare(reference, black).score < .72)
        assertTrue(AutoMapOpenDetector.compare(reference, closed).score < .72)
    }

    @Test fun SingleAnimationFrameAndAlternatingFramesNeverOpen() {
        val detector = detector()
        repeat(12) { detector.observe(if (it % 2 == 0) reference else closed, it * 250L) }
        assertFalse(detector.isOpen)
        assertEquals(0L, detector.openCycle)
        open(detector, 4_000L)
        detector.observe(closed, 4_750L)
        assertTrue(detector.isOpen)
        detector.observe(reference, 5_000L)
        assertTrue(detector.isOpen)
    }

    @Test fun HysteresisMiddleRegionDoesNotStartOrStopAndInterruptsDebounce() {
        val middleScore = AutoMapOpenDetector.compare(reference, middle).score
        assertTrue(middleScore > .72 && middleScore < .85)
        val detector = detector()
        detector.observe(reference, 0L)
        detector.observe(middle, 250L)
        detector.observe(reference, 500L)
        assertFalse(detector.isOpen)
        detector.observe(reference, 750L)
        assertTrue(detector.isOpen)
        detector.observe(closed, 1_000L)
        detector.observe(middle, 1_250L)
        detector.observe(closed, 1_500L)
        assertTrue(detector.isOpen)
        assertFalse(detector.shouldAttemptAlignment(1_500L))
        detector.observe(reference, 1_750L)
        assertTrue(detector.shouldAttemptAlignment(1_750L))
    }

    @Test fun UnknownResetsDebounceButDoesNotCloseOrReleaseManualOverride() {
        val detector = detector()
        detector.observe(reference, 0L)
        assertTrue(detector.observe(null, 250L).unknown)
        detector.observe(reference, 500L)
        assertFalse(detector.isOpen)
        detector.observe(reference, 750L)
        assertTrue(detector.isOpen)
        detector.manualClose()
        detector.observe(closed, 1_000L)
        val unknown = detector.observe(null, 1_250L)
        assertEquals(AutoMapOpenTransition.NONE, unknown.transition)
        assertTrue(unknown.isOpen)
        assertTrue(unknown.manualSuppressed)
        assertFalse(detector.shouldAttemptAlignment(1_250L))
        detector.observe(closed, 1_500L)
        assertTrue(detector.isOpen)
        assertEquals(AutoMapOpenTransition.CLOSED, detector.observe(closed, 1_750L).transition)
        assertFalse(detector.manualSuppressed)
    }

    @Test fun RepeatedManualCloseSuppressesEntirePhysicalOpening() {
        val detector = detector()
        open(detector)
        repeat(4) {
            detector.manualClose()
            detector.observe(reference, 500L + it * 250L)
            assertTrue(detector.isOpen)
            assertTrue(detector.manualSuppressed)
            assertNull(detector.alignmentStarted(500L + it * 250L))
        }
        val cycle = detector.openCycle
        close(detector, 2_000L)
        assertFalse(detector.manualSuppressed)
        open(detector, 3_000L)
        assertEquals(cycle + 1L, detector.openCycle)
        assertNotNull(detector.alignmentStarted(3_250L))
    }

    @Test fun ManualCloseBeforeDebouncedOpeningStillRequiresObservedClosure() {
        val detector = detector()
        detector.observe(reference, 0L)
        detector.manualClose()
        detector.observe(reference, 250L)
        detector.observe(reference, 500L)
        assertTrue(detector.isOpen)
        assertTrue(detector.manualSuppressed)
        close(detector, 1_000L)
        open(detector, 2_000L)
        assertFalse(detector.manualSuppressed)
    }

    @Test fun AttemptsAreSingleFlightCooledDownAndCappedPerOpening() {
        val detector = detector(AutoMapOpenConfig(retryCooldownMs = 1_000L))
        open(detector)
        var now = 250L
        repeat(3) { attempt ->
            detector.observe(reference, now)
            val cycle = detector.alignmentStarted(now)
            assertNotNull(cycle)
            assertEquals(attempt + 1, detector.attemptsInCycle)
            assertFalse(detector.shouldAttemptAlignment(now))
            assertNull(detector.alignmentStarted(now))
            assertTrue(detector.alignmentFinished(false, now + 100L, requireNotNull(cycle)))
            detector.observe(reference, now + 1_099L)
            assertFalse(detector.shouldAttemptAlignment(now + 1_099L))
            now += 1_100L
        }
        detector.observe(reference, now)
        assertFalse(detector.shouldAttemptAlignment(now))
        assertNull(detector.alignmentStarted(now))
        close(detector, now + 250L)
        open(detector, now + 1_000L)
        assertEquals(0, detector.attemptsInCycle)
        assertNotNull(detector.alignmentStarted(now + 1_250L))
    }

    @Test fun SuccessDoesNotRecalculateEveryFrame() {
        val detector = detector()
        open(detector)
        val cycle = requireNotNull(detector.alignmentStarted(250L))
        assertTrue(detector.alignmentFinished(true, 500L, cycle))
        repeat(10) {
            val now = 1_500L + it * 250L
            detector.observe(reference, now)
            assertFalse(detector.shouldAttemptAlignment(now))
        }
        assertTrue(detector.alignedInCycle)
        assertEquals(1, detector.attemptsInCycle)
    }

    @Test fun OldCompletionCannotAffectNextCycleOrManualClose() {
        val detector = detector()
        open(detector)
        val oldCycle = requireNotNull(detector.alignmentStarted(250L))
        detector.manualClose()
        assertFalse(detector.alignmentFinished(true, 500L, oldCycle))
        close(detector, 1_000L)
        open(detector, 2_000L)
        val currentCycle = requireNotNull(detector.alignmentStarted(2_250L))
        assertFalse(detector.alignmentFinished(true, 2_500L, oldCycle))
        assertTrue(detector.alignmentInFlight)
        assertFalse(detector.alignedInCycle)
        assertTrue(detector.alignmentFinished(true, 2_500L, currentCycle))
    }

    @Test fun ResetInvalidatesWorkButKeepsCycleTokensMonotonic() {
        val detector = detector()
        open(detector)
        val oldCycle = requireNotNull(detector.alignmentStarted(250L))
        detector.manualClose()
        detector.reset()
        assertFalse(detector.isOpen)
        assertFalse(detector.manualSuppressed)
        assertFalse(detector.alignmentInFlight)
        assertEquals(0, detector.attemptsInCycle)
        assertFalse(detector.shouldAttemptAlignment(500L))
        open(detector, 1_000L)
        val newCycle = requireNotNull(detector.alignmentStarted(1_250L))
        assertTrue(newCycle > oldCycle)
        assertFalse(detector.alignmentFinished(true, 1_500L, oldCycle))
        assertTrue(detector.alignmentFinished(false, 1_500L, newCycle))
    }

    @Test fun StaleFrameOrClockBeforeSampleCannotStartNewComputation() {
        val detector = detector()
        open(detector)
        assertFalse(detector.shouldAttemptAlignment(249L))
        assertFalse(detector.shouldAttemptAlignment(1_251L))
        detector.observe(null, 1_500L)
        assertFalse(detector.shouldAttemptAlignment(1_500L))
        detector.observe(reference, 1_750L)
        assertTrue(detector.shouldAttemptAlignment(1_750L))
    }

    @Test fun interruptionOfSuccessfulDisplayCanResumeOnlyAfterFreshFrame() {
        val detector = detector()
        open(detector)
        val cycle = requireNotNull(detector.alignmentStarted(250L))
        assertTrue(detector.alignmentFinished(true, 500L, cycle))
        detector.alignmentInterrupted()
        assertTrue(detector.isOpen)
        assertEquals(cycle, detector.openCycle)
        assertEquals(1, detector.attemptsInCycle)
        assertFalse(detector.alignedInCycle)
        assertFalse(detector.shouldAttemptAlignment(1_500L))
        detector.observe(reference, 1_500L)
        assertEquals(AutoMapOpenTransition.NONE, detector.observe(reference, 1_500L).transition)
        assertEquals(cycle, detector.alignmentStarted(1_500L))
        assertEquals(2, detector.attemptsInCycle)
    }

    @Test fun interruptionDoesNotRemoveManualSuppressionOrPermitLateCompletion() {
        val detector = detector()
        open(detector)
        val cycle = requireNotNull(detector.alignmentStarted(250L))
        detector.manualClose()
        detector.alignmentInterrupted()
        assertFalse(detector.alignmentFinished(true, 500L, cycle))
        detector.observe(reference, 1_500L)
        assertTrue(detector.manualSuppressed)
        assertFalse(detector.shouldAttemptAlignment(1_500L))
        assertNull(detector.alignmentStarted(1_500L))
        close(detector, 2_000L)
        open(detector, 3_000L)
        assertNotNull(detector.alignmentStarted(3_250L))
    }

    @Test fun interruptionRejectsPendingCompletionAndPreservesRetryCooldown() {
        val detector = detector()
        open(detector)
        val cycle = requireNotNull(detector.alignmentStarted(250L))
        detector.alignmentInterrupted()
        assertFalse(detector.alignmentInFlight)
        assertFalse(detector.alignmentFinished(true, 500L, cycle))
        detector.observe(reference, 1_249L)
        assertFalse(detector.shouldAttemptAlignment(1_249L))
        detector.observe(reference, 1_250L)
        assertEquals(cycle, detector.alignmentStarted(1_250L))
        assertEquals(2, detector.attemptsInCycle)
    }

    @Test fun repeatedPausesCannotResetThePerOpeningAttemptLimit() {
        val detector = detector()
        open(detector)
        repeat(3) { attempt ->
            val now = 250L + attempt * 1_000L
            detector.observe(reference, now)
            assertNotNull(detector.alignmentStarted(now))
            detector.alignmentInterrupted()
            assertEquals(attempt + 1, detector.attemptsInCycle)
        }
        detector.observe(reference, 3_250L)
        assertTrue(detector.isOpen)
        assertFalse(detector.manualSuppressed)
        assertFalse(detector.shouldAttemptAlignment(3_250L))
        assertNull(detector.alignmentStarted(3_250L))
        close(detector, 4_000L)
        open(detector, 5_000L)
        assertEquals(0, detector.attemptsInCycle)
        assertNotNull(detector.alignmentStarted(5_250L))
    }

    @Test fun InvalidSampleSizeAndInvalidConfigFailBeforeDetection() {
        try { AutoMapOpenDetector.signature(IntArray(8)); fail("sample size must be fixed") }
        catch (_: IllegalArgumentException) { }
        try { AutoMapOpenConfig(openThreshold = .7, closeThreshold = .8); fail("invalid hysteresis") }
        catch (_: IllegalArgumentException) { }
        try { AutoMapOpenConfig(maximumAttempts = 0); fail("retry bound must be positive") }
        catch (_: IllegalArgumentException) { }
    }
}


