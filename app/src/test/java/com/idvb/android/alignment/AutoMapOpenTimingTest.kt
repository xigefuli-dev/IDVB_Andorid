package com.idvb.android.alignment

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoMapOpenTimingTest {
    @Test fun captureAndProcessingDoNotAddAnotherFullPollingInterval() {
        assertEquals(325L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_025L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_350L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_900L))
    }

    @Test fun twoFrameConfirmationKeepsTheSameBoundAcrossRepeatedOpenCloseCycles() {
        var now = 0L
        repeat(20) {
            val began = now
            now += 25L
            now += AutoMapOpenTiming.delayAfterSample(began, now)
            assertEquals(began + 350L, now)
        }
    }

    @Test fun clockBeforeRequestDoesNotCauseANegativeOrUnboundedDelay() {
        assertEquals(350L, AutoMapOpenTiming.delayAfterSample(1_000L, 900L))
        assertEquals(350L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_000L))
    }
}
