package com.idvb.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.R
import com.idvb.android.recognize.CandidateSelectionView
import com.idvb.android.recognize.RecognitionResult
import com.idvb.android.recognize.automaticallyConfirmedCandidate
import com.idvb.android.recognize.ScreenCaptureGrant
import com.idvb.android.recognize.ScreenCaptureSession
import com.idvb.android.recognize.vpsg.VpsgLineScanner
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.data.MapIdentitySource
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.recognize.AccessibilityScreenCaptureService
import com.idvb.android.recognize.RecognitionCandidate
import com.idvb.android.recognize.CaptureDiagnosticsContext
import com.idvb.android.recognize.RecognitionExecutor
import com.idvb.android.recognize.gate.ScreenRect
import com.idvb.android.idvm.MapRecord
import com.idvb.android.graphics.decodeMapRegion
import com.idvb.android.graphics.MapBackgroundRemover
import java.util.concurrent.Executors

/** 新悬浮窗入口：当前阶段只承载四个独立控制小球。 */
class OverlayService : Service() {
    companion object {
        const val CHANNEL_ID = "idvb_overlay"
        const val NOTIFICATION_ID = 101
        const val ACTION_TOGGLE_LOCK = "com.idvb.android.overlay.TOGGLE_LOCK"
        const val ACTION_TOGGLE_VISIBLE = "com.idvb.android.overlay.TOGGLE_VISIBLE"
        const val ACTION_SET_OPACITY = "com.idvb.android.overlay.SET_OPACITY"
        const val ACTION_SET_FLOOR = "com.idvb.android.overlay.SET_FLOOR"
        const val ACTION_CLOSE = "com.idvb.android.overlay.CLOSE"
        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_FLOOR_DELTA = "floor_delta"

        fun start(context: Context) = context.startForegroundService(Intent(context, OverlayService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, OverlayService::class.java))
        fun sendAction(context: Context, action: String, block: Intent.() -> Unit = {}) {
            runCatching { context.startService(Intent(context, OverlayService::class.java).setAction(action).apply(block)) }
        }
    }

