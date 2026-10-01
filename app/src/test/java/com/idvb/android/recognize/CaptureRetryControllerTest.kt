package com.idvb.android.recognize

import org.junit.Assert.*
import org.junit.Test

class CaptureRetryControllerTest {
    @Test fun closingCancelsRetryEvenIfSchedulerAlreadyDequeuedItAndReopenUsesFreshController() {
        val tasks = mutableListOf<() -> Unit>()
        var removals = 0
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { _, task -> tasks += task; { removals++ } }
        var oldCalls = 0
        val old = CaptureRetryController(schedule)
        assertEquals(80L, old.retry { oldCalls++ })
        old.stop()
        tasks[0]()
        assertEquals(0, oldCalls)
        assertEquals(1, removals)
        assertNull(old.retry { oldCalls++ })
        var newCalls = 0
        val next = CaptureRetryController(schedule)
        assertEquals(80L, next.retry { newCalls++ })
        tasks[1]()
        assertEquals(1, newCalls)
    }
    @Test fun retriesAreBoundedAndDoNotStartAnInfiniteCaptureLoop() {
        val delays = mutableListOf<Long>()
        val retry = CaptureRetryController { delay, _ -> delays += delay; {} }
        repeat(8) { assertNotNull(retry.retry {}) }
        assertNull(retry.retry {})
        assertEquals(listOf(80L, 160L, 320L, 320L, 320L, 320L, 320L, 320L), delays)
    }
}
