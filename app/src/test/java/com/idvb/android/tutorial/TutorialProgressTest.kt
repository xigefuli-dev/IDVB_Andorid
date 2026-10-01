package com.idvb.android.tutorial

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TutorialProgressTest {
    @Test fun `missing accessibility cannot be treated as a projection grant pending start`() {
        val p = com.idvb.android.ui.PermissionSnapshot(true, false, true, true, true, true,
            com.idvb.android.data.ScreenCaptureMethod.ACCESSIBILITY)
        assertFalse(p.readyToStart)
        assertTrue(p.copy(captureMethod = com.idvb.android.data.ScreenCaptureMethod.MEDIA_PROJECTION).readyToStart)
        assertFalse(p.copy(overlay = false).readyToStart)
        assertTrue(p.copy(screenCapture = true).readyToStart)
    }
    @Test fun `reading or checking cannot substitute for real actions`() {
        for (step in TutorialStep.entries.filter { it != TutorialStep.DONE }) {
            assertFalse(step.name, TutorialProgress(step = step).canPass())
        }
        assertFalse(TutorialProgress(step = TutorialStep.IMPORT, downloaded = true).canPass())
        assertTrue(TutorialProgress(step = TutorialStep.IMPORT).canPass(hasMaps = true))
        assertFalse(TutorialProgress(step = TutorialStep.PERMISSIONS).canPass(hasMaps = true))
        assertTrue(TutorialProgress(step = TutorialStep.PERMISSIONS).canPass(permissionsReady = true))
        assertFalse(TutorialProgress(step = TutorialStep.START).canPass(permissionsReady = true))
        assertFalse(TutorialProgress(step = TutorialStep.START, serviceStarted = true).canPass())
        assertTrue(TutorialProgress(step = TutorialStep.START).canPass(overlayVisible = true))
    }

    @Test fun `skipping supplies sample prerequisites but does not pass an exercise`() {
        var p = TutorialProgress()
        repeat(TutorialStep.DONE.ordinal) {
            val previous = p.step
            p = p.advance(skip = true)
            assertTrue(previous in p.skipped)
            assertTrue(p.passed.isEmpty())
            assertTrue(p.performed.isEmpty())
            if (p.step != TutorialStep.DONE) assertFalse(p.canPass())
        }
        assertEquals(TutorialStep.DONE, p.step)
        assertEquals(p, p.advance())
    }

    @Test fun `restoring checkpoint retains unfinished calibration and adjustment`() {
        val p = TutorialProgress(step = TutorialStep.ADJUST, practiceOpen = true,
            passed = setOf(TutorialStep.SCAN), skipped = setOf(TutorialStep.CALIBRATE),
            performed = setOf(TutorialStep.SELECT),
            practice = PracticeState(scene = "game", mode = "adjust", selected = true, visible = true,
                rect = listOf(.32f,.17f,.84f,.82f), x = .12f, scale = 1.4f, opacity = .4f, changed = true))
        assertEquals(p, Json.decodeFromString<TutorialProgress>(Json.encodeToString(p)))
    }

    @Test fun `calibration rejects a room the whole screen reversed and non finite bounds`() {
        assertTrue(isPracticeCalibrationValid(listOf(.325f,.17f,.84f,.82f)))
        assertTrue(isPracticeCalibrationValid(listOf(.34f,.19f,.82f,.8f)))
        for (r in listOf(emptyList(), listOf(0f,0f,1f,1f), listOf(.4f,.3f,.5f,.4f),
            listOf(.84f,.82f,.325f,.17f), listOf(Float.NaN,.17f,.84f,.82f))) {
            assertFalse(r.toString(), isPracticeCalibrationValid(r))
        }
    }

    @Test fun `show exercise requires map still visible`() {
        val p = TutorialProgress(step = TutorialStep.SHOW, performed = setOf(TutorialStep.SHOW))
        assertFalse(p.canPass())
        assertTrue(p.copy(practice = p.practice.copy(visible = true)).canPass())
    }

    @Test fun `calibrate title and assist touch copy are updated`() {
        assertEquals("首次使用先校准地图", TutorialStep.CALIBRATE.title)
        assertFalse(TutorialStep.ASSIST_TOUCH.instructions.contains("白条仅作装饰"))
    }

    @Test fun `assist touch validation checks corner placements near center with reasonable tolerance`() {
        val exact = listOf(0.077f, 0.265f, 0.938f, 0.253f)
        assertTrue(isPracticeAssistTouchValid(exact))
        val swapped = listOf(0.938f, 0.253f, 0.077f, 0.265f)
        assertTrue(isPracticeAssistTouchValid(swapped))

        val withinTolerance = listOf(0.12f, 0.29f, 0.90f, 0.22f)
        assertTrue(isPracticeAssistTouchValid(withinTolerance))

        val outsideTolerance = listOf(0.5f, 0.5f, 0.938f, 0.253f)
        assertFalse(isPracticeAssistTouchValid(outsideTolerance))

        val invalidCount = listOf(0.077f, 0.265f)
        assertFalse(isPracticeAssistTouchValid(invalidCount))

        val p = TutorialProgress(step = TutorialStep.ASSIST_TOUCH, performed = setOf(TutorialStep.ASSIST_TOUCH))
        assertFalse(p.canPass())
        assertTrue(p.copy(practice = p.practice.copy(assistPoints = exact)).canPass())
        assertFalse(p.copy(practice = p.practice.copy(assistPoints = outsideTolerance)).canPass())
    }
}
