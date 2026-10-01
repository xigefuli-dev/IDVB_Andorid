package com.idvb.android.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.UsageConsent
import com.idvb.android.data.MapTemplate
import com.idvb.android.data.TemplateFloor
import com.idvb.android.idvm.ClassRecord
import com.idvb.android.tutorial.TutorialProgress
import com.idvb.android.tutorial.TutorialStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val consentPrefs get() = context.getSharedPreferences("mandatory_usage_consent", Context.MODE_PRIVATE)
    private val fixtureClassId = "navigation-test-${UUID.randomUUID()}"
    private var acceptedRevision: Int? = null
    private lateinit var tutorialProgress: TutorialProgress
    private var selectedClassId: String? = null
    private var previousThickness = 1
    private var previousShowRoutes = true
    private var fixtureMapId: String? = null

    @Before fun setUp() {
        acceptedRevision = if (consentPrefs.contains("accepted_revision")) consentPrefs.getInt("accepted_revision", 0) else null
        assertTrue(consentPrefs.edit().putInt("accepted_revision", UsageConsent.REVISION).commit())
        val store = TutorialStore.get(context)
        tutorialProgress = store.state.value
        store.update { it.copy(active = false, practiceOpen = false) }
        selectedClassId = AppServices.prefs.selectedMapClassId
        previousThickness = AppServices.prefs.routeLineThickness
        previousShowRoutes = AppServices.prefs.showRoutes
        AppServices.prefs.showRoutes = true
        AppServices.prefs.routeLineThickness = 1
        val catalog = AppServices.repository.loadCatalog()
        AppServices.repository.saveCatalog(catalog.copy(classes = catalog.classes + ClassRecord(fixtureClassId, "导航回归关卡")))
        AppServices.prefs.selectedMapClassId = fixtureClassId
    }

    @After fun restore() {
        fixtureMapId?.let { id ->
            AppServices.repository.loadCatalog().maps.firstOrNull { it.id == id }?.let(AppServices.repository::deleteMap)
        }
        val catalog = AppServices.repository.loadCatalog()
        AppServices.repository.saveCatalog(catalog.copy(classes = catalog.classes.filterNot { it.id == fixtureClassId }))
        AppServices.prefs.selectedMapClassId = selectedClassId
        AppServices.prefs.routeLineThickness = previousThickness
        AppServices.prefs.showRoutes = previousShowRoutes
        TutorialStore.get(context).update { tutorialProgress }
        val editor = consentPrefs.edit()
        acceptedRevision?.let { editor.putInt("accepted_revision", it) } ?: editor.remove("accepted_revision")
        assertTrue(editor.commit())
    }

    @Test fun settingsChildrenUseSystemAndToolbarUpWithoutReservingNavigationHeight() {
        launchPortrait().use { scenario ->
            clickLabel("设置")
            for (label in listOf("通用", "视觉", "操作", "模板")) {
                android.util.Log.i("NavigationRegression", "Checking settings child=$label")
                clickLabel(label)
                waitForLabel("返回")
                assertNavigationAbsent()
                if (label != "模板") assertContentReachesSystemNavigation(scenario)
                systemBack(scenario)
                scrollToLabel("功能设置")
                waitForLabel("首页")
                scenario.onActivity { assertFalse(it.isFinishing) }
                clickLabel(label)
                waitForLabel("返回")
                clickLabel("返回")
                scrollToLabel("功能设置")
            }
        }
    }

    @Test fun rootTabsRequireExplicitExitConfirmation() {
        launchPortrait().use { scenario ->
            for (label in listOf("首页", "列表", "订阅", "设置")) {
                clickLabel(label)
                systemBack(scenario)
                waitForLabel("确认退出")
                clickLabel("取消")
                assertNull(findLabel("确认退出"))
                waitForLabel("首页")
                scenario.onActivity { assertFalse(it.isFinishing) }
                systemBack(scenario)
                waitForLabel("确认退出")
                systemBack(scenario)
                assertNull(findLabel("确认退出"))
                waitForLabel("首页")
            }
            systemBack(scenario)
            waitForLabel("确认退出")
            clickLabel("退出", settle = false)
            waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun settingsNavigationAndScrollingFrameMetrics() {
        launchPortrait().use { scenario ->
            clickLabel("设置")
            clickLabel("视觉")
            systemBack(scenario)
            val phase = AtomicReference("idle")
            val samples = mutableListOf<JSONObject>()
            val worker = HandlerThread("navigation-frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                val currentPhase = phase.get()
                if (currentPhase != "idle") synchronized(samples) {
                    samples += JSONObject().put("phase", currentPhase)
                        .put("totalMs", metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6)
                        .put("layoutMs", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6)
                        .put("drawMs", metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1e6)
                        .put("droppedReports", dropped)
                }
            }
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
            try {
                repeat(4) {
                    phase.set("navigation")
                    clickLabel("视觉")
                    systemBack(scenario)
                    phase.set("scroll")
                    val scrollBounds = Rect().also(waitForNode { it.isScrollable }::getBoundsInScreen)
                    swipe(scrollBounds.exactCenterX(), scrollBounds.top + scrollBounds.height() * .8f,
                        scrollBounds.top + scrollBounds.height() * .25f)
                    swipe(scrollBounds.exactCenterX(), scrollBounds.top + scrollBounds.height() * .25f,
                        scrollBounds.top + scrollBounds.height() * .8f)
                    phase.set("idle")
                }
            } finally {
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                worker.quitSafely()
                worker.join(2000)
            }
            val frames = synchronized(samples) { samples.toList() }
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
            val report = JSONObject().put("versionCode", version).put("frames", JSONArray(frames))
            File(context.filesDir, "test-evidence/ui-frame-timing-$version.json").apply {
                parentFile!!.mkdirs()
                writeText(report.toString())
            }
            for (name in listOf("navigation", "scroll")) {
                val times = frames.filter { it.getString("phase") == name }.map { it.getDouble("totalMs") }.sorted()
                assertTrue("Expected measured $name frames", times.size >= 10)
                fun percentile(fraction: Double) = times[((times.size - 1) * fraction).toInt()]
                android.util.Log.i("NavigationRegression", "Frame timing version=$version phase=$name count=${times.size} " +
                    "p50Ms=${percentile(.5)} p95Ms=${percentile(.95)} maxMs=${times.last()}")
            }
        }
    }

    @Test fun creationBackWalksViaTemplatePickerAndReturnsToMapList() {
        launchPortrait().use { scenario ->
            clickLabel("列表")
            waitForLabel("导航回归关卡")
            clickLabel("更多")
            clickLabel("创建地图")
            waitForLabel("选择模板")
            assertNavigationAbsent()
            clickLabel("常规双层")
            waitForLabel("地图名称")
            systemBack(scenario)
            waitForLabel("选择模板")
            assertNavigationAbsent()
            systemBack(scenario)
            waitForLabel("地图系统")
            waitForLabel("导航回归关卡")
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun childPageTransitionKeepsTheHeaderOnHorizontalPath() {
        launchPortrait().use { scenario ->
            clickLabel("设置")
            clickLabel("视觉")
            val finalBounds = Rect().also(waitForLabel("返回")::getBoundsInScreen)
            val reference = checkNotNull(automation.takeScreenshot())
            val signature = try { headerSignature(reference, finalBounds) } finally { reference.recycle() }
            val frames = mutableListOf<Rect>()
            try {
                systemBack(scenario)
                scrollToLabel("功能设置")
                clickLabel("视觉", settle = false)
                val deadline = SystemClock.uptimeMillis() + 500
                do {
                    val screenshot = checkNotNull(automation.takeScreenshot())
                    try { findHeader(screenshot, finalBounds, signature)?.let(frames::add) }
                    finally { screenshot.recycle() }
                    SystemClock.sleep(16)
                } while (SystemClock.uptimeMillis() < deadline)
            } finally { signature.release() }
            android.util.Log.i("NavigationRegression", "Horizontal transition frames=$frames; final=$finalBounds")
            assertTrue("Expected to observe horizontal movement, frames=$frames", frames.map { it.left }.distinct().size >= 2)
            frames.forEach { frame ->
                assertTrue("The rendered header must not move vertically: $frames",
                    kotlin.math.abs(finalBounds.centerY() - frame.centerY()) <= 2)
            }
        }
    }

    private fun grayscale(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return gray
        } catch (failure: Throwable) { gray.release(); throw failure }
        finally { rgba.release() }
    }

    private fun headerSignature(bitmap: Bitmap, bounds: Rect): Mat {
        val gray = grayscale(bitmap)
        val header = gray.submat(CvRect(bounds.left, bounds.top, bounds.width(), bounds.height()))
        return try { header.clone() } finally { header.release(); gray.release() }
    }

    private fun findHeader(bitmap: Bitmap, expected: Rect, signature: Mat): Rect? {
        val top = (expected.top - expected.height()).coerceAtLeast(0)
        val bottom = (expected.bottom + expected.height()).coerceAtMost(bitmap.height)
        val gray = grayscale(bitmap)
        val strip = gray.submat(CvRect(0, top, bitmap.width, bottom - top))
        val scores = Mat()
        return try {
            Imgproc.matchTemplate(strip, signature, scores, Imgproc.TM_CCOEFF_NORMED)
            val peak = Core.minMaxLoc(scores)
            if (peak.maxVal < .85) null else {
                val x = peak.maxLoc.x.toInt()
                val y = top + peak.maxLoc.y.toInt()
                Rect(x, y, x + expected.width(), y + expected.height())
            }
        } finally { scores.release(); strip.release(); gray.release() }
    }

    @Test fun bottomNavigationSlidesVerticallyAndLeavesChildPageFullHeight() {
        launchPortrait().use { scenario ->
            clickLabel("设置")
            val baseline = Rect().also(waitForLabel("首页")::getBoundsInScreen)
            val screenshot = checkNotNull(automation.takeScreenshot())
            val sampleX = screenshot.width / 2
            val surfaceColor = screenshot.getPixel(sampleX, baseline.centerY())
            val searchTop = (baseline.top - baseline.height() * 3).coerceAtLeast(0)
            val baselineTop = checkNotNull(navigationSurfaceTop(screenshot, sampleX, searchTop, surfaceColor))
            screenshot.recycle()
            clickLabel("视觉", settle = false)
            val exiting = recordNavigationFrames(sampleX, searchTop, surfaceColor)
            waitForLabel("返回")
            assertNavigationAbsent()
            assertContentReachesSystemNavigation(scenario)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            val entering = recordNavigationFrames(sampleX, searchTop, surfaceColor)
            val finalBounds = Rect().also(waitForLabel("首页")::getBoundsInScreen)
            android.util.Log.i("NavigationRegression", "Bottom navigation exit=$exiting; enter=$entering; final=$finalBounds")
            for (frames in listOf(exiting, entering)) {
                assertTrue("Expected vertical movement in rendered pixels, frames=$frames", frames.filterNotNull().distinct().size >= 2)
                frames.filterNotNull().forEach { top ->
                    assertTrue("Bottom navigation must slide towards the bottom", top >= baselineTop)
                }
            }
            assertNull("Exiting navigation must finish outside the visible window", exiting.last())
            assertEquals(baselineTop, entering.last())
            assertEquals(baseline, finalBounds)
        }
    }

    private fun recordNavigationFrames(sampleX: Int, searchTop: Int, surfaceColor: Int): List<Int?> {
        val frames = mutableListOf<Int?>()
        val deadline = SystemClock.uptimeMillis() + 500
        do {
            val screenshot = checkNotNull(automation.takeScreenshot())
            try { frames += navigationSurfaceTop(screenshot, sampleX, searchTop, surfaceColor) }
            finally { screenshot.recycle() }
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        return frames
    }

    private fun navigationSurfaceTop(bitmap: Bitmap, sampleX: Int, searchTop: Int, surfaceColor: Int): Int? {
        var run = 0
        for (y in searchTop until bitmap.height) {
            val pixel = bitmap.getPixel(sampleX, y)
            val matches = listOf(16, 8, 0).all { shift ->
                kotlin.math.abs(((pixel shr shift) and 255) - ((surfaceColor shr shift) and 255)) <= 2
            }
            run = if (matches) run + 1 else 0
            if (run >= 3) return y - run + 1
        }
        return null
    }

    @Test fun editingBackWalksViaImagesThenReturnsToMapList() {
        val image = File(context.cacheDir, "$fixtureClassId.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val map = AppServices.repository.createMap(
                fixtureClassId, "导航回归地图", MapTemplate(fixtureClassId, "导航模板", listOf(TemplateFloor("1f", "一楼"))),
                mapOf("1f" to Uri.fromFile(image)), emptyList(), context.contentResolver,
            )
            fixtureMapId = map.id
        } finally {
            bitmap.recycle()
            image.delete()
        }
        launchPortrait().use { scenario ->
            clickLabel("列表")
            clickLabel("导航回归地图")
            waitForLabel("编辑地图")
            clickLabel("下一步：标记门")
            waitForLabel("标记门 · 一楼")
            assertNavigationAbsent()
            systemBack(scenario)
            waitForLabel("编辑地图")
            assertNavigationAbsent()
            systemBack(scenario)
            waitForLabel("地图系统")
            waitForLabel("导航回归地图")
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun thicknessSliderSupportsAllFourStepsAndDisabledState() {
        launchPortrait().use {
            clickLabel("设置")
            clickLabel("视觉")
            scrollToLabel("线路粗细")
            val slider = waitForSlider()
            assertEquals(0f, slider.rangeInfo.min, 0f)
            assertEquals(3f, slider.rangeInfo.max, 0f)
            val bounds = Rect().also(slider::getBoundsInScreen)
            val touchInset = 24f * context.resources.displayMetrics.density
            for (step in 0..3) {
                tap(bounds.left + touchInset + (bounds.width() - touchInset * 2) * step / 3f, bounds.exactCenterY())
                assertEquals(step, AppServices.prefs.routeLineThickness)
            }
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 1f) }
            assertTrue(waitForSlider()
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
            waitUntil { AppServices.prefs.routeLineThickness == 1 }
            clickSwitchForLabel("显示路线")
            val disabled = waitForSlider()
            assertFalse(disabled.isEnabled)
            tap(bounds.right - touchInset, bounds.exactCenterY())
            assertEquals(1, AppServices.prefs.routeLineThickness)
        }
    }

    private fun launchPortrait(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        return scenario
    }

    private fun systemBack(scenario: ActivityScenario<MainActivity>) {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(450)
        scenario.onActivity { assertFalse("System back must stay in the activity on a child page", it.isFinishing) }
    }

    @Suppress("DEPRECATION")
    private fun assertContentReachesSystemNavigation(scenario: ActivityScenario<MainActivity>) {
        var expectedBottom = 0
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            expectedBottom = decor.height - (decor.rootWindowInsets?.systemWindowInsetBottom ?: 0)
        }
        // A full-height scroll container can report isScrollable=false when all content fits.
        val scroll = waitForNode { it.className?.toString() == "android.widget.ScrollView" }
        val bounds = Rect().also(scroll::getBoundsInScreen)
        assertTrue("Child viewport should end at $expectedBottom, actual=$bounds", kotlin.math.abs(bounds.bottom - expectedBottom) <= 2)
    }

    private fun assertNavigationAbsent() {
        SystemClock.sleep(450)
        for (label in listOf("首页", "列表", "订阅")) assertNull("Hidden navigation must leave composition: $label", findLabel(label))
    }

    private fun clickLabel(label: String, settle: Boolean = true) {
        var node = scrollToLabel(label)
        // Tap the visible label; a parent row may extend below a clipped scroll viewport.
        val bounds = Rect().also(node::getBoundsInScreen)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Expected clickable $label", node.isClickable)
        tap(bounds.exactCenterX(), bounds.exactCenterY(), settle)
    }

    private fun waitForSlider() = waitForNode { node ->
        node.rangeInfo != null && findNode(node) { it.contentDescription?.toString() == "线路粗细" } != null
    }

    private fun clickSwitchForLabel(label: String) {
        var container = scrollToLabel(label)
        var toggle = findNode(container) { it.isCheckable }
        while (toggle == null && container.parent != null) {
            container = container.parent
            toggle = findNode(container) { it.isCheckable }
        }
        val bounds = Rect().also(checkNotNull(toggle)::getBoundsInScreen)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun scrollToLabel(label: String): AccessibilityNodeInfo {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        repeat(10) {
            findLabel(label)?.takeIf(::insideScrollViewport)?.let { node ->
                val before = Rect().also(node::getBoundsInScreen)
                SystemClock.sleep(100)
                findLabel(label)?.takeIf(::insideScrollViewport)?.let { fresh ->
                    if (Rect().also(fresh::getBoundsInScreen) == before) return fresh
                }
            }
            val scroll = findNode(freshRoot()) { it.isScrollable }
            if (scroll != null && !scroll.performAction(direction)) direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            instrumentation.waitForIdleSync()
            SystemClock.sleep(450)
        }
        return waitForLabel(label)
    }

    private fun insideScrollViewport(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        var parent = node.parent
        while (parent != null) {
            if (parent.className?.toString() == "android.widget.ScrollView") {
                return Rect().also(parent::getBoundsInScreen).contains(bounds)
            }
            parent = parent.parent
        }
        return !bounds.isEmpty
    }

    private fun tap(x: Float, y: Float, settle: Boolean = true) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, settle || action == MotionEvent.ACTION_DOWN)) } finally { event.recycle() }
        }
        if (settle) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(450)
        }
    }

    private fun swipe(x: Float, fromY: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..24) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 24 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x, fromY + (toY - fromY) * step / 24f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(16)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(450)
    }

    private fun waitForLabel(label: String): AccessibilityNodeInfo = try {
        waitForNode { matchesLabel(it, label) }
    } catch (error: AssertionError) {
        throw AssertionError("Expected visible label: $label", error)
    }
    private fun findLabel(label: String) = findNode(freshRoot()) { matchesLabel(it, label) }
    private fun matchesLabel(node: AccessibilityNodeInfo, label: String) =
        node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString() == label)

    private fun waitForNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil { findNode(freshRoot(), predicate).also { result = it } != null }
        return checkNotNull(result)
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        // Accessibility caches can retain a frame's coordinates throughout a Compose animation.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        else automation.serviceInfo = automation.serviceInfo
        return automation.rootInActiveWindow
    }

    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            if (check()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for the expected UI state")
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), predicate)?.let { return it }
        return null
    }
}
