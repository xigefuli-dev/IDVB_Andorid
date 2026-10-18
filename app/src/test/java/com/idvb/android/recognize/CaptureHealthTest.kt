package com.idvb.android.recognize

import com.idvb.android.data.ScreenCaptureMethod
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CaptureHealthTest {
    private val source = ScreenCaptureMethod.MEDIA_PROJECTION

    @Test fun startupCannotClaimSuccessFromASwitchOrNewGrant() {
        val machine = CaptureHealthMachine(source)
        assertEquals(CaptureAvailability.UNKNOWN, machine.snapshot.availability)
        machine.record(machine.ticket(), 10, CaptureException(CaptureFailure.NO_GRANT, "授权已失效"))
        assertEquals(CaptureAvailability.UNAVAILABLE, machine.snapshot.availability)
        assertNull(machine.snapshot.lastSuccessElapsedMs)
        machine.beginSession()
        assertEquals(CaptureAvailability.UNKNOWN, machine.snapshot.availability)
        machine.record(machine.ticket(), 20, null)
        assertEquals(CaptureAvailability.HEALTHY, machine.snapshot.availability)
        assertEquals(20L, machine.snapshot.lastSuccessElapsedMs)
    }

    @Test fun failureKeepsActualSuccessTimeAndRecoveryKeepsFailureEvidence() {
        val machine = CaptureHealthMachine(source)
        machine.record(machine.ticket(), 10, null)
        machine.record(machine.ticket(), 20, CaptureException(CaptureFailure.BLACK_FRAME, "黑屏"))
        assertEquals(10L, machine.snapshot.lastSuccessElapsedMs)
        assertEquals(CaptureFailure.BLACK_FRAME, machine.snapshot.lastFailure)
        machine.record(machine.ticket(), 30, null)
        assertEquals(CaptureAvailability.HEALTHY, machine.snapshot.availability)
        assertEquals(30L, machine.snapshot.lastSuccessElapsedMs)
        assertEquals(20L, machine.snapshot.lastFailureElapsedMs)
    }

    @Test fun stoppedProjectionRejectsLateSuccessAndNewSessionRejectsOldFailure() {
        val machine = CaptureHealthMachine(source)
        val inFlight = machine.ticket()
        machine.beginSession()
        machine.record(machine.ticket(), 10, CaptureException(CaptureFailure.SYSTEM_STOPPED, "已停止"))
        assertFalse(machine.record(inFlight, 11, null))
        assertEquals(CaptureAvailability.UNAVAILABLE, machine.snapshot.availability)
        val old = machine.ticket()
        machine.beginSession()
        machine.record(machine.ticket(), 20, null)
        assertFalse(machine.record(old, 21, CaptureException(CaptureFailure.TIMEOUT, "旧请求超时")))
        assertEquals(CaptureAvailability.HEALTHY, machine.snapshot.availability)
    }

    @Test fun cancellationAndOutOfOrderCompletionsDoNotReplaceNewEvidence() {
        val machine = CaptureHealthMachine(source)
        val older = machine.ticket(); val newer = machine.ticket()
        machine.record(newer, 20, null)
        assertFalse(machine.record(older, 30, CaptureException(CaptureFailure.TIMEOUT, "超时")))
        assertFalse(machine.record(machine.ticket(), 40, CancellationException()))
        assertEquals(20L, machine.snapshot.lastSuccessElapsedMs)
        assertEquals(CaptureAvailability.HEALTHY, machine.snapshot.availability)
    }

    @Test fun failuresRetainTypedReasonAndRawAccessibilityCode() {
        val machine = CaptureHealthMachine(ScreenCaptureMethod.ACCESSIBILITY)
        for (code in 1..6) {
            val failure = accessibilityCaptureException(code)
            machine.record(machine.ticket(), code.toLong(), failure)
            assertEquals(code, machine.snapshot.lastErrorCode)
            assertEquals(CaptureFailure.REJECTED, machine.snapshot.lastFailure)
            assertTrue(failure.message!!.contains("错误码 $code"))
        }
        assertTrue(accessibilityCaptureException(6).message!!.contains("安全窗口"))
        assertTrue(accessibilityCaptureException(2).message!!.contains("无障碍访问"))
        assertTrue(accessibilityCaptureException(99).message!!.contains("未知错误"))
    }

    @Test fun captureSourcesHaveIndependentEvidence() {
        val projection = CaptureHealthMachine(source)
        val accessibility = CaptureHealthMachine(ScreenCaptureMethod.ACCESSIBILITY)
        projection.record(projection.ticket(), 10, CaptureException(CaptureFailure.NO_GRANT, "失效"))
        accessibility.record(accessibility.ticket(), 20, null)
        assertEquals(CaptureAvailability.UNAVAILABLE, projection.snapshot.availability)
        assertEquals(CaptureAvailability.HEALTHY, accessibility.snapshot.availability)
    }

    @Test fun warningIsOncePerOutageAndOnlyRealSuccessResetsIt() {
        val warnings = CaptureHealthWarnings()
        val failed = CaptureHealthSnapshot(source, CaptureAvailability.UNAVAILABLE)
        assertTrue(warnings.shouldWarn(failed))
        assertFalse(warnings.shouldWarn(failed))
        assertFalse(warnings.shouldWarn(failed.copy(availability = CaptureAvailability.UNKNOWN)))
        assertFalse(warnings.shouldWarn(failed.copy(lastFailure = CaptureFailure.TIMEOUT)))
        assertTrue(warnings.shouldWarn(failed.copy(source = ScreenCaptureMethod.ACCESSIBILITY)))
        assertFalse(warnings.shouldWarn(failed.copy(availability = CaptureAvailability.HEALTHY)))
        assertTrue(warnings.shouldWarn(failed))
    }
}
