package com.idvb.android.alignment

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoMapOpenTimingTest {
    @Test fun captureAndProcessingDoNotAddAnotherFullPollingInterval() {
        assertEquals(155L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_025L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_180L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_500L))
    }

    @Test fun burstIntervalKeepsTheSameBoundAcrossRepeatedSamples() {
        var now = 0L
        repeat(20) {
            val began = now
            now += 25L
            now += AutoMapOpenTiming.delayAfterSample(began, now)
            assertEquals(began + 180L, now)
        }
    }

    @Test fun clockBeforeRequestDoesNotCauseANegativeOrUnboundedDelay() {
        assertEquals(180L, AutoMapOpenTiming.delayAfterSample(1_000L, 900L))
        assertEquals(180L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_000L))
    }
}