    private lateinit var window: OverlayWindowManager
    private lateinit var overlayContext: Context
    private lateinit var blueprintWindow: OverlayWindowManager
    private lateinit var candidateWindow: OverlayWindowManager
    private lateinit var guideWindow: OverlayWindowManager
    private lateinit var scanProgressWindow: OverlayWindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanProgressView: ScanProgressView? = null
    @Volatile private var scanGeneration = 0
    private var balls: OverlayBallView? = null
    private var blueprintView: BlueprintCalibrationView? = null
    private var adjustView: BlueprintImageAdjustView? = null
    private var candidateView: CandidateSelectionView? = null
    private var candidateResult: RecognitionResult? = null
    private var captureSession: ScreenCaptureSession? = null
    private var captureMethod = AppServices.prefs.screenCaptureMethod
    private var lastCaptureScreen: Pair<Int, Int>? = null
    private var destroyed = false
    private val capturePreferencesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "screen_capture_method") mainHandler.post {
            if (!destroyed) synchronizeCaptureSession()
        }
    }

    private fun cancelCaptureScan() {
        scanGeneration++
        val wasScanning = scanning
        scanning = false
        hideScanProgress()
        if (wasScanning) {
            balls?.visibility = android.view.View.VISIBLE
            if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
        }
    }

    private fun synchronizeCaptureSession() {
        val method = AppServices.prefs.screenCaptureMethod
        val changed = captureMethod != method
        val renewed = captureSession?.let { it.grantRevision != ScreenCaptureGrant.revision } == true
        if (changed || renewed) {
            cancelCaptureScan()
            captureSession?.close()
            captureSession = null
            captureMethod = method
        }
        if (method == ScreenCaptureMethod.MEDIA_PROJECTION && captureSession == null && ScreenCaptureGrant.available) {
            startForegroundCompat()
            val size = screenSize()
            captureSession = ScreenCaptureSession(this).also { it.start(size.first, size.second) }
        }
        Log.d("IDVBCapture", "source=$method grant=${ScreenCaptureGrant.revision} session=${captureSession != null}")
    }
    private var guideView: GuideMapView? = null
    private var guideBitmap: Bitmap? = null
    private var guideVisible = false
    private var currentMap: MapRecord? = null
    private var floorIndex = 0
    @Volatile private var scanning = false
    private val recognitionExecutor = RecognitionExecutor()
    private val accessibilityCaptureExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "idvb-accessibility-capture")
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                Handler(Looper.getMainLooper()).post { refreshWindowsForDisplayChange() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("overlay", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(capturePreferencesListener)
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val displayContext = display?.let(::createDisplayContext) ?: this
        overlayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        window = OverlayWindowManager(overlayContext)
        blueprintWindow = OverlayWindowManager(overlayContext)
        candidateWindow = OverlayWindowManager(overlayContext)
        guideWindow = OverlayWindowManager(overlayContext)
        scanProgressWindow = OverlayWindowManager(overlayContext)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(); startForegroundCompat()
        if (intent?.action != ACTION_CLOSE) synchronizeCaptureSession()
        when (intent?.action) {
            ACTION_CLOSE -> stopSelf()
            ACTION_TOGGLE_LOCK -> {
                ensureBalls()
                val locked = !OverlayState.state.value.locked
                window.update(locked)
                OverlayState.update { it.copy(locked = locked) }
            }
            ACTION_TOGGLE_VISIBLE -> {
                ensureBalls()
                val visible = !OverlayState.state.value.visible
                balls?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
                if (!visible) guideView?.visibility = android.view.View.INVISIBLE
                else if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                OverlayState.update { it.copy(visible = visible) }
            }
            ACTION_SET_OPACITY -> {
                ensureBalls()
                val opacity = intent.getFloatExtra(EXTRA_OPACITY, AppServices.prefs.opacity).coerceIn(.1f, 1f)
                AppServices.prefs.opacity = opacity
                guideWindow.opacity = opacity
                guideView?.showBitmap(guideBitmap)
                OverlayState.update { it.copy(opacity = opacity) }
            }
            ACTION_SET_FLOOR -> {
                ensureBalls()
                val delta = intent.getIntExtra(EXTRA_FLOOR_DELTA, 0)
                if (delta > 0) nextFloor() else if (delta < 0) previousFloor()
            }
            else -> ensureBalls()
        }
        return START_STICKY
    }

    private fun ensureBalls() {
        if (balls != null) return
        val density = overlayContext.resources.displayMetrics.density
        val screen = screenSize()
        val catalog = AppServices.repository.loadCatalog()
        val activeClassId = resolveActiveClassId(catalog)
        currentMap = AppServices.prefs.lastMapId?.let { id ->
            catalog.maps.firstOrNull { it.id == id && it.classId == activeClassId }
        }
        val orderedFloors = currentMap?.floors?.sortedBy { it.sortOrder }.orEmpty()
        floorIndex = orderedFloors.indexOfFirst { it.key == AppServices.prefs.lastFloorKey }
            .let { if (it < 0) 0 else it }
        // 收起菜单时窗口必须与小球同高，避免不可见的透明区域拦截底层应用触摸。
        val hasVariants = catalog.nextVariantMapId(currentMap?.id.orEmpty()) != null
        window.width = ((if (hasVariants) 264 else 212) * density).toInt(); window.height = (48 * density).toInt()
        window.x = (screen.first - window.width).coerceAtLeast(0); window.y = (screen.second * .28f).toInt()
        var menuOpensUp = false
        balls = OverlayBallView(overlayContext).apply {
            mapLocked = currentMap != null
            identityVerified = currentMap?.let {
                AppServices.prefs.lastMapIdentitySource == MapIdentitySource.STRUCTURE_VERIFIED
            }
            floorLabel = orderedFloors.getOrNull(floorIndex)?.displayName ?: "--"
            variantsAvailable = hasVariants
            listener = object : OverlayBallView.Listener {
                override fun onSearch() = runForegroundScan()
                override fun onToggleGuide() = toggleGuide()
                override fun onNextFloor() = nextFloor()
                override fun onNextVariant() = nextVariant()
                override fun onFreeAdjust() = enterFreeAdjustMode()
                override fun onCalibrate() = enterBlueprintMode()
                override fun onClose() = stopSelf()
                override fun onMenuExpanded(expanded: Boolean) {
                    val collapsedHeight = (48 * density).toInt()
                    val expandedHeight = (190 * density).toInt()
                    val heightChange = expandedHeight - collapsedHeight
                    if (expanded) {
                        val ballY = window.y
                        val screenHeight = screenSize().second
                        menuOpensUp = ballY + expandedHeight > screenHeight && ballY >= heightChange
                        setMenuOpensUp(menuOpensUp)
                        if (menuOpensUp) window.y = ballY - heightChange
                        window.height = expandedHeight
                    } else {
                        if (menuOpensUp) window.y += heightChange
                        window.height = collapsedHeight
                        menuOpensUp = false
                    }
                    window.update()
                }
                override fun onMove(dx: Float, dy: Float) {
                    // 旋转不会重建前台服务，因此拖动时必须使用实时屏幕尺寸。
                    val currentScreen = screenSize()
                    window.x = (window.x + dx.toInt()).coerceIn(
                        0,
                        (currentScreen.first - window.width).coerceAtLeast(0),
                    )
                    window.y = (window.y + dy.toInt()).coerceIn(
                        0,
                        (currentScreen.second - window.height).coerceAtLeast(0),
                    )
                    window.update()
                }
            }
        }
        // 攻略图窗口先于控制球挂载。之后只切换可见性，既能保证控制球始终在最上层，
        // 也避免点击“显示”时通过移除/重挂控制球造成整窗闪烁。
        guideView = GuideMapView(overlayContext).apply { visibility = android.view.View.INVISIBLE }
        guideWindow.x = 0; guideWindow.y = 0; guideWindow.width = 1; guideWindow.height = 1
        guideWindow.opacity = AppServices.prefs.opacity
        guideWindow.add(guideView!!, locked = true)
        window.add(balls!!, locked = false)
        lastCaptureScreen = screen
        OverlayState.update { it.copy(running = true, visible = true, locked = false) }
    }

    private fun runForegroundScan() {
        if (candidateView != null || blueprintView != null || adjustView != null) return
        if (AppServices.prefs.debugMode) {
            if (AppServices.prefs.manualMapSelectionEnabled) showManualMapSelection() else showDebugCandidates()
            return
        }
        synchronizeCaptureSession()
        if (scanning) {
            Toast.makeText(this, "正在扫描地图，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        clearPendingCandidates()
        val size = screenSize()
        val region = captureRegionPixels(size.first, size.second)
        if (region == null) {
            Toast.makeText(this, "请先在 ··· 中校准显示区域", Toast.LENGTH_SHORT).show()
            return
        }
        val method = AppServices.prefs.screenCaptureMethod
        val session = captureSession
        if (method == ScreenCaptureMethod.MEDIA_PROJECTION && (session == null || !session.start(size.first, size.second))) {
            Toast.makeText(this, "屏幕捕获授权已失效，请返回 IDVB 重新授权", Toast.LENGTH_LONG).show(); return
        }
        if (method == ScreenCaptureMethod.ACCESSIBILITY && !AccessibilityScreenCaptureService.available) {
            Toast.makeText(this, "IDVB 无障碍服务未启用，请返回应用授权", Toast.LENGTH_LONG).show(); return
        }
        scanning = true
        scanGeneration++
        val generation = scanGeneration
        hideScanProgress()
        if (method == ScreenCaptureMethod.MEDIA_PROJECTION) session?.prepareCapture()
        Log.d("IDVBCapture", "request=$generation source=$method screen=$size")
        balls?.visibility = android.view.View.INVISIBLE
        guideView?.visibility = android.view.View.INVISIBLE
        mainHandler.postDelayed({
            if (destroyed || generation != scanGeneration || method != AppServices.prefs.screenCaptureMethod) return@postDelayed
            val bounds = Rect(region.left.toInt(), region.top.toInt(), region.right.toInt(), region.bottom.toInt())
            val callback = { captured: Result<Bitmap> ->
                mainHandler.post {
                    if (destroyed || generation != scanGeneration || method != AppServices.prefs.screenCaptureMethod) {
                        captured.getOrNull()?.recycle()
                    } else {
                        captured.exceptionOrNull()?.let { Log.e("IDVBCapture", "request=$generation source=$method failed", it) }
                        processCapturedFrame(captured, region, size, generation)
                    }
                }
                Unit
            }
            if (method == ScreenCaptureMethod.ACCESSIBILITY) {
                AccessibilityScreenCaptureService.capture(bounds, accessibilityCaptureExecutor, callback)
            } else {
                session!!.capture(bounds, callback)
            }
        }, 140L)
    }

    private fun showManualMapSelection(reference: Bitmap? = null) {
        val catalog = AppServices.repository.loadCatalog()
        val activeClassId = resolveActiveClassId(catalog)
        val maps = catalog.maps.filter { it.classId == activeClassId }
        if (maps.isEmpty()) {
            Toast.makeText(this, "当前关卡模式下没有可选择地图", Toast.LENGTH_SHORT).show()
            return
        }
        val preview = reference ?: Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.rgb(24, 28, 31))
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(142, 232, 93); textSize = 34f; textAlign = Paint.Align.CENTER
                    drawText(if (AppServices.prefs.debugMode) "IDVB 调试模式" else "手动选择地图", 320f, 166f, this)
                    color = Color.LTGRAY; textSize = 20f
                    drawText("按标签筛选后选择地图", 320f, 208f, this)
                }
            }
        }
        val candidates = maps.map { map ->
            RecognitionCandidate(
                map = map,
                floorKey = map.floors.minByOrNull { it.sortOrder }?.key.orEmpty(),
                disposition = CandidateDisposition.CATALOG_ONLY,
                evidenceLabel = "手动选择 · 结构未确认",
            )
        }
        showCandidates(RecognitionResult(preview, candidates), manualSelection = true)
    }

    private fun processCapturedFrame(captured: Result<Bitmap>, region: RectF, size: Pair<Int, Int>,
        generation: Int) {
        if (generation != scanGeneration) {
            captured.getOrNull()?.recycle()
            return
        }
        val bitmap = captured.getOrElse { error ->
            mainHandler.post {
                if (generation != scanGeneration) return@post
                scanning = false
                balls?.visibility = android.view.View.VISIBLE
                if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                Toast.makeText(this, "截图失败：${error.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (AppServices.prefs.manualMapSelectionEnabled) {
            mainHandler.post {
                if (generation != scanGeneration) {
                    bitmap.recycle()
                    return@post
                }
                scanning = false
                balls?.visibility = android.view.View.VISIBLE
                if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                showManualMapSelection(bitmap)
            }
            return
        }
                // 截图完成后才显示进度条，避免悬浮层被截入识别画面。
                mainHandler.post {
                    if (generation != scanGeneration) return@post
                    showScanProgress(region, size, generation)
                    balls?.visibility = android.view.View.VISIBLE
                    if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                }
                val accepted = recognitionExecutor.execute {
                    val scanStarted = System.nanoTime()
                    val recognition = runCatching {
                        reportScanProgress(generation, .10, "正在准备扫描…")
                        val catalog = AppServices.repository.loadCatalog()
                        val classId = resolveActiveClassId(catalog)
                        val viewport = ScreenRect(region.left.toDouble(), region.top.toDouble(),
                            region.width().toDouble(), region.height().toDouble())
                        com.idvb.android.recognize.MapScanRecognizer(this, AppServices.repository).recognize(
                            bitmap, viewport, size.first, size.second, classId,
                            progress = { value, label -> reportScanProgress(generation, .10 + .86 * value, label) },
                        )
                    }
                    val loggedResult = recognition.getOrNull()
                    Log.i("IDVB-Scan", "scan totalMs=${(System.nanoTime() - scanStarted) / 1_000_000}" +
                        " route=${loggedResult?.route ?: loggedResult?.diagnostics?.route ?: "failed"}" +
                        " reliable=${loggedResult?.candidates?.count {
                            it.disposition == CandidateDisposition.RELIABLE
                        } ?: 0}" +
                        " vpsgMs=${loggedResult?.vpsgDiagnostics?.elapsedMilliseconds ?: 0.0}" +
                        " structureMs=${loggedResult?.diagnostics?.structureTotalMilliseconds ?: 0.0}")
                    val diagnostics = if (AppServices.prefs.recognitionDiagnosticsEnabled) {
                        recognition.getOrNull()?.let { result -> runCatching {
                            val catalog = AppServices.repository.loadCatalog()
                            AppServices.recognitionDiagnostics.record(
                                capturedFrame = bitmap,
                                result = result,
                                captureContext = CaptureDiagnosticsContext(
                                    screenWidth = size.first,
                                    screenHeight = size.second,
                                    captureLeft = region.left.toInt(),
                                    captureTop = region.top.toInt(),
                                    captureRight = region.right.toInt(),
                                    captureBottom = region.bottom.toInt(),
                                    classId = resolveActiveClassId(catalog),
                                    className = catalog.classes.firstOrNull {
                                        it.id == resolveActiveClassId(catalog)
                                    }?.name,
                                ),
                            )
                        }.getOrElse { Result.failure(it) } }
                    } else null
                    mainHandler.post {
                        if (generation != scanGeneration) {
                            bitmap.recycle()
                            return@post
                        }
                        scanning = false
                        finishScanProgress(generation, recognition.isSuccess)
                        diagnostics?.exceptionOrNull()?.let { error ->
                            Toast.makeText(this, "识别诊断保存失败：${error.message}", Toast.LENGTH_LONG).show()
                        }
                        recognition.onSuccess(::handleScanResult).onFailure { error ->
                            bitmap.recycle()
                            Toast.makeText(this, "扫描失败：${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (!accepted) {
                    mainHandler.post {
                        bitmap.recycle()
                        if (generation != scanGeneration) return@post
                        scanning = false
                        finishScanProgress(generation, false)
                        balls?.visibility = android.view.View.VISIBLE
                        if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "识别线程已停止", Toast.LENGTH_LONG).show()
                    }
                }
    }

    private fun showScanProgress(region: RectF, size: Pair<Int, Int>, generation: Int) {
        if (generation != scanGeneration || !scanning) return
        val density = overlayContext.resources.displayMetrics.density
        val barWidth = minOf((340 * density).toInt(), size.first - (32 * density).toInt()).coerceAtLeast(1)
        val barHeight = (56 * density).toInt()
        scanProgressWindow.width = barWidth
        scanProgressWindow.height = barHeight
        scanProgressWindow.x = (region.centerX() - barWidth / 2f).toInt()
            .coerceIn(0, (size.first - barWidth).coerceAtLeast(0))
        scanProgressWindow.y = (region.top + region.height() * .85f - barHeight / 2f).toInt()
            .coerceIn(0, (size.second - barHeight).coerceAtLeast(0))
        val view = ScanProgressView(overlayContext)
        scanProgressView = view
        view.report(.08, "正在扫描地图…")
        scanProgressWindow.add(view, locked = true)
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(220).start()
    }

    private fun reportScanProgress(generation: Int, value: Double, label: String) {
        mainHandler.post {
            if (generation == scanGeneration && scanning) scanProgressView?.report(value, label)
        }
    }

    private fun finishScanProgress(generation: Int, success: Boolean) {
        if (generation != scanGeneration) return
        val view = scanProgressView ?: return
        view.finish(success)
        mainHandler.postDelayed({
            if (generation == scanGeneration && scanProgressView === view) hideScanProgress()
        }, 1200L)
    }

    private fun hideScanProgress() {
        scanProgressView?.animate()?.cancel()
        scanProgressWindow.remove()
        scanProgressView = null
    }

    private fun showDebugCandidates() {
        val catalog = AppServices.repository.loadCatalog()
        val activeClassId = resolveActiveClassId(catalog)
        val maps = catalog.maps.filter { it.classId == activeClassId }
        if (maps.isEmpty()) {
            Toast.makeText(this, "当前关卡模式下没有可扫描地图", Toast.LENGTH_SHORT).show()
            return
        }
        val randomized = maps.shuffled()
        val highMaps = randomized.take(3)
        val referenceMaps = randomized.drop(highMaps.size).take(2)
        val included = (highMaps + referenceMaps).mapTo(mutableSetOf()) { it.id }
        fun primaryFloor(map: MapRecord) = map.floors.minByOrNull { it.sortOrder }?.key.orEmpty()
        val high = highMaps.mapIndexed { index, map ->
            val score = .94 - index * .045
            RecognitionCandidate(
                map = map,
                floorKey = primaryFloor(map),
                disposition = CandidateDisposition.RELIABLE,
                templateScore = score,
                templateMargin = .08 - index * .01,
                chamferPixels = 1.15 + index * .42,
                edgeCoverage = .86 - index * .07,
                occupancyCoverage = .82 - index * .06,
                evidenceLabel = "结构已验证 · 调试高候选 · 模板相似度 ${(score * 100).toInt()}%",
            )
        }
        val references = referenceMaps.mapIndexed { index, map ->
            val score = .72 - index * .06
            RecognitionCandidate(
                map = map,
                floorKey = primaryFloor(map),
                disposition = CandidateDisposition.NEEDS_VERIFICATION,
                templateScore = score,
                evidenceLabel = "仅供参考（未通过结构验证） · 调试候选 ${(score * 100).toInt()}%",
            )
        }
        val remaining = maps.filterNot { it.id in included }.map { map ->
            RecognitionCandidate(
                map = map,
                floorKey = primaryFloor(map),
                disposition = CandidateDisposition.CATALOG_ONLY,
                evidenceLabel = "未进入本次识别候选",
            )
        }
        val preview = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(preview).apply {
            drawColor(Color.rgb(24, 28, 31))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(142, 232, 93)
                textSize = 34f
                textAlign = Paint.Align.CENTER
            }
            drawText("IDVB 调试模式", preview.width / 2f, preview.height / 2f - 8f, paint)
            paint.color = Color.LTGRAY; paint.textSize = 20f
            drawText("已跳过屏幕捕获与识别算法", preview.width / 2f, preview.height / 2f + 34f, paint)
        }
        handleScanResult(RecognitionResult(preview, high + references + remaining))
    }

    private fun handleScanResult(result: RecognitionResult) {
        val confirmed = result.automaticallyConfirmedCandidate()
        if (confirmed != null) {
            clearPendingCandidates()
            candidateResult = result
            lockSelectedMap(confirmed)
            return
        }
        if (!AppServices.prefs.backgroundScanEnabled) {
            showCandidates(result)
            return
        }
        clearPendingCandidates()
        candidateResult = result
        balls?.candidatesAvailable = true
        Toast.makeText(this, "扫描完成，已得出候选结果；点击 👁 查看", Toast.LENGTH_LONG).show()
    }

    private fun showPendingCandidates(): Boolean {
        val result = candidateResult ?: return false
        if (candidateView != null) return true
        showCandidates(result)
        return true
    }

    private fun showCandidates(
        result: RecognitionResult,
        manualSelection: Boolean = false,
    ) {
        if (candidateResult !== result) closeCandidates(recycleCapture = true)
        candidateResult = result
        balls?.candidatesAvailable = false
        val size = screenSize()
        candidateWindow.x = 0; candidateWindow.y = 0; candidateWindow.width = size.first; candidateWindow.height = size.second
        candidateView = CandidateSelectionView(overlayContext, result, AppServices.repository, manualSelection).apply {
            listener = object : CandidateSelectionView.Listener {
                override fun onSelected(candidate: com.idvb.android.recognize.RecognitionCandidate) {
                    lockSelectedMap(candidate)
                }
                override fun onCancelled() = closeCandidates(recycleCapture = true)
            }
        }
        candidateWindow.add(candidateView!!, locked = false)
    }

    private fun lockSelectedMap(candidate: com.idvb.android.recognize.RecognitionCandidate) {
        AppServices.prefs.lastMapId = candidate.map.id
        AppServices.prefs.lastFloorKey = candidate.floorKey
        val identitySource = if (candidate.disposition == CandidateDisposition.RELIABLE) {
            MapIdentitySource.STRUCTURE_VERIFIED
        } else {
            MapIdentitySource.MANUAL_UNVERIFIED
        }
        AppServices.prefs.lastMapIdentitySource = identitySource
        currentMap = candidate.map
        val floors = candidate.map.floors.sortedBy { it.sortOrder }
        floorIndex = floors.indexOfFirst { it.key == candidate.floorKey }.let { if (it < 0) 0 else it }
        balls?.mapLocked = true
        balls?.identityVerified = identitySource == MapIdentitySource.STRUCTURE_VERIFIED
        balls?.floorLabel = floors.getOrNull(floorIndex)?.displayName ?: candidate.floorKey
        val hasVariants = AppServices.repository.loadCatalog().nextVariantMapId(candidate.map.id) != null
        balls?.variantsAvailable = hasVariants
        resizeControls(hasVariants)
        if (guideVisible) loadGuideFloor()
        closeCandidates(recycleCapture = true)
        val message = if (identitySource == MapIdentitySource.STRUCTURE_VERIFIED) {
            "结构已确认并锁定：${candidate.map.title}"
        } else {
            "已人工选择：${candidate.map.title}（结构未确认）"
        }
        Toast.makeText(this@OverlayService, message, Toast.LENGTH_SHORT).show()
    }

    private fun closeCandidates(recycleCapture: Boolean) {
        candidateWindow.remove(); candidateView = null
        if (recycleCapture) candidateResult?.capturedRegion?.let { if (!it.isRecycled) it.recycle() }
        candidateResult = null
        balls?.candidatesAvailable = false
    }

    private fun clearPendingCandidates() {
        if (candidateView == null && candidateResult != null) closeCandidates(recycleCapture = true)
    }

    private fun toggleGuide() {
        if (showPendingCandidates()) return
        if (currentMap == null) return
        if (guideVisible) {
            guideView?.visibility = android.view.View.INVISIBLE
            guideVisible = false
            balls?.guideVisible = false
            return
        }
        val size = screenSize()
        val region = guideRegionPixels(size.first, size.second)
        if (region == null) {
            Toast.makeText(this, "请先校准显示区域", Toast.LENGTH_SHORT).show()
            return
        }
        applyGuideBounds(region)
        loadGuideFloor()
        guideWindow.update()
        guideView?.visibility = android.view.View.VISIBLE
        guideVisible = true
        balls?.guideVisible = true
    }

    private fun nextFloor() {
        val map = currentMap ?: return
        val floors = map.floors.sortedBy { it.sortOrder }
        if (floors.isEmpty()) return
        floorIndex = (floorIndex + 1) % floors.size
        val floor = floors[floorIndex]
        AppServices.prefs.lastFloorKey = floor.key
        balls?.floorLabel = floor.displayName
        if (guideVisible) loadGuideFloor()
    }

    private fun previousFloor() {
        val map = currentMap ?: return
        val floors = map.floors.sortedBy { it.sortOrder }
        if (floors.isEmpty()) return
        floorIndex = (floorIndex - 1 + floors.size) % floors.size
        val floor = floors[floorIndex]
        AppServices.prefs.lastFloorKey = floor.key
        balls?.floorLabel = floor.displayName
        if (guideVisible) loadGuideFloor()
    }

    private fun nextVariant() {
        val map = currentMap ?: return
        val catalog = AppServices.repository.loadCatalog()
        val next = catalog.nextVariantMapId(map.id)?.let { id -> catalog.maps.firstOrNull { it.id == id } } ?: return
        val floorKey = AppServices.prefs.lastFloorKey
        val floors = next.floors.sortedBy { it.sortOrder }
        val index = floors.indexOfFirst { it.key == floorKey }.takeIf { it >= 0 } ?: return
        currentMap = next
        floorIndex = index
        AppServices.prefs.lastMapId = next.id
        AppServices.prefs.lastFloorKey = floorKey
        AppServices.prefs.selectedMapClassId = next.classId
        AppServices.prefs.lastMapIdentitySource = MapIdentitySource.MANUAL_UNVERIFIED
        balls?.identityVerified = false
        balls?.floorLabel = floors[index].displayName
        balls?.variantsAvailable = catalog.nextVariantMapId(next.id) != null
        if (guideVisible) loadGuideFloor()
    }

    private fun resizeControls(hasVariants: Boolean) {
        val screen = screenSize()
        window.width = ((if (hasVariants) 264 else 212) * overlayContext.resources.displayMetrics.density).toInt()
        window.x = window.x.coerceIn(0, (screen.first - window.width).coerceAtLeast(0))
        window.update()
    }

    private fun loadGuideFloor() {
        val map = currentMap ?: return
        val floor = map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex) ?: return
        val file = AppServices.repository.floorImageFile(map.id, floor.imagePath)
        val maxDimension = maxOf(screenSize().first, screenSize().second) * 2
        val selected = AppServices.repository.loadPreviewRegion(map.id, floor)
        var next = decodeMapRegion(file, selected, maxDimension,
            AppServices.repository.loadFreeCropPoints(map.id, floor)) ?: return
        if (AppServices.prefs.removeGuideBackground) {
            val classMarkedForRemoval = AppServices.repository.loadCatalog().classes
                .firstOrNull { it.id == map.classId }
                ?.removeBackground == true
            val processed = MapBackgroundRemover.remove(
                bitmap = next,
                classMarkedForRemoval = classMarkedForRemoval,
                layers = AppServices.repository.loadBackgroundLayers(map.id, floor.key),
                sourceWidth = floor.imageWidth,
                sourceHeight = floor.imageHeight,
                region = selected,
            )
            if (processed !== next && !next.isRecycled) next.recycle()
            next = processed
        }
        val previous = guideBitmap
        guideBitmap = next
        guideWindow.opacity = AppServices.prefs.opacity
        guideView?.showBitmap(next)
        previous?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun applyGuideBounds(region: RectF) {
        val target = if (AppServices.prefs.constrainGuideToScreen) {
            val screen = screenSize()
            val width = region.width().coerceAtMost(screen.first.toFloat()).coerceAtLeast(1f)
            val height = region.height().coerceAtMost(screen.second.toFloat()).coerceAtLeast(1f)
            RectF(
                region.left.coerceIn(0f, screen.first - width),
                region.top.coerceIn(0f, screen.second - height),
                region.left.coerceIn(0f, screen.first - width) + width,
                region.top.coerceIn(0f, screen.second - height) + height,
            )
        } else region
        guideWindow.x = target.left.toInt()
        guideWindow.y = target.top.toInt()
        guideWindow.width = target.width().toInt().coerceAtLeast(1)
        guideWindow.height = target.height().toInt().coerceAtLeast(1)
    }

    private fun enterFreeAdjustMode() {
        if (adjustView != null || blueprintView != null || candidateView != null) return
        if (currentMap == null) {
            Toast.makeText(this, "请先锁定地图", Toast.LENGTH_SHORT).show()
            return
        }
        val screen = screenSize()
        val resetRegion = captureRegionPixels(screen.first, screen.second)
        if (resetRegion == null) {
            Toast.makeText(this, "请先校准显示区域", Toast.LENGTH_SHORT).show()
            return
        }
        if (!guideVisible) toggleGuide()
        val bitmap = guideBitmap ?: return
        val currentRegion = guideRegionPixels(screen.first, screen.second) ?: resetRegion
        // 编辑蓝图自己绘制攻略图，避免底下的点击穿透窗口重复叠图。
        guideView?.visibility = android.view.View.INVISIBLE
        blueprintWindow.x = 0; blueprintWindow.y = 0
        blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
        adjustView = BlueprintImageAdjustView(
            overlayContext,
            bitmap,
            currentRegion,
            resetRegion,
            AppServices.prefs.opacity,
        ).apply {
            listener = object : BlueprintImageAdjustView.Listener {
                override fun onConfirmed(region: RectF, opacity: Float) {
                    val size = screenSize()
                    AppServices.prefs.setGuideRegion(
                        landscape = size.first > size.second,
                        left = region.left / size.first,
                        top = region.top / size.second,
                        right = region.right / size.first,
                        bottom = region.bottom / size.second,
                    )
                    AppServices.prefs.opacity = opacity
                    exitFreeAdjustMode(restoreGuide = true)
                    Toast.makeText(this@OverlayService, "攻略图显示已保存", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 可聚焦窗口负责接收音量键；仍然覆盖全屏并吞掉所有触摸。
        blueprintWindow.add(adjustView!!, locked = false, focusable = true)
    }

    private fun exitFreeAdjustMode(restoreGuide: Boolean) {
        blueprintWindow.remove()
        adjustView = null
        if (!restoreGuide || !guideVisible || guideView == null) return
        val screen = screenSize()
        val region = guideRegionPixels(screen.first, screen.second) ?: return
        applyGuideBounds(region)
        guideWindow.opacity = AppServices.prefs.opacity
        guideView?.showBitmap(guideBitmap)
        guideWindow.update()
        guideView?.visibility = android.view.View.VISIBLE
    }

    private fun enterBlueprintMode() {
        if (blueprintView != null || adjustView != null || candidateView != null) return
        val screen = screenSize()
        blueprintWindow.x = 0; blueprintWindow.y = 0
        blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
        blueprintView = BlueprintCalibrationView(overlayContext).apply {
            listener = object : BlueprintCalibrationView.Listener {
                override fun onConfirmed(region: RectF) {
                    val size = screenSize()
                    AppServices.prefs.setCaptureRegion(
                        landscape = size.first > size.second,
                        left = region.left / width,
                        top = region.top / height,
                        right = region.right / width,
                        bottom = region.bottom / height,
                    )
                    if (guideVisible) {
                        val guideRegion = guideRegionPixels(size.first, size.second) ?: region
                        applyGuideBounds(guideRegion)
                        guideWindow.update()
                    }
                    exitBlueprintMode()
                    Toast.makeText(this@OverlayService, "显示区域已校准", Toast.LENGTH_SHORT).show()
                }
                override fun onCancelled() = exitBlueprintMode()
            }
        }
        // 全屏可触摸窗口会吞掉全部事件，蓝图模式期间不会穿透到底层进程。
        blueprintWindow.add(blueprintView!!, locked = false)
    }

    private fun exitBlueprintMode() {
        blueprintWindow.remove()
        blueprintView = null
    }

    /** 将当前方向保存的比例坐标换算成屏幕捕获所需的真实像素区域。 */
    private fun captureRegionPixels(screenWidth: Int, screenHeight: Int): RectF? {
        val values = AppServices.prefs.captureRegion(screenWidth > screenHeight) ?: return null
        return RectF(
            values[0] * screenWidth, values[1] * screenHeight,
            values[2] * screenWidth, values[3] * screenHeight,
        )
    }

    private fun guideRegionPixels(screenWidth: Int, screenHeight: Int): RectF? {
        val values = AppServices.prefs.guideRegion(screenWidth > screenHeight)
            ?: AppServices.prefs.captureRegion(screenWidth > screenHeight)
            ?: return null
        return RectF(
            values[0] * screenWidth, values[1] * screenHeight,
            values[2] * screenWidth, values[3] * screenHeight,
        )
    }

    private fun resolveActiveClassId(catalog: com.idvb.android.idvm.MapCatalogDocument): String? {
        val saved = AppServices.prefs.selectedMapClassId
        val resolved = saved?.takeIf { id -> catalog.classes.any { it.id == id } }
            ?: catalog.classes.firstOrNull()?.id
        if (resolved != saved) AppServices.prefs.selectedMapClassId = resolved
        return resolved
    }

    override fun onDestroy() {
        destroyed = true
        getSharedPreferences("overlay", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(capturePreferencesListener)
        scanGeneration++
        scanning = false
        hideScanProgress()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        accessibilityCaptureExecutor.shutdownNow()
        recognitionExecutor.close()
        closeCandidates(recycleCapture = true)
        captureSession?.close(); captureSession = null
        guideWindow.remove(); guideView = null
        guideBitmap?.let { if (!it.isRecycled) it.recycle() }; guideBitmap = null
        blueprintWindow.remove(); blueprintView = null; adjustView = null
        window.remove(); balls = null; OverlayState.reset(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWindowsForDisplayChange()
    }

    /** 固定竖屏 Activity 在后台时配置回调并不可靠，显示器旋转事件也走同一刷新链路。 */
    private fun refreshWindowsForDisplayChange() {
        if (balls == null) return
        val screen = screenSize()
        if (blueprintView != null) {
            blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
            blueprintWindow.x = 0; blueprintWindow.y = 0
            blueprintWindow.update()
        }
        if (adjustView != null) {
            blueprintWindow.remove()
            adjustView = null
            enterFreeAdjustMode()
        }
        if (candidateView != null) {
            candidateWindow.width = screen.first; candidateWindow.height = screen.second
            candidateWindow.x = 0; candidateWindow.y = 0; candidateWindow.update()
        }
        if (scanProgressView != null) {
            scanProgressWindow.x = scanProgressWindow.x.coerceIn(0,
                (screen.first - scanProgressWindow.width).coerceAtLeast(0))
            scanProgressWindow.y = scanProgressWindow.y.coerceIn(0,
                (screen.second - scanProgressWindow.height).coerceAtLeast(0))
            scanProgressWindow.update()
        }
        if (lastCaptureScreen != screen) {
            cancelCaptureScan()
            lastCaptureScreen = screen
        }
        if (AppServices.prefs.screenCaptureMethod == ScreenCaptureMethod.MEDIA_PROJECTION) {
            captureSession?.start(screen.first, screen.second)
        }
        if (guideVisible) {
            val guideRegion = guideRegionPixels(screen.first, screen.second)
            if (guideRegion == null) {
                guideView?.visibility = android.view.View.INVISIBLE
                guideWindow.x = 0; guideWindow.y = 0; guideWindow.width = 1; guideWindow.height = 1
                guideWindow.update(); guideVisible = false; balls?.guideVisible = false
            } else {
                applyGuideBounds(guideRegion); guideWindow.update()
            }
        }
        window.x = window.x.coerceIn(0, (screen.first - window.width).coerceAtLeast(0))
        window.y = window.y.coerceIn(0, (screen.second - window.height).coerceAtLeast(0))
        window.update()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (AppServices.prefs.screenCaptureMethod == ScreenCaptureMethod.MEDIA_PROJECTION && ScreenCaptureGrant.available) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else 0
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_HOME
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IDVB 悬浮窗").setContentText("小球控制窗运行中")
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(openApp).setOngoing(true)
            .addAction(0, "关闭", close).build()
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = overlayContext.getSystemService(WindowManager::class.java)
            wm.currentWindowMetrics.bounds.run { width() to height() }
        } else {
            @Suppress("DEPRECATION")
            val size = Point().also { point ->
                (overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealSize(point)
            }
            size.x to size.y
        }
    }
}
