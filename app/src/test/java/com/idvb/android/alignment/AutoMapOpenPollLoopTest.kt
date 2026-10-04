package com.idvb.android.alignment

import com.idvb.android.recognize.ForegroundWindowPolicy
import org.junit.Assert.*
import org.junit.Test

class AutoMapOpenPollLoopTest {
    private class Fixture {
        var consented = true
        var enabled = true
        var visible = true
        var hostForeground = false
        var inFlight = false
        var calls = 0
        var action: () -> Unit = {}
        val queued = LinkedHashMap<Runnable, Long>()
        val loop = AutoMapOpenPollLoop(
            post = { task, delay -> queued[task] = delay }, remove = { queued.remove(it); Unit },
            canSchedule = { consented && enabled },
            canRecover = { consented && enabled && visible && !hostForeground },
            sampleInFlight = { inFlight }, poll = { calls++; action() },
        )
        fun tick() {
            val task = queued.keys.single()
            queued.remove(task)
            task.run()
        }
    }

    @Test fun startupInPortraitThenLandscapeRecoversWithoutAnotherForegroundEvent() {
        val f = Fixture()
        var landscape = false
        var captures = 0
        f.action = { if (landscape) captures++ }
        f.loop.schedule(0L)
        repeat(3) { f.tick(); assertTrue(f.loop.pending) }
        assertEquals(0, captures)
        landscape = true
        f.tick()
        assertEquals(1, captures)
        assertEquals(1, f.queued.size)
    }

    @Test fun firstAutomaticSelectionAndVolumeWindowContinueSamplingWithoutSettingsOrEye() {
        val f = Fixture()
        var foreground = "com.netease.dwrg"
        var selected = false
        var captures = 0
        f.action = { if (selected && foreground == "com.netease.dwrg") captures++ }
        f.loop.schedule(0L)
        f.tick()
        selected = true
        f.loop.schedule(0L) // The selection commit is an explicit wakeup.
        if (ForegroundWindowPolicy.ignoredWindowReason("com.android.systemui",
                "com.android.systemui.volume.VolumeDialogImpl\$CustomDialog") == null) {
            foreground = "com.android.systemui"
        }
        // The recorded failure has no volume-dismissal or new game foreground event.
        repeat(5) { f.tick() }
        assertEquals(5, captures)
        assertTrue(f.loop.pending)
    }

    @Test fun captureAvailabilityRecoversWithoutSettingsToggle() {
        val f = Fixture()
        var available = false
        var captures = 0
        f.action = { if (available) captures++ }
        f.loop.schedule(0L)
        f.tick()
        assertEquals(350L, f.queued.values.single())
        available = true
        f.tick()
        assertEquals(1, captures)
    }

    @Test fun asynchronousCaptureOwnsTheSuccessorAndDoesNotStartADuplicatePoll() {
        val f = Fixture()
        f.action = { f.inFlight = true }
        f.loop.schedule(0L)
        f.tick()
        assertFalse(f.loop.pending)
        assertTrue(f.queued.isEmpty())
        f.inFlight = false
        f.loop.schedule(117L)
        assertEquals(117L, f.queued.values.single())
    }

    @Test fun explicitSampleDelayIsPreservedAndRepeatedWakeupsHaveOneQueuedPoll() {
        val f = Fixture()
        f.action = { f.loop.schedule(91L) }
        repeat(10) { f.loop.schedule(0L) }
        assertEquals(1, f.queued.size)
        f.tick()
        assertEquals(91L, f.queued.values.single())
        assertTrue(f.loop.pending)
    }

    @Test fun hostAndHiddenOverlayStopRecoveryUntilTheirExplicitResume() {
        val f = Fixture()
        f.hostForeground = true
        f.loop.schedule(0L)
        f.tick()
        assertFalse(f.loop.pending)
        f.hostForeground = false
        f.loop.schedule(0L)
        f.visible = false
        f.tick()
        assertFalse(f.loop.pending)
        f.visible = true
        f.loop.schedule(0L)
        f.tick()
        assertTrue(f.loop.pending)
    }

    @Test fun unacceptedConsentAndDisabledSettingNeverQueuePollsOrRecovery() {
        val f = Fixture()
        f.consented = false
        f.loop.schedule(0L)
        assertTrue(f.queued.isEmpty())
        assertEquals(0, f.calls)
        f.consented = true
        f.loop.schedule(0L)
        f.action = { f.enabled = false }
        f.tick()
        assertFalse(f.loop.pending)
        f.loop.schedule(0L)
        assertTrue(f.queued.isEmpty())
    }

    @Test fun stopCancelsTheQueuedPoll() {
        val f = Fixture()
        f.loop.schedule(0L)
        f.loop.stop()
        assertFalse(f.loop.pending)
        assertTrue(f.queued.isEmpty())
        assertEquals(0, f.calls)
    }
}
