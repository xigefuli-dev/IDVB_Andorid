package com.idvb.android.alignment

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.MainActivity
import com.idvb.android.UsageConsent
import com.idvb.android.overlay.GuideMapView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Saved input -> complete calculation -> attached hardware-rendered window frame commit.
 * Does not simulate capture, claim game acceptance, or modify the installed main app. */
class AlignmentWindowBudgetInstrumentedTest {
    @Test fun originalInputsReachTheWindowFrameCommitWithin270Milliseconds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.idvb.android.verification") { "Use the authorized independent verification app" }
        val root = File(context.cacheDir, requireNotNull(InstrumentationRegistry.getArguments().getString("alignmentCorpus"))).canonicalFile
        require(root.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val rows = mutableListOf<String>(); val failures = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (!UsageConsent.isAccepted(context)) {
                Thread.sleep(5300)
                onView(withText(UsageConsent.CONFIRM)).inRoot(isDialog()).perform(click())
            }
            assertTrue(UsageConsent.isAccepted(context))
            lateinit var view: GuideMapView
            scenario.onActivity { activity -> view = GuideMapView(activity); activity.setContentView(view) }
            instrumentation.waitForIdleSync()
            assertTrue("Hardware window required", view.isHardwareAccelerated)
            for (archive in root.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name })
                AlignmentPackageReplay.open(context, archive).use { replay ->
                    replay.prepare()
                    val bitmap = ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry("reference.png")).use { requireNotNull(BitmapFactory.decodeStream(it)) } }
                    try {
                        instrumentation.runOnMainSync { view.showBitmap(bitmap) }
                        val began = System.nanoTime()
                        val report = replay.run()
                        val computed = System.nanoTime()
                        val fit = report.result as? AlignmentResult.Aligned
                        assertNotNull("${archive.name} must align", fit)
                        val latch = CountDownLatch(1)
                        var committed = 0L
                        instrumentation.runOnMainSync {
                            view.viewTreeObserver.registerFrameCommitCallback { committed = System.nanoTime(); latch.countDown() }
                            val bounds = fit!!.transform.bounds; val viewport = replay.testCase.request.viewport
                            view.showAlignment(RectF(bounds.x.toFloat(), bounds.y.toFloat(), (bounds.x + bounds.width).toFloat(), (bounds.y + bounds.height).toFloat()),
                                RectF(viewport.x.toFloat(), viewport.y.toFloat(), (viewport.x + viewport.width).toFloat(), (viewport.y + viewport.height).toFloat()))
                        }
                        assertTrue("Window must commit a frame", latch.await(5, TimeUnit.SECONDS))
                        val total = (committed - began) / 1e6
                        rows += "${archive.name} computeMs=${(computed - began) / 1e6} computeAndWindowCommitMs=$total"
                        if (total > AlignmentPerformanceBudget.FIRST_DRAW_MS) failures += rows.last()
                    } finally {
                        instrumentation.runOnMainSync { view.clearAlignment(); view.showBitmap(null) }
                        bitmap.recycle()
                    }
                }
        }
        File(context.filesDir, "test-evidence/window-budgets.txt").apply { parentFile!!.mkdirs() }.writeText(rows.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
