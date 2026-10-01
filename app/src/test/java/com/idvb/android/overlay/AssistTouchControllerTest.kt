package com.idvb.android.overlay

import org.junit.Assert.*
import org.junit.Test

class AssistTouchControllerTest {
    private val clicks = mutableListOf<Boolean>()
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    private var shown = 0
    private var hidden = 0
    private var errors = 0
    private fun controller() = AssistTouchController(
        { open, done -> clicks += open; callbacks += done }, { shown++ }, { hidden++ }, { errors++ })

    @Test fun waitsForOpenBeforeShowingAndClosesOnSecondTap() {
        val c = controller()
        c.toggle(); assertEquals(0, shown)
        callbacks[0](true); assertEquals(1, shown)
        c.toggle(); assertEquals(listOf(true, false), clicks); assertEquals(1, hidden)
        callbacks[1](true); assertFalse(c.active); assertFalse(c.busy)
    }
    @Test fun rapidSecondTapQueuesCloseAndNeverShows() {
        val c = controller()
        c.toggle(); c.toggle(); c.toggle()
        assertEquals(listOf(true), clicks)
        callbacks[0](true)
        assertEquals(listOf(true, false), clicks); assertEquals(0, shown)
    }
    @Test fun failedOpenDoesNotShowOrIssueClose() {
        val c = controller()
        c.toggle(); callbacks[0](false)
        assertEquals(0, shown); assertEquals(1, errors); assertFalse(c.active)
        assertEquals(listOf(true), clicks)
    }
    @Test fun lateCallbackAfterExitCannotShowOrClickAgain() {
        val c = controller()
        c.toggle(); c.cancel(); callbacks[0](true)
        assertEquals(0, shown); assertEquals(listOf(true), clicks); assertFalse(c.active)
        c.toggle(); callbacks[1](true); assertEquals(1, shown)
    }
}
