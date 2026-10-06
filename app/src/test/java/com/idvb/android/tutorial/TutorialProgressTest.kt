package com.idvb.android.tutorial

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TutorialProgressTest {
    @Test fun `battery exemption or skipping cannot finish tutorial or unlock start`() {
        val base = com.idvb.android.ui.PermissionSnapshot(true, true, true, true, true, false,
            com.idvb.android.data.ScreenCaptureMethod.ACCESSIBILITY)
        for (snapshot in listOf(base.copy(batteryOptimization = true), base.copy(batteryOptimizationSkipped = true))) {
            assertTrue(snapshot.readyToStart)
            for (step in TutorialStep.activeSteps) {
                val progress = TutorialProgress(step = step, serviceStarted = true)
                assertFalse(progress.completed)
                if (step == TutorialStep.START) {
                    assertFalse(progress.canPass(permissionsReady = snapshot.readyToStart))
                    val practiced = progress.copy(startButtonPracticed = true)
                    assertTrue(practiced.canPass(permissionsReady = snapshot.readyToStart))
                    assertFalse(practiced.completed)
                }
            }
        }
    }
    @Test fun `nine questions retain the requested correct answers and replace single question completion`() {
        assertEquals(9, TutorialQuiz.questions.size)
        assertEquals(listOf("一楼大门", "一楼侧门", "等待队友的大厅（有镜子那个）",
            "从一楼大门进入，探索一定程度然后打开地图点🔍按钮",
            "扫描地图、选择与显示地图", "群文件和软件内订阅",
            "点首页右下角的箭头按钮或者启动按钮", "不可以", "去 设置 - 预设 切换成传统小抄"),
            TutorialQuiz.questions.map { it.options[it.correct] })
        val old = TutorialProgress(step = TutorialStep.DONE, quizAnswered = 1, completionRevision = 1,
            passed = TutorialStep.activeSteps.filter { it != TutorialStep.DONE }.toSet())
        assertFalse(old.completed)
        val restored = old.requiredCheckpoint()
        assertEquals(TutorialStep.DONE, restored.step)
        assertEquals(0, restored.quizAnswered)
        assertTrue(restored.active)
        assertEquals(old.passed, restored.passed)
    }
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
        assertFalse(TutorialProgress(step = TutorialStep.START, startButtonPracticed = true).canPass())
        assertTrue(TutorialProgress(step = TutorialStep.START, startButtonPracticed = true).canPass(permissionsReady = true))
    }

    @Test fun `skipping stages preserves skip history and still requires the full quiz`() {
        var progress = TutorialProgress(active = true)
        while (progress.step != TutorialStep.DONE) {
            val stage = progress.step
            progress = progress.advance(skip = true)
            assertTrue(stage in progress.skipped)
            assertTrue(progress.passed.isEmpty())
            assertTrue(progress.performed.isEmpty())
            assertFalse(progress.completed)
            assertEquals(progress, progress.requiredCheckpoint())
        }
        assertTrue(progress.stagesFinished)
        assertEquals(progress, progress.advance(skip = true))
        assertEquals(progress, progress.answerQuiz(TutorialQuiz.questions.first().correct))
        progress = progress.finishPractice()
        assertFalse(progress.completed)
        for (question in TutorialQuiz.questions) {
            assertFalse(progress.completed)
            progress = progress.answerQuiz(question.correct)
        }
        assertTrue(progress.completed)
        assertEquals(progress, progress.requiredCheckpoint())
        val wrong = progress.copy(quizAnswered = 0, completionRevision = 0, active = true).answerQuiz(0)
        assertEquals(TutorialStep.DOWNLOAD, wrong.step)
        assertTrue(wrong.skipped.isEmpty())
        assertFalse(wrong.practiceFinished)
    }

    @Test fun `legacy skipped stages are retained without bypassing quiz`() {
        assertFalse(TutorialProgress(step = TutorialStep.DONE).completed)
        val legacy = TutorialProgress(step = TutorialStep.DONE,
            passed = TutorialStep.activeSteps.filter { it != TutorialStep.DONE && it != TutorialStep.CALIBRATE }.toSet(),
            skipped = setOf(TutorialStep.CALIBRATE))
        val restored = legacy.requiredCheckpoint()
        assertEquals(TutorialStep.DONE, restored.step)
        assertTrue(restored.active)
        assertEquals(legacy.skipped, restored.skipped)
        assertFalse(restored.completed)
        assertFalse(restored.practiceFinished)
        val unfinished = legacy.copy(passed = legacy.passed - TutorialStep.SWITCH).requiredCheckpoint()
        assertEquals(TutorialStep.SWITCH, unfinished.step)
        assertEquals(legacy.skipped, unfinished.skipped)
    }
    @Test fun `all quiz answers and exercises are required and wrong answer restarts everything`() {
        val passed = TutorialStep.activeSteps.filter { it != TutorialStep.DONE }.toSet()
        var progress = TutorialProgress(step = TutorialStep.DONE, active = true, passed = passed).finishPractice()
        assertEquals(progress, progress.answerQuiz(-1))
        for (question in TutorialQuiz.questions) {
            assertFalse(progress.completed)
            question.options.indices.firstOrNull { it != question.correct }?.let { wrong ->
                val reset = progress.answerQuiz(wrong)
                assertEquals(TutorialStep.DOWNLOAD, reset.step)
                assertEquals(0, reset.quizAnswered)
                assertTrue(reset.passed.isEmpty())
                assertTrue(reset.active)
            }
            progress = progress.answerQuiz(question.correct)
            assertEquals(progress, Json.decodeFromString<TutorialProgress>(Json.encodeToString(progress)))
        }
        assertTrue(progress.completed)
        assertEquals(progress, progress.requiredCheckpoint())
        assertFalse(progress.copy(passed = passed - TutorialStep.SCAN).completed)
        assertEquals(TutorialProgress(step = TutorialStep.DONE), TutorialProgress(step = TutorialStep.DONE).answerQuiz(2))
    }
    @Test fun `quiz starts only after explicitly finishing every practice step`() {
        val passed = TutorialStep.activeSteps.filter { it != TutorialStep.DONE }.toSet()
        val ending = TutorialProgress(step = TutorialStep.DONE, passed = passed)
        assertFalse(ending.practiceFinished)
        assertEquals(ending, ending.answerQuiz(TutorialQuiz.questions.first().correct))
        val finished = ending.finishPractice()
        assertTrue(finished.practiceFinished)
        assertEquals(1, finished.answerQuiz(TutorialQuiz.questions.first().correct).quizAnswered)
        val unfinished = ending.copy(passed = passed - TutorialStep.SWITCH)
        assertEquals(unfinished, unfinished.finishPractice())
        assertFalse(finished.answerQuiz(0).practiceFinished)
    }
    @Test fun `assist touch step is deregistered from active tutorial flow`() {
        assertFalse(TutorialStep.activeSteps.contains(TutorialStep.ASSIST_TOUCH))
        val calibrateProgress = TutorialProgress(step = TutorialStep.CALIBRATE)
        assertEquals(TutorialStep.ENTER, calibrateProgress.advance().step)
        val legacyAssistProgress = TutorialProgress(step = TutorialStep.ASSIST_TOUCH)
        assertEquals(TutorialStep.ENTER, legacyAssistProgress.advance().step)
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
