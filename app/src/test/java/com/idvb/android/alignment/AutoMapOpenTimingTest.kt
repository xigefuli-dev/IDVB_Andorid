package com.idvb.android.alignment

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoMapOpenTimingTest {
    @Test fun captureAndProcessingDoNotAddAnotherFullPollingInterval() {
        assertEquals(6L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_010L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_016L))
        assertEquals(0L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_500L))
    }

    @Test fun accessibilityUsesItsOwn350msIntervalInsteadOfProjectionTiming() {
        var now = 0L
        repeat(20) {
            val began = now
            now += 25L
            now += AutoMapOpenTiming.delayAfterSample(began, now, 350L)
            assertEquals(began + 350L, now)
        }
    }

    @Test fun clockBeforeRequestDoesNotCauseANegativeOrUnboundedDelay() {
        assertEquals(16L, AutoMapOpenTiming.delayAfterSample(1_000L, 900L))
        assertEquals(16L, AutoMapOpenTiming.delayAfterSample(1_000L, 1_000L))
    }
}
