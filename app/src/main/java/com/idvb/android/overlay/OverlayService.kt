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
import com.idvb.android.data.EyeButtonAction
import com.idvb.android.data.SearchButtonAction
import com.idvb.android.alignment.AlignmentRequest
import com.idvb.android.alignment.AlignmentCancellation
import com.idvb.android.alignment.AlignmentResult
import com.idvb.android.alignment.AlignmentLogSink
import com.idvb.android.alignment.AlignmentLogEvent
import com.idvb.android.alignment.AlignmentTrace
import com.idvb.android.alignment.AlignmentDiagnosticContext
import com.idvb.android.alignment.emit
import com.idvb.android.alignment.measure
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
        const val ACTION_CONFIGURE_AUTO_MAP_REFERENCE = "com.idvb.android.overlay.CONFIGURE_AUTO_MAP_REFERENCE"
        const val ACTION_CLOSE = "com.idvb.android.overlay.CLOSE"
        const val EXTRA_OPACITY = "opacity"
        const val EXTRA_FLOOR_DELTA = "floor_delta"
        private var currentService: OverlayService? = null
        private var practiceForeground = false
        private var hostForeground = false

        fun setHostForeground(active: Boolean) {
            hostForeground = active
            currentService?.let { service ->
                if (active) {
                    service.mainHandler.removeCallbacks(service.autoTick)
                    service.stopAutoMapGuide("host-foreground")
                    service.autoDetector?.reset()
                } else service.scheduleAutoMapOpen(0L)
            }
        }

        /** Read on the main thread at check time; persisted startup history is not visibility. */
        fun isControlOverlayVisible(): Boolean = currentService?.let { service ->
            service.window.isAdded() && service.balls?.let { it.isAttachedToWindow && it.isShown } == true
        } == true

        /** Main-thread lifecycle call; do not toggle the user's persisted visibility. */
        fun setPracticeForeground(active: Boolean) {
            practiceForeground = active
            if (active) currentService?.cancelAlignment()
            currentService?.applyPracticeVisibility()
        }

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
    @Volatile private var alignmentGeneration = 0
    private var aligning = false
    private var activeAlignmentCancellation: AlignmentCancellation? = null
    private var alignmentCapturing = false
    private var alignmentCaptureBounds: Rect? = null
    private var activeAlignmentTrace: AlignmentTrace? = null
    private var pendingAlignmentStart: Runnable? = null
    private var abortAlignmentCapture: (() -> Unit)? = null
    private var alignmentNotice = -1L
    private var lastAlignmentRequestId: String? = null
    private var lastAlignmentTerminal = "idle"
    private var notifications: OverlayNotifications? = null
    private var alignedGuideBounds: RectF? = null
    private var alignedGuideViewport: RectF? = null
    private var balls: OverlayBallView? = null
    private val autoReferenceStore by lazy { com.idvb.android.alignment.AutoMapOpenReferenceStore(this) }
    private val autoDiagnostics by lazy { com.idvb.android.alignment.AutoMapOpenDiagnostics(this) }
    private var autoReference: com.idvb.android.alignment.AutoMapOpenReference? = null
    private var autoDetector: com.idvb.android.alignment.AutoMapOpenDetector? = null
    private var autoReferenceScreen = ""
    private var autoContext = ""
    private var autoGuideOwned = false
    private var guidePreparationKey: String? = null
    private var guidePreparationFailedKey: String? = null
    private var autoOpeningFrameNanos = 0L
    private var autoStaticResume = false
    private var lastAutoSampleId = ""
    private val guidePreparationExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "idvb-guide-prepare") }
    private val autoFrameCallbackQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var autoBlocked = false
    private var autoCaptureBusy = false
    private var autoCaptureGeneration = 0
    private var abortAutoCapture: (() -> Unit)? = null
    private var lastAutoFrameMs = 0L
    private var autoPauseReason = ""
    private var autoPreviousMapFrame: com.idvb.android.alignment.PreparedMapFrame? = null
    private val autoTick = Runnable { pollAutoMapOpen() }
    private var readyReferenceKey = ""
    private var readyReference: com.idvb.android.alignment.MapFrameSignature? = null
    private var assistEditorWindow: OverlayWindowManager? = null
    private var assistController: AssistTouchController? = null
    private var assistClickPoints: List<Float> = emptyList()
    private var assistClickScreen = 0 to 0
    private data class AssistClickEvidence(val id: String, val start: Long, val end: Long, val x: Float, val y: Float)
    private var assistOpenEvidence: AssistClickEvidence? = null
    private var assistOpenId = ""
    private var buttonLayout: OverlayButtonLayout? = null
    private var ballMenu: OverlayBallMenuWindow? = null
    private var blueprintView: BlueprintCalibrationView? = null
    private var adjustView: BlueprintImageAdjustView? = null
    private var candidateView: CandidateSelectionView? = null
    private var candidateResult: RecognitionResult? = null
    private var captureSession: ScreenCaptureSession? = null
    private var captureMethod = AppServices.prefs.screenCaptureMethod
    private var lastCaptureScreen: Pair<Int, Int>? = null
    private var destroyed = false
    private val capturePreferencesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "auto_detect_map_open_enabled") mainHandler.post {
            if (!destroyed) {
                stopAutoMapGuide("setting-changed")
                autoDetector?.reset(); autoDetector = null; autoContext = ""
                if (AppServices.prefs.autoDetectMapOpenEnabled) scheduleAutoMapOpen(0L)
                else mainHandler.removeCallbacks(autoTick)
            }
        }
        if (key == "screen_capture_method") mainHandler.post {
            if (!destroyed) {
                stopAutoMapGuide("capture-method-changed")
                autoDetector = null; autoContext = ""
                synchronizeCaptureSession()
                scheduleAutoMapOpen(0L)
            }
        }
        if (key in setOf("show_routes", "route_line_thickness", "remove_guide_background")) mainHandler.post {
            if (!destroyed) {
                if (guideVisible) loadGuideFloor() else prewarmGuideFloor()
            }
        }
        if (key == "selected_map_class_id") {
            val changedGeneration = scanGeneration
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (!destroyed) cancelCaptureScan()
            } else mainHandler.post {
                // A delayed setting callback must not cancel a newer scan.
                if (!destroyed && scanGeneration == changedGeneration) cancelCaptureScan()
            }
        }
        if (key in setOf("eye_button_action", "alignment_method_id") ||
            key?.startsWith("capture_") == true) mainHandler.post {
            if (!destroyed) cancelAlignment()
        }
    }

    private fun cancelCaptureScan() {
        cancelAlignment()
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
        if (!com.idvb.android.UsageConsent.isAccepted(this)) return
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
            captureSession = ScreenCaptureSession(this).also { session ->
                session.onFrameAvailable = {
                    if (autoFrameCallbackQueued.compareAndSet(false, true)) mainHandler.post {
                        autoFrameCallbackQueued.set(false)
                        if (!autoCaptureBusy && !destroyed) scheduleAutoMapOpen(0L)
                    }
                }
                session.onStopped = { mainHandler.post {
                    if (!destroyed) stopAutoMapGuide("screen-capture-authorization-expired")
                } }
                session.start(size.first, size.second)
            }
        }
        Log.d("IDVBCapture", "source=$method grant=${ScreenCaptureGrant.revision} session=${captureSession != null}")
    }
    private var guideView: GuideMapView? = null
    private var guideBitmap: Bitmap? = null
    private var guideBitmapKey: String? = null
    private var guideVisible = false
    private var alignmentDisplayReady = true
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

    private val sessionLogs by lazy { com.idvb.android.diagnostics.SessionLogRecorder(java.io.File(filesDir, "idvb/diagnostics")) }

    override fun onCreate() {
        super.onCreate()
        currentService = this
        if (com.idvb.android.UsageConsent.isAccepted(this)) {
            sessionLogs.begin()
            AppServices.scanPreparation.request()
        }
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
        notifications = OverlayNotifications(overlayContext, ::screenSize)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        AccessibilityScreenCaptureService.foregroundChangedListener = ::handleAutoForegroundChanged
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(); startForegroundCompat()
        if (!com.idvb.android.UsageConsent.isAccepted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CLOSE) synchronizeCaptureSession()
        when (intent?.action) {
            ACTION_CONFIGURE_AUTO_MAP_REFERENCE -> {
                ensureBalls()
                stopAutoMapGuide("reference-configuration")
                notifyOverlay("请返回游戏并打开地图，再点悬浮窗“···”中的“设置开图参照”", 8_000L)
            }
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
                if (!visible) cancelAlignment()
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
        if (intent?.action == null && OverlayState.state.value.running) {
            com.idvb.android.tutorial.TutorialStore.get(this).update { it.copy(serviceStarted = true) }
        }
        applyPracticeVisibility()
        scheduleAutoMapOpen(0L)
        return START_STICKY
    }

    private fun applyPracticeVisibility() {
        val visible = !practiceForeground && OverlayState.state.value.visible
        if (!visible && (assistController?.active == true || assistController?.busy == true || assistEditorWindow != null)) {
            assistController?.cancel()
            assistEditorWindow?.remove(); assistEditorWindow = null
        }
        balls?.visibility = if (visible && !scanning) android.view.View.VISIBLE else android.view.View.INVISIBLE
        balls?.setCaptureHidden(alignmentCapturing, alignmentCaptureBounds)
        notifications?.setCaptureHidden(alignmentCapturing)
        notifications?.setHidden(!visible || scanning)
        guideView?.let { view ->
            val show = visible && guideVisible && alignmentDisplayReady && !scanning && adjustView == null
            // A root may just have become INVISIBLE while its previous surface buffer is
            // still on screen (close/reopen in one UI turn). Submit a transparent buffer from
            // every attached guide root instead of treating isShown=false as a draw receipt.
            view.alpha = if (alignmentCapturing) 0f else 1f
            val captureRoot = alignmentCapturing && view.isAttachedToWindow
            view.visibility = if (visible && guideVisible && (captureRoot || show)) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
        listOf(blueprintView, adjustView, candidateView, scanProgressView).forEach {
            it?.visibility = if (practiceForeground) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    private fun notifyOverlay(
        message: String,
        durationMs: Long = 4_000L,
        tone: NotificationTone = NotificationTone.DEFAULT,
    ) {
        if (!destroyed) notifications?.show(message, durationMs, tone)
    }

    private fun showAlignmentNotice(message: String, durationMs: Long = 4_000L): Long {
        if (destroyed || !AppServices.prefs.showAlignmentOutput) return -1L
        return notifications?.show(message, durationMs) ?: -1L
    }

    private fun updateAlignmentNotice(id: Long, message: String, durationMs: Long = 4_000L) {
        if (destroyed || !AppServices.prefs.showAlignmentOutput) return
        notifications?.update(id, message, durationMs)
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
        prewarmGuideFloor()
        // 收起菜单时窗口必须与小球同高，避免不可见的透明区域拦截底层应用触摸。
        val hasVariants = catalog.nextVariantMapId(currentMap?.id.orEmpty()) != null
        window.width = ((if (hasVariants) 264 else 212) * density).toInt(); window.height = (48 * density).toInt()
        window.x = (screen.first - window.width).coerceAtLeast(0); window.y = (screen.second * .28f).toInt()
        balls = OverlayBallView(overlayContext).apply {
            ballMenu = OverlayBallMenuWindow(overlayContext, window, morePanel, ::screenSize)
            mapLocked = currentMap != null
            identityVerified = currentMap?.let {
                AppServices.prefs.lastMapIdentitySource == MapIdentitySource.STRUCTURE_VERIFIED
            }
            floorLabel = orderedFloors.getOrNull(floorIndex)?.displayName ?: "--"
            variantsAvailable = hasVariants
            listener = object : OverlayBallView.Listener {
                override fun onSearch() {
                    when (AppServices.prefs.searchButtonAction) {
                        SearchButtonAction.SCAN_MAP -> runForegroundScan()
                    }
                }
                override fun useAssistTouchToggle() = false
                override fun onAssistTouch() {}
                override fun onToggleGuide() {
                    if (!showPendingCandidates()) toggleGuide()
                }
                override fun onOpenGuide() = openGuide()
                override fun onCloseGuide() = hideGuide()
                override fun onNextFloor() = nextFloor()
                override fun onNextVariant() = nextVariant()
                override fun onFreeAdjust() = enterFreeAdjustMode()
                override fun onCalibrate() = enterBlueprintMode()
                override fun onConfigureAutoReference() {
                    notifyOverlay("已内置开图识别参照，无需手动截取设置")
                }
                override fun onOutsideTouch() = handleOutsideTouch()
                override fun onScreenTouch(x: Float, y: Float) = handleOutsideTouch()
                override fun onResetMap() = resetMapIdentity()
                override fun onChooseMapClass() = toggleMapClassSubmenu()
                override fun onClose() = stopSelf()
                override fun onMenuExpanded(expanded: Boolean) {
                    if (expanded) {
                        // The transparent more-button hit target can still be tapped during
                        // capture. Cancel before introducing another independent visible window.
                        if (alignmentCapturing) cancelAlignment()
                        ballMenu?.show()
                    } else ballMenu?.hide()
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
                    ballMenu?.updatePosition()
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
        balls!!.post { if (!destroyed) buttonLayout = OverlayButtonLayout(overlayContext, balls!!, window, ::screenSize) }
        lastCaptureScreen = screen
        OverlayState.update { it.copy(running = true, visible = true, locked = false) }
    }

    private fun runForegroundScan() {
        if (autoGuideOwned) { autoDetector?.manualClose(); stopAutoMapGuide("manual-scan") }
        val diagnosticSessionId = sessionLogs.sessionId
        if (candidateView != null || blueprintView != null || adjustView != null) return
        if (aligning) {
            notifyOverlay("正在自动贴合，请稍候")
            return
        }
        if (scanning) {
            notifyOverlay("正在扫描地图，请稍候")
            return
        }
        if (AppServices.prefs.debugMode) {
            if (AppServices.prefs.manualMapSelectionEnabled) showManualMapSelection() else showDebugCandidates()
            return
        }
        synchronizeCaptureSession()
        clearPendingCandidates()
        val size = screenSize()
        val region = captureRegionPixels(size.first, size.second)
        if (region == null) {
            notifyOverlay("请先在 ··· 中校准显示区域")
            return
        }
        val method = AppServices.prefs.screenCaptureMethod
        val session = captureSession
        if (method == ScreenCaptureMethod.MEDIA_PROJECTION && (session == null || !session.start(size.first, size.second))) {
            notifyOverlay("屏幕捕获授权已失效，请返回 IDVB 重新授权", 6_000L); return
        }
        if (method == ScreenCaptureMethod.ACCESSIBILITY && !AccessibilityScreenCaptureService.available) {
            notifyOverlay("IDVB 无障碍服务未启用，请返回应用授权", 6_000L); return
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
                        processCapturedFrame(captured, region, size, generation, diagnosticSessionId)
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
            notifyOverlay("当前关卡模式下没有可选择地图")
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
        generation: Int, diagnosticSessionId: String?) {
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
                notifyOverlay("截图失败：${error.message}", 6_000L)
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
                // One immutable catalog/class snapshot belongs to this captured frame.
                // Imports and class changes must not relabel or commit an older scan.
                val scanCatalog = AppServices.repository.loadCatalog()
                val scanClassId = AppServices.prefs.selectedMapClassId
                    ?.takeIf { id -> scanCatalog.classes.any { it.id == id } }
                    ?: scanCatalog.classes.firstOrNull()?.id
                val accepted = recognitionExecutor.execute {
                    val scanStarted = System.nanoTime()
                    val recognition = runCatching {
                        reportScanProgress(generation, .10, "正在准备扫描…")
                        val viewport = ScreenRect(region.left.toDouble(), region.top.toDouble(),
                            region.width().toDouble(), region.height().toDouble())
                        com.idvb.android.recognize.MapScanRecognizer(this, AppServices.repository).recognize(
                            bitmap, viewport, size.first, size.second, scanClassId,
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
                                    classId = scanClassId,
                                    sessionId = diagnosticSessionId,
                                    className = scanCatalog.classes.firstOrNull { it.id == scanClassId }?.name,
                                ),
                            )
                        }.getOrElse { Result.failure(it) } }
                    } else null
                    mainHandler.post {
                        completeCapturedScan(bitmap,generation,scanCatalog,scanClassId,
                            recognition.getOrNull(),recognition.exceptionOrNull(),diagnostics?.exceptionOrNull())
                    }
                }
                if (!accepted) {
                    mainHandler.post {
                        bitmap.recycle()
                        if (generation != scanGeneration) return@post
                        scanning = false
                        finishScanProgress(generation, ScanOutcome.FAILED)
                        balls?.visibility = android.view.View.VISIBLE
                        if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                        notifyOverlay("识别线程已停止", 6_000L)
                    }
                }
    }

    /** Single main-thread consumer for both a queued worker and an invalidated capture. */
    private fun completeCapturedScan(bitmap: Bitmap, generation: Int,
        scanCatalog: com.idvb.android.idvm.MapCatalogDocument, scanClassId: String?,
        recognition: RecognitionResult?, failure: Throwable?, diagnosticFailure: Throwable?) {
        if (generation != scanGeneration) {
            bitmap.recycle()
            return
        }
        val currentCatalog = AppServices.repository.loadCatalog()
        val currentClassId = AppServices.prefs.selectedMapClassId
            ?.takeIf { id -> currentCatalog.classes.any { it.id == id } }
            ?: currentCatalog.classes.firstOrNull()?.id
        if (scanCatalog !== currentCatalog || scanClassId != currentClassId) {
            Log.i("IDVB-Scan", "discard request=$generation reason=scan-context-changed class=$scanClassId currentClass=$currentClassId")
            cancelCaptureScan()
            bitmap.recycle()
            return
        }
        scanning = false
        diagnosticFailure?.let { notifyOverlay("识别诊断保存失败：${it.message}", 6_000L) }
        if (recognition != null) handleScanResult(recognition) else {
            finishScanProgress(generation, ScanOutcome.FAILED)
            bitmap.recycle()
            notifyOverlay("扫描失败：${failure?.message}", 6_000L)
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

    private fun finishScanProgress(generation: Int, outcome: ScanOutcome) {
        if (generation != scanGeneration) return
        val view = scanProgressView ?: return
        view.finish(outcome)
        mainHandler.postDelayed({
            if (generation == scanGeneration && scanProgressView === view) hideScanProgress()
        }, 2400L)
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
            notifyOverlay("当前关卡模式下没有可扫描地图")
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
        showCandidates(RecognitionResult(preview, high + references + remaining))
    }

    private fun handleScanResult(result: RecognitionResult) {
        val confirmed = result.automaticallyConfirmedCandidate()
        if (confirmed != null) {
            clearPendingCandidates()
            candidateResult = result
            if (lockSelectedMap(confirmed)) finishScanProgress(scanGeneration, ScanOutcome.LOCKED)
            else {
                closeCandidates(recycleCapture = true)
                finishScanProgress(scanGeneration, ScanOutcome.NO_RESULT)
            }
            return
        }
        if (AppServices.prefs.showUnconfirmedCandidates) {
            // Background scans retain candidates until the eye button is pressed.
            // Missing structural assets still permit explicit catalog selection.
            val catalog = AppServices.repository.loadCatalog()
            val included = result.candidates.mapTo(HashSet()) { it.map.id }
            val remaining = catalog.maps.filter {
                it.classId == resolveActiveClassId(catalog) && it.id !in included
            }.map { map -> RecognitionCandidate(map, map.floors.minByOrNull { it.sortOrder }?.key.orEmpty(),
                CandidateDisposition.CATALOG_ONLY, evidenceLabel = "目录手选 · 结构未确认") }
            val candidates = result.copy(candidates = result.candidates + remaining)
            if (AppServices.prefs.backgroundScanEnabled) {
                closeCandidates(recycleCapture = true)
                candidateResult = candidates
                balls?.candidatesAvailable = true
                notifyOverlay("扫描候选已就绪，点击 👁 查看")
            } else {
                showCandidates(candidates)
            }
            finishScanProgress(scanGeneration, ScanOutcome.CANDIDATES)
            return
        }
        closeCandidates(recycleCapture = true)
        if (!result.capturedRegion.isRecycled) result.capturedRegion.recycle()
        finishScanProgress(scanGeneration, ScanOutcome.NO_RESULT)
        val retainedMap = if (currentMap != null) "；仍保留原地图" else ""
        notifyOverlay("未识别到地图：当前可见结构不足以确认，请调整后重扫$retainedMap", 6_000L)
    }

    private fun toggleMapClassSubmenu() {
        if (ballMenu?.isSubmenuVisible() == true) {
            ballMenu?.hideSubmenu()
            return
        }
        val catalog = AppServices.repository.loadCatalog()
        val availableIds = catalog.maps.mapTo(mutableSetOf()) { it.classId }
        val available = catalog.classes.filter { it.id in availableIds }
        if (available.isEmpty()) {
            notifyOverlay("没有可用的地图包，请先导入地图")
            return
        }
        ballMenu?.toggleClasses(available, resolveActiveClassId(catalog)) { selected ->
            if (AppServices.prefs.selectedMapClassId == selected.id) {
                notifyOverlay("当前地图包：${selected.name}")
                return@toggleClasses
            }
            cancelAlignment()
            resetMapIdentity(notify = false)
            AppServices.prefs.selectedMapClassId = selected.id
            notifyOverlay("已选择地图包：${selected.name}，请重新扫描")
        }
    }

    private fun resetMapIdentity(notify: Boolean = true) {
        assistController?.cancel()
        cancelCaptureScan()
        sessionLogs.begin()
        alignedGuideBounds = null
        alignedGuideViewport = null
        guideView?.clearAlignment()
        closeCandidates(recycleCapture = true)
        blueprintWindow.remove()
        blueprintView = null
        adjustView = null
        currentMap = null
        floorIndex = 0
        AppServices.prefs.lastMapId = null
        AppServices.prefs.lastFloorKey = null
        guideVisible = false
        guideView?.visibility = android.view.View.INVISIBLE
        guideView?.showBitmap(null)
        guideBitmap?.let { if (!it.isRecycled) it.recycle() }
        guideBitmap = null
        balls?.apply {
            mapLocked = false
            identityVerified = null
            floorLabel = "--"
            guideVisible = false
            variantsAvailable = false
        }
        resizeControls(false)
        OverlayState.update { it.copy(mapTitle = null, floorLabel = null) }
        if (notify) notifyOverlay("地图身份已重置，请重新扫描或选择地图")
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
                    lockSelectedMap(candidate, fromCandidateUi = true)
                }
                override fun onCancelled() = closeCandidates(recycleCapture = true)
            }
        }
        candidateWindow.add(candidateView!!, locked = false)
    }

    private fun lockSelectedMap(
        candidate: com.idvb.android.recognize.RecognitionCandidate,
        fromCandidateUi: Boolean = false,
    ): Boolean {
        val catalog = AppServices.repository.loadCatalog()
        val activeClassId = AppServices.prefs.selectedMapClassId
            ?.takeIf { id -> catalog.classes.any { it.id == id } }
            ?: catalog.classes.firstOrNull()?.id
        val latestMap = catalog.maps.firstOrNull { it.id == candidate.map.id }
        if (latestMap == null || latestMap != candidate.map || candidate.map.classId != activeClassId ||
            latestMap.floors.none { it.key == candidate.floorKey }) {
            notifyOverlay("地图资料或关卡已更新，请重新选择地图", 6_000L)
            return false
        }
        if (fromCandidateUi) cancelCaptureScan() else cancelAlignment()
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
        val hasVariants = catalog.findVariantGroup(candidate.map.id) != null
        balls?.variantsAvailable = hasVariants
        resizeControls(hasVariants)
        refreshGuideSelection()
        closeCandidates(recycleCapture = true)
        if (fromCandidateUi && hasVariants) {
            notifyOverlay("你选择了一张变体地图，如果贴合异常请尝试切换变体（⇆）", tone = NotificationTone.WARNING)
        } else {
            val message = if (identitySource == MapIdentitySource.STRUCTURE_VERIFIED) {
                "结构已确认并锁定：${candidate.map.title}"
            } else {
                "已人工选择：${candidate.map.title}（结构未确认）"
            }
            notifyOverlay(message)
        }
        return true
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
        if (guideVisible) hideGuide() else openGuide()
    }

    private fun assistEnabled(): Boolean = false

    private fun editAssistTouch() {
        if (scanning || blueprintView != null || adjustView != null) {
            notifyOverlay("请先完成当前扫描或调整"); return
        }
        assistController?.cancel()
        assistEditorWindow?.remove()
        val size = screenSize()
        val store = AssistTouchStore(this)
        val editorWindow = OverlayWindowManager(overlayContext)
        val editor = AssistTouchEditorView(overlayContext, store.load(size.first > size.second)) { points ->
            store.save(size.first > size.second, points)
            editorWindow.remove(); assistEditorWindow = null
            notifyOverlay(if (points.isEmpty()) "辅助触控已重置" else "辅助触控已保存：按住激活开启时，点击 👁 开图，再次点击关图")
        }
        editor.setOnKeyListener { _, key, event ->
            if (key == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                editorWindow.remove(); assistEditorWindow = null; true
            } else false
        }
        editorWindow.width = size.first; editorWindow.height = size.second
        assistEditorWindow = editorWindow
        editorWindow.add(editor, locked = false, focusable = true)
        editor.requestFocus()
    }

    private fun toggleAssistTouch() {
        if (scanning || blueprintView != null || adjustView != null || assistEditorWindow != null || currentMap == null) return
        if (assistController?.busy == true) return
        if (assistController == null) assistController = AssistTouchController(
            click = { opening, done ->
                val index = if (opening) 0 else 2
                val started = System.nanoTime()
                val id = java.util.UUID.randomUUID().toString()
                if (opening) assistOpenId = id
                val x = (assistClickPoints[index] * assistClickScreen.first).coerceAtMost((assistClickScreen.first - 1).toFloat())
                val y = (assistClickPoints[index + 1] * assistClickScreen.second).coerceAtMost((assistClickScreen.second - 1).toFloat())
                val overlapsControl = balls?.let { controls ->
                    (controls.buttons.values + controls.moreButton).any { button ->
                        if (!button.isShown) false else {
                            val location = IntArray(2).also(button::getLocationOnScreen)
                            x >= location[0] && x < location[0] + button.width &&
                                y >= location[1] && y < location[1] + button.height
                        }
                    }
                } == true
                AppServices.alignmentDiagnostics.recordLifecycle(id, "assist.click-start", if (opening) "open" else "close",
                    mapOf("x" to x.toDouble(), "y" to y.toDouble()), sessionLogs.sessionId)
                val clicked: (Boolean) -> Unit = { success ->
                    val ended = System.nanoTime()
                    if (opening && id == assistOpenId) assistOpenEvidence = if (success) AssistClickEvidence(id, started, ended, x, y) else null
                    AppServices.alignmentDiagnostics.recordLifecycle(id, "assist.click-end", "success=$success",
                        mapOf("durationMs" to (ended - started) / 1e6), sessionLogs.sessionId)
                    done(success)
                }
                if (overlapsControl) {
                    clicked(false)
                    notifyOverlay("辅助触控位置与悬浮按钮重叠，请移动悬浮按钮或重新设置")
                } else AccessibilityScreenCaptureService.click(x, y, clicked)
            },
            show = { if (!destroyed) openGuide() },
            hide = { hideGuide() },
            failed = { notifyOverlay("辅助点击未完成，请检查无障碍服务或重新设置位置") },
        )
        assistClickScreen = screenSize()
        assistClickPoints = AssistTouchStore(this).load(assistClickScreen.first > assistClickScreen.second)
        if (validAssistPoints(assistClickPoints)) assistController?.toggle()
    }

    private fun hideGuide(manual: Boolean = true) {
        if (manual) {
            autoDetector?.manualClose()
            autoDiagnostics.event("manual-close", autoReference, sessionLogs.sessionId, lastAlignmentRequestId)
        }
        autoGuideOwned = false
        if (!guideVisible && !aligning) return
        val actionStarted = System.nanoTime()
        val previous = lastAlignmentTerminal
        guideVisible = false
        balls?.guideVisible = false
        cancelAlignment()
        lastAlignmentRequestId?.let { AppServices.alignmentDiagnostics.recordLifecycle(it, "eye.hidden", previous,
            mapOf("uiResponseMs" to (System.nanoTime() - actionStarted) / 1e6)) }
        if (previous == "drawn" || previous in setOf("rejected", "unavailable", "idle")) {
            updateAlignmentNotice(alignmentNotice, if (previous == "drawn") "贴合已成功，现已隐藏" else "已关闭贴合显示", 2_500L)
        }
    }

    private fun openGuide() {
        val replaceAutomaticRequest = autoGuideOwned && (aligning || !alignmentDisplayReady)
        autoDetector?.manualClose()
        autoGuideOwned = false
        if (replaceAutomaticRequest) {
            // Manual opening takes ownership of pending work as well as the visible surface.
            guideVisible = false
            balls?.guideVisible = false
            cancelAlignment()
        }
        if (blueprintView != null || adjustView != null || assistEditorWindow != null || scanning) {
            notifyOverlay("请先完成当前扫描或调整")
            return
        }
        if (showPendingCandidates()) return
        if (currentMap == null || guideVisible) return
        val clicked = System.nanoTime()
        val autoAlign = AppServices.prefs.eyeButtonAction == EyeButtonAction.SHOW_AND_ALIGN
        if (autoAlign) {
            // A new open always replaces old work, including a callback still waiting on the UI thread.
            cancelAlignment()
            alignedGuideBounds = null
            alignedGuideViewport = null
            guideView?.clearAlignment()
        }
        if (showGuide(renderGuide = !autoAlign) { code, reason -> if (autoAlign) recordAlignmentPreflightFailure(clicked, code, reason) } && autoAlign) {
            alignGuide(clicked, System.nanoTime() - clicked)
        }
    }

    /** Explicit display-only entry used by manual adjustment as well as the eye action. */
    private fun showGuide(renderGuide: Boolean = true, onUnavailable: (String, String) -> Unit = { _, _ -> }): Boolean {
        val size = screenSize()
        val region = guideRegionPixels(size.first, size.second)
        if (region == null) {
            notifyOverlay("请先校准显示区域")
            onUnavailable("display-region-missing", "请先校准显示区域")
            return false
        }
        if (!loadGuideFloor()) {
            notifyOverlay("当前楼层图片无法读取")
            onUnavailable("guide-image-unavailable", "当前楼层图片无法读取")
            return false
        }
        if (renderGuide) {
            applyGuidePlacement(region)
        }
        alignmentDisplayReady = renderGuide
        guideVisible = true
        balls?.guideVisible = true
        applyPracticeVisibility()
        return true
    }

    private fun recordAlignmentPreflightFailure(clicked: Long, code: String, reason: String) {
        alignmentNotice = showAlignmentNotice("自动贴合未开始：$reason", 6_000L)
        val map = currentMap ?: return
        val screen = screenSize()
        val region = captureRegionPixels(screen.first, screen.second)
        val trace = AlignmentTrace(clicked, AppServices.prefs.alignmentReplayInputsEnabled)
        lastAlignmentRequestId = trace.id
        lastAlignmentTerminal = "unavailable"
        AppServices.alignmentDiagnostics.recordLifecycle(trace.id, "request.preflight", code, sessionId = sessionLogs.sessionId)
        trace.emit(AlignmentLogEvent("request.preflight", reason, labels = mapOf("code" to code)))
        trace.emit(AlignmentLogEvent("request.total", "unavailable", durationNanos = System.nanoTime() - clicked))
        val viewport = region?.let { ScreenRect(it.left.toDouble(), it.top.toDouble(), it.width().toDouble(), it.height().toDouble()) }
            ?: ScreenRect(0.0, 0.0, 0.0, 0.0)
        val context = AlignmentDiagnosticContext(AppServices.prefs.alignmentMethodId, map,
            map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex)?.key.orEmpty(), viewport,
            screen.first, screen.second, AppServices.prefs.screenCaptureMethod.name, sessionLogs.sessionId)
        AppServices.alignmentDiagnostics.recordAsync(trace, context, null, AlignmentResult.Unavailable(reason, code), "unavailable") {
            it.onFailure { error -> Log.e("IDVB-Align", "request=${trace.id} preflight diagnostics save failed", error) }
        }
    }

    private fun cancelAlignment() {
        val pending = activeAlignmentCancellation?.isCancelled == false && activeAlignmentTrace != null
        activeAlignmentCancellation?.cancel()
        activeAlignmentTrace?.emit(AlignmentLogEvent("request.cancelled", "state-changed"))
        activeAlignmentTrace?.let { AppServices.alignmentDiagnostics.recordLifecycle(it.id, "cancel.requested", "state-changed") }
        if (aligning) Log.i("IDVB-Align", "request=$alignmentGeneration stage=cancelled detail=state-changed")
        alignmentGeneration++
        aligning = false
        alignmentCapturing = false
        if (pending) updateAlignmentNotice(alignmentNotice, "已关闭显示，正在取消本次贴合…", 4_000L)
        abortAlignmentCapture?.invoke()
        abortAlignmentCapture = null
        applyPracticeVisibility()
    }

    private fun finishAutoAlignment(
        requestDetector: com.idvb.android.alignment.AutoMapOpenDetector?,
        requestContext: String,
        cycle: Long,
        requestId: String,
        success: Boolean,
    ) {
        // Pausing and resuming can start a new request in the same physical opening.
        // A cycle token alone cannot distinguish that request from a late cancelled one.
        if (lastAlignmentRequestId == requestId && autoDetector === requestDetector && autoContext == requestContext) {
            requestDetector?.alignmentFinished(success, android.os.SystemClock.uptimeMillis(), cycle)
            if (!success && requestDetector != null) mainHandler.postDelayed({
                if (!destroyed && autoDetector === requestDetector && autoContext == requestContext && requestDetector.isOpen) {
                    autoStaticResume = true
                    scheduleAutoMapOpen(0L)
                }
            }, requestDetector.config.retryCooldownMs)
        }
    }

    private fun isAlignmentCatalogCurrent(catalog: com.idvb.android.idvm.MapCatalogDocument,
        map: MapRecord, floor: com.idvb.android.idvm.FloorRecord): Boolean =
        AppServices.repository.loadCatalog() === catalog && catalog.maps.any { current ->
            current == map && current.floors.any { it == floor }
        }

    /** All async callbacks must still belong to this visible map, floor, screen and user action. */
    private fun alignGuide(clicked: Long, displayPreparationNanos: Long, autoCycle: Long? = null,
        preparedFrames: MutableList<com.idvb.android.alignment.PreparedMapFrame> = mutableListOf()) {
        val requestDetector = autoDetector
        val requestAutoContext = autoContext
        val requestAutoReference = autoReference
        if (!com.idvb.android.UsageConsent.isAccepted(this) || practiceForeground || scanning || aligning) return
        val map = currentMap ?: return
        val floor = map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex) ?: run {
            recordAlignmentPreflightFailure(clicked, "floor-unavailable", "当前楼层不存在"); return
        }
        val catalogSnapshot = AppServices.repository.loadCatalog()
        if (!isAlignmentCatalogCurrent(catalogSnapshot,map,floor)) {
            recordAlignmentPreflightFailure(clicked, "map-or-floor-catalog-changed", "地图资料已更新，请重新选择地图"); return
        }
        val screen = screenSize()
        val region = captureRegionPixels(screen.first, screen.second) ?: run {
            recordAlignmentPreflightFailure(clicked, "capture-region-missing", "截图区域尚未校准"); return
        }
        val bounds = Rect(region.left.toInt().coerceIn(0, screen.first), region.top.toInt().coerceIn(0, screen.second),
            region.right.toInt().coerceIn(0, screen.first), region.bottom.toInt().coerceIn(0, screen.second))
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            recordAlignmentPreflightFailure(clicked, "capture-region-empty", "截图区域不在屏幕内"); return
        }
        val method = AppServices.prefs.screenCaptureMethod
        val methodId = AppServices.prefs.alignmentMethodId
        val viewport = ScreenRect(bounds.left.toDouble(), bounds.top.toDouble(), bounds.width().toDouble(), bounds.height().toDouble())
        val trace = AlignmentTrace(clicked, AppServices.prefs.alignmentReplayInputsEnabled, AlignmentLogSink { event ->
            Log.i("IDVB-Align", "request=${event.labels["requestId"]} method=$methodId map=${map.id} floor=${floor.key}" +
                " stage=${event.stage} durationMs=${event.durationNanos?.div(1e6)} detail=${event.detail} metrics=${event.measurements}")
        })
        preparedFrames.removeAll { frame ->
            val now = android.os.SystemClock.uptimeMillis()
            val invalid = method.name != frame.captureMethod || !frame.isFreshFor(bounds, now,
                requestDetector?.config?.maximumFrameAgeMs ?: 1_000L)
            if (invalid) {
                trace.emit(AlignmentLogEvent("capture.prepared-frame.discarded", "stale-viewport-or-capture-method-changed",
                    measurements = mapOf("ageMs" to (now - frame.captureStartedAtMs).toDouble(),
                        "left" to frame.bounds.left.toDouble(), "top" to frame.bounds.top.toDouble(),
                        "width" to frame.bounds.width().toDouble(), "height" to frame.bounds.height().toDouble()),
                    thresholds = mapOf("maximumFrameAgeMs" to (requestDetector?.config?.maximumFrameAgeMs ?: 1_000L).toDouble()),
                    labels = mapOf("captureMethod" to method.name)))
                frame.recycle()
            }
            invalid
        }
        val hasPreparedFrame = preparedFrames.isNotEmpty()
        var alignmentWorkNanos = 0L
        val diagnosticContext = AlignmentDiagnosticContext(methodId, map, floor.key, viewport, screen.first, screen.second, method.name, sessionLogs.sessionId)
        val cancellation = AlignmentCancellation()
        val assistEvidence = assistOpenEvidence.takeIf { assistController?.active == true }
        assistEvidence?.let {
            trace.emit(AlignmentLogEvent("assist.open-click", "completed-before-capture", timestampNanos = it.end,
                durationNanos = it.end - it.start, labels = mapOf("assistRequestId" to it.id),
                measurements = mapOf("x" to it.x.toDouble(), "y" to it.y.toDouble())))
        }
        val readinessKey = "${map.id}:${map.hashCode()}:${floor.key}:$bounds:$screen:$methodId"
        val reference = readyReference.takeIf { readyReferenceKey == readinessKey }
        var readySignature: com.idvb.android.alignment.MapFrameSignature? = null
        lastAlignmentRequestId = trace.id
        lastAlignmentTerminal = "starting"
        alignmentNotice = showAlignmentNotice("正在准备自动贴合…", 0L)
        val notice = alignmentNotice
        AppServices.alignmentDiagnostics.recordLifecycle(trace.id, "eye.shown", "alignment-requested", sessionId = diagnosticContext.sessionId)
        trace.emit(AlignmentLogEvent("display.prepare", durationNanos = displayPreparationNanos,
            timestampNanos = clicked + displayPreparationNanos))
        trace.emit(AlignmentLogEvent("request.context", trace.id,
            labels = mapOf("eyeAction" to AppServices.prefs.eyeButtonAction.name,
                "requestSource" to if (autoCycle == null) "manual-eye" else "auto-map-open",
                "autoOpenCycle" to (autoCycle?.toString() ?: "none"),
                "autoSampleId" to if (autoCycle == null) "none" else lastAutoSampleId,
                "autoReferenceId" to if (autoCycle == null) "none" else autoReference?.id.orEmpty(),
                "identitySource" to AppServices.prefs.lastMapIdentitySource.name,
                "showAlignmentOutput" to AppServices.prefs.showAlignmentOutput.toString()),
            measurements = mapOf("screenWidth" to screen.first.toDouble(), "screenHeight" to screen.second.toDouble(),
                "captureX" to viewport.x, "captureY" to viewport.y, "captureWidth" to viewport.width, "captureHeight" to viewport.height)))
        fun complete(outcome: AlignmentResult?, terminal: String, frame: Bitmap? = null) {
            if (trace.completed.get()) return
            val latency = com.idvb.android.alignment.AlignmentLatency.measure(clicked, System.nanoTime(), alignmentWorkNanos)
            trace.emit(AlignmentLogEvent("request.non-alignment", "capture-to-terminal-minus-alignment-call",
                durationNanos = (latency.overheadMs * 1e6).toLong(),
                measurements = mapOf("totalMs" to latency.totalMs, "alignmentMs" to latency.alignmentMs,
                    "nonAlignmentMs" to latency.overheadMs),
                thresholds = if (autoCycle != null && method == ScreenCaptureMethod.MEDIA_PROJECTION)
                    mapOf("maximumNonAlignmentMs" to com.idvb.android.alignment.AutoMapOpenTiming.NON_ALIGNMENT_BUDGET_MS.toDouble()) else emptyMap(),
                labels = mapOf("captureMethod" to method.name, "budgetResult" to
                    if (autoCycle == null || method != ScreenCaptureMethod.MEDIA_PROJECTION) "not-applicable"
                    else if (latency.withinBudget) "passed" else "exceeded",
                    "timingOrigin" to if (autoCycle == null) "eye-click" else "observed-open-frame-received")))
            if (autoCycle != null) {
                finishAutoAlignment(requestDetector, requestAutoContext, autoCycle, trace.id,
                    outcome is AlignmentResult.Aligned && terminal == "drawn")
                autoDiagnostics.event("alignment-$terminal", requestAutoReference, diagnosticContext.sessionId, trace.id)
            }
            if (lastAlignmentRequestId == trace.id) lastAlignmentTerminal = terminal
            AppServices.alignmentDiagnostics.recordLifecycle(trace.id, "request.completed", terminal,
                mapOf("totalMs" to (System.nanoTime() - clicked) / 1e6))
            // Each request owns a card. Finalize an older card in place without moving it
            // above a newer request or leaving a false "cancelling" status behind.
            if (!destroyed) {
                val elapsed = ((System.nanoTime() - clicked) / 1e6).toInt()
                when {
                    terminal == "drawn" -> updateAlignmentNotice(notice, "自动贴合成功 · ${elapsed}ms", 3_500L)
                    terminal == "cancelled-after-algorithm" -> updateAlignmentNotice(notice, "计算已结束，关闭后未显示结果", 3_500L)
                    terminal == "discarded-after-submit" -> updateAlignmentNotice(notice, "已关闭显示，本次绘制未确认", 3_500L)
                    terminal.startsWith("cancelled") -> updateAlignmentNotice(notice, "贴合已取消 · 已退出计算", 2_500L)
                    terminal == "submitted-without-observed-draw" -> updateAlignmentNotice(notice, "贴合已计算，但尚未确认绘制", 5_000L)
                }
            }
            if (activeAlignmentTrace === trace) activeAlignmentTrace = null
            if (activeAlignmentCancellation === cancellation) activeAlignmentCancellation = null
            trace.emit(AlignmentLogEvent("request.total", terminal, durationNanos = System.nanoTime() - clicked))
            assistEvidence?.let { trace.emit(AlignmentLogEvent("assist.click-to-terminal", terminal,
                durationNanos = System.nanoTime() - it.start, labels = mapOf("assistRequestId" to it.id))) }
            AppServices.alignmentDiagnostics.recordAsync(trace, diagnosticContext, frame, outcome, terminal) { saved ->
                saved.onFailure { error ->
                    Log.e("IDVB-Align", "request=${trace.id} diagnostics save failed", error)
                    mainHandler.post { if (!destroyed && lastAlignmentRequestId == trace.id)
                        updateAlignmentNotice(notice, "对齐诊断保存失败：${error.message}", 6_000L) }
                }
            }
        }
        fun unavailable(reason: String) { updateAlignmentNotice(notice, "自动贴合未完成：$reason", 6_000L) }
        trace.measure("capture.session-setup") { synchronizeCaptureSession() }
        activeAlignmentTrace = trace
        activeAlignmentCancellation = cancellation
        val session = captureSession
        if (method == ScreenCaptureMethod.MEDIA_PROJECTION && !trace.measure("capture.session-start") {
                session != null && session.start(screen.first, screen.second)
            }) {
            val reason = "屏幕捕获授权已失效，请返回 IDVB 重新授权"
            unavailable(reason); complete(AlignmentResult.Unavailable(reason, "capture-permission"), "unavailable"); return
        }
        if (method == ScreenCaptureMethod.ACCESSIBILITY && !AccessibilityScreenCaptureService.available) {
            val reason = "IDVB 无障碍服务未启用，请返回应用授权"
            unavailable(reason); complete(AlignmentResult.Unavailable(reason, "capture-permission"), "unavailable"); return
        }
        val generation = ++alignmentGeneration
        aligning = true
        alignmentCaptureBounds = bounds
        alignmentCapturing = true
        lastAlignmentTerminal = "capturing"
        fun current() = !destroyed && generation == alignmentGeneration && guideVisible &&
            OverlayState.state.value.visible && !practiceForeground && currentMap == map &&
            isAlignmentCatalogCurrent(catalogSnapshot,map,floor) &&
            map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex) == floor && screenSize() == screen &&
            (if (autoCycle == null) AppServices.prefs.eyeButtonAction == EyeButtonAction.SHOW_AND_ALIGN else
                autoGuideOwned && autoContext == requestAutoContext && autoDetector === requestDetector &&
                requestDetector?.isOpen == true && requestDetector.manualSuppressed == false &&
                AppServices.prefs.autoDetectMapOpenEnabled && autoReference?.targetPackage == autoExternalPackage() &&
                (if (method == ScreenCaptureMethod.MEDIA_PROJECTION) ScreenCaptureGrant.available && captureSession === session
                    else android.os.SystemClock.uptimeMillis() - lastAutoFrameMs <= 1_500L)) &&
            AppServices.prefs.alignmentMethodId == methodId && AppServices.prefs.screenCaptureMethod == method &&
            com.idvb.android.UsageConsent.isAccepted(this)
        // Independent control/notification roots that are shown need their own receipt.
        // An attached guide also needs one after immediate close/reopen: its last visible
        // surface buffer can outlive the current INVISIBLE view flag.
        val hiddenControls = balls?.captureControls().orEmpty().filter { it.second.isShown } +
            listOfNotNull(guideView?.takeIf { it.isAttachedToWindow }?.let { "guide" to it },
                notifications?.captureView?.takeIf { it.isShown }?.let { "notifications" to it })
        hideScanProgress()
        trace.measure("capture.prepare") {
            if (method == ScreenCaptureMethod.MEDIA_PROJECTION && !hasPreparedFrame) session?.prepareCapture()
            applyPracticeVisibility()
        }
        val hidden = System.nanoTime()
        val captureStart = Runnable {
            pendingAlignmentStart = null
            abortAlignmentCapture = null
            trace.emit(AlignmentLogEvent("capture.overlay-settle", durationNanos = System.nanoTime() - hidden))
            if (!current()) {
                if (generation == alignmentGeneration) cancelAlignment()
                complete(null, "cancelled-before-capture"); return@Runnable
            }
            val captureStarted = System.nanoTime()
            val callback = { captured: Result<Bitmap> ->
                trace.emit(AlignmentLogEvent("capture.acquire", captured.exceptionOrNull()?.message ?: "captured",
                    durationNanos = System.nanoTime() - captureStarted))
                val capturedAt = System.nanoTime()
                mainHandler.post {
                    trace.emit(AlignmentLogEvent("capture.callback-queue", durationNanos = System.nanoTime() - capturedAt))
                    if (!current()) {
                        if (generation == alignmentGeneration) cancelAlignment()
                        complete(null, "cancelled-after-capture", captured.getOrNull())
                        return@post
                    }
                    abortAlignmentCapture = null
                    alignmentCapturing = false
                    applyPracticeVisibility()
                    val bitmap = captured.getOrElse {
                        aligning = false
                        val timedOut = cancellation.reason == "map-open-timeout"
                        val reason = it.message ?: "截图失败"
                        unavailable(reason)
                        complete(AlignmentResult.Unavailable(reason, if (timedOut) "map-open-timeout" else "capture-failed"),
                            if (timedOut) "map-open-timeout" else "capture-failed")
                        return@post
                    }
                    lastAlignmentTerminal = "queued"
                    updateAlignmentNotice(notice, "正在自动贴合 · 等待计算", 0L)
                    val queued = System.nanoTime()
                    val accepted = recognitionExecutor.execute {
                        trace.emit(AlignmentLogEvent("worker.queue", durationNanos = System.nanoTime() - queued))
                        if (generation != alignmentGeneration || destroyed) {
                            mainHandler.post { complete(null, "cancelled-in-queue", bitmap) }
                            return@execute
                        }
                        AppServices.alignmentDiagnostics.recordLifecycle(trace.id, "worker.started", "running")
                        mainHandler.post {
                            if (current()) { lastAlignmentTerminal = "computing"; updateAlignmentNotice(notice, "正在自动贴合 · 结构校验", 0L) }
                        }
                        val algorithmStarted = System.nanoTime()
                        val result = runCatching {
                            AppServices.alignmentMethods.align(methodId, AlignmentRequest(bitmap, viewport, map, floor, cancellation), trace)
                        }
                        val solvedAt = System.nanoTime()
                        val algorithmNanos = solvedAt - algorithmStarted
                        val algorithmDiagnosticNanos = trace.diagnosticNanosBetween(algorithmStarted, solvedAt)
                        trace.emit(AlignmentLogEvent("algorithm.cost", "diagnostic-spans-unioned-within-alignment-call",
                            measurements = mapOf("callWallMs" to algorithmNanos / 1e6,
                                "alignmentWorkMs" to (algorithmNanos - algorithmDiagnosticNanos) / 1e6,
                                "nestedDiagnosticMs" to algorithmDiagnosticNanos / 1e6),
                            labels = mapOf("nestedCosts" to "diagnostics-in-call-not-added-twice",
                                "overheadBudget" to "includes-synchronous-diagnostics")))
                        val cancelledAtWorkerExit = cancellation.isCancelled
                        AppServices.alignmentDiagnostics.recordLifecycle(trace.id, "worker.exited",
                            if (cancelledAtWorkerExit) "cancelled" else "finished",
                            if (cancelledAtWorkerExit) mapOf("cancelResponseMs" to (solvedAt - cancellation.requestedAtNanos) / 1e6) else emptyMap())
                        mainHandler.post finish@{
                            alignmentWorkNanos = algorithmNanos - algorithmDiagnosticNanos
                            trace.emit(AlignmentLogEvent("render.callback-queue", durationNanos = System.nanoTime() - solvedAt))
                            if (cancellation.isCancelled) {
                                complete(result.getOrNull(), if (cancelledAtWorkerExit) "cancelled-during-algorithm" else "cancelled-after-algorithm", bitmap)
                                return@finish
                            }
                            val outcome = result.getOrElse {
                                Log.w("IDVB-Align", "Alignment failed: $methodId", it)
                                AlignmentResult.Unavailable(it.message ?: "贴合失败", "algorithm-error")
                            }
                            if (!current()) {
                                if (generation == alignmentGeneration) cancelAlignment()
                                complete(outcome, "discarded-stale-result", bitmap)
                                return@finish
                            }
                            aligning = false
                            when (outcome) {
                                is AlignmentResult.Aligned -> {
                                    readyReference = readySignature; readyReferenceKey = readinessKey
                                    alignmentDisplayReady = true
                                    val target = outcome.transform.bounds
                                    val drawnBounds = RectF(target.x.toFloat(), target.y.toFloat(),
                                        (target.x + target.width).toFloat(), (target.y + target.height).toFloat())
                                    val referenceBounds = autoReference?.let(::autoReferenceBounds)
                                    if (autoCycle != null && referenceBounds != null && drawnBounds.intersect(RectF(bounds)) &&
                                        RectF.intersects(drawnBounds, referenceBounds)) {
                                        autoBlocked = true
                                        unavailable("攻略图会覆盖开图检测区域，请重新设置攻略图之外的参照区域")
                                        complete(AlignmentResult.Rejected("开图检测区域被攻略图覆盖", "auto-reference-occluded"), "auto-reference-occluded", bitmap)
                                        stopAutoMapGuide("auto-reference-occluded")
                                        return@finish
                                    }
                                    val submitted = System.nanoTime()
                                    guideView?.afterNextAlignedDraw { nanos ->
                                        if (!trace.completed.get()) {
                                            trace.emit(AlignmentLogEvent("render.canvas", durationNanos = nanos))
                                            trace.emit(AlignmentLogEvent("render.first-draw", durationNanos = System.nanoTime() - submitted))
                                            mainHandler.post { complete(outcome, if (current()) "drawn" else "discarded-after-submit", bitmap) }
                                        }
                                    }
                                    val renderSubmission = runCatching { trace.measure("render.submit") {
                                        alignedGuideBounds = RectF(target.x.toFloat(), target.y.toFloat(),
                                            (target.x + target.width).toFloat(), (target.y + target.height).toFloat())
                                        alignedGuideViewport = RectF(bounds)
                                        applyGuidePlacement(requireNotNull(alignedGuideBounds))
                                    } }
                                    if (renderSubmission.isFailure) {
                                        guideView?.afterNextAlignedDraw(null)
                                        unavailable("显示贴合结果失败：${renderSubmission.exceptionOrNull()?.message}")
                                        complete(outcome, "render-failed", bitmap)
                                        return@finish
                                    }
                                    mainHandler.postDelayed({
                                        if (!trace.completed.get()) {
                                            trace.emit(AlignmentLogEvent("render.draw-timeout", "no-observed-draw",
                                                durationNanos = System.nanoTime() - submitted))
                                            complete(outcome, "submitted-without-observed-draw", bitmap)
                                        }
                                    }, 1_000L)
                                }
                                is AlignmentResult.Unavailable -> { unavailable(outcome.reason); complete(outcome, "unavailable", bitmap) }
                                is AlignmentResult.Rejected -> { unavailable(outcome.reason); complete(outcome, "rejected", bitmap) }
                            }
                            applyPracticeVisibility()
                        }
                    }
                    if (!accepted) {
                        aligning = false; unavailable("识别线程已停止")
                        complete(AlignmentResult.Unavailable("识别线程已停止", "executor-closed"), "unavailable", bitmap)
                    }
                }
                Unit
            }
            if (method == ScreenCaptureMethod.ACCESSIBILITY || autoCycle != null) {
                updateAlignmentNotice(notice, "等待游戏地图画面 · 最多 3 秒", 0L)
                abortAlignmentCapture = com.idvb.android.alignment.MapOpenFrameCapture(
                    mainHandler, accessibilityCaptureExecutor, cancellation, trace, reference,
                    callback = { result, signature -> readySignature = signature; callback(result) },
                    captureFrame = if (method == ScreenCaptureMethod.MEDIA_PROJECTION)
                        { rect, received -> requireNotNull(session).captureNext(rect, trace, received) } else null,
                    intervalMs = if (method == ScreenCaptureMethod.MEDIA_PROJECTION) 0L else com.idvb.android.alignment.MapOpenReadiness.INTERVAL_MS,
                    maximumFrameAgeMs = requestDetector?.config?.maximumFrameAgeMs ?: 1_000L,
                ).start(bounds, preparedFrames.toList().also { preparedFrames.clear() })
            } else {
                session!!.capture(bounds, callback)
                abortAlignmentCapture = { session.cancelPending() }
            }
        }
        pendingAlignmentStart = captureStart
        if (hasPreparedFrame) {
            // These pixels were captured before showing the guide/notice, with both regions
            // checked for overlay occlusion. Readiness and structure still run on exact inputs.
            trace.emit(AlignmentLogEvent("capture.overlay-settle-skipped", "prepared-unoccluded-detector-capture"))
            captureStart.run()
            return
        }
        val renderBarrier = OverlayCaptureFrameBarrier(mainHandler, hiddenControls, ::current, trace) { ready ->
            if (pendingAlignmentStart === captureStart) {
                if (ready) mainHandler.post(captureStart)
                else {
                    cancelAlignment()
                    complete(null, "cancelled-before-capture")
                }
            }
        }
        abortAlignmentCapture = {
            renderBarrier.cancel()
            mainHandler.removeCallbacks(captureStart)
            if (pendingAlignmentStart === captureStart) pendingAlignmentStart = null
            complete(null, "cancelled-before-capture")
        }
        renderBarrier.start()
    }

    private fun refreshGuideSelection() {
        stopAutoMapGuide("map-or-floor-changed")
        autoDetector?.reset(); autoContext = ""; autoBlocked = false
        cancelAlignment()
        alignedGuideBounds = null
        alignedGuideViewport = null
        guideView?.clearAlignment()
        prewarmGuideFloor()
        if (guideVisible) {
            if (!loadGuideFloor()) {
                guideVisible = false
                balls?.guideVisible = false
                applyPracticeVisibility()
                return
            }
            val screen = screenSize()
            guideRegionPixels(screen.first, screen.second)?.let(::applyGuidePlacement)
        }
    }

    private fun nextFloor() {
        val map = currentMap ?: return
        val floors = map.floors.sortedBy { it.sortOrder }
        if (floors.isEmpty()) return
        cancelCaptureScan()
        floorIndex = (floorIndex + 1) % floors.size
        val floor = floors[floorIndex]
        AppServices.prefs.lastFloorKey = floor.key
        balls?.floorLabel = floor.displayName
        refreshGuideSelection()
    }

    private fun previousFloor() {
        val map = currentMap ?: return
        val floors = map.floors.sortedBy { it.sortOrder }
        if (floors.isEmpty()) return
        cancelCaptureScan()
        floorIndex = (floorIndex - 1 + floors.size) % floors.size
        val floor = floors[floorIndex]
        AppServices.prefs.lastFloorKey = floor.key
        balls?.floorLabel = floor.displayName
        refreshGuideSelection()
    }

    private fun nextVariant() {
        val map = currentMap ?: return
        val catalog = AppServices.repository.loadCatalog()
        val variantGroup = catalog.findVariantGroup(map.id) ?: return
        val nextId = catalog.nextVariantMapId(map.id) ?: return
        val next = catalog.maps.firstOrNull { it.id == nextId } ?: return
        val floorKey = AppServices.prefs.lastFloorKey
        val floors = next.floors.sortedBy { it.sortOrder }
        val index = floors.indexOfFirst { it.key == floorKey }.takeIf { it >= 0 } ?: return
        cancelCaptureScan()
        currentMap = next
        floorIndex = index
        AppServices.prefs.lastMapId = next.id
        AppServices.prefs.lastFloorKey = floorKey
        AppServices.prefs.selectedMapClassId = next.classId
        AppServices.prefs.lastMapIdentitySource = MapIdentitySource.MANUAL_UNVERIFIED
        balls?.identityVerified = false
        balls?.floorLabel = floors[index].displayName
        balls?.variantsAvailable = catalog.nextVariantMapId(next.id) != null
        refreshGuideSelection()
        val variantIndex = variantGroup.mapIds.indexOf(next.id) + 1
        val variantTotal = variantGroup.mapIds.size
        notifyOverlay("已切换至变体地图 $variantIndex/$variantTotal", tone = NotificationTone.WARNING)
    }

    private fun resizeControls(hasVariants: Boolean) {
        if (buttonLayout?.separated == true) { buttonLayout?.refresh(); return }
        val screen = screenSize()
        window.width = ((if (hasVariants) 264 else 212) * overlayContext.resources.displayMetrics.density).toInt()
        window.x = window.x.coerceIn(0, (screen.first - window.width).coerceAtLeast(0))
        window.update()
        ballMenu?.updatePosition()
    }

    /** Snapshot settings before leaving the main thread; render never touches windows/views. */
    private fun guideFloorPreparation(): Pair<String, () -> Bitmap?>? {
        val map = currentMap ?: return null
        val floor = map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex) ?: return null
        val file = AppServices.repository.floorImageFile(map.id, floor.imagePath)
        val maxDimension = maxOf(screenSize().first, screenSize().second) * 2
        val selected = AppServices.repository.loadPreviewRegion(map.id, floor)
        val freeCrop = AppServices.repository.loadFreeCropPoints(map.id, floor)
        val classMarkedForRemoval = AppServices.repository.loadCatalog().classes
            .firstOrNull { it.id == map.classId }?.removeBackground == true
        val metadata = java.io.File(AppServices.repository.mapsRoot, "${map.id}/data").listFiles()
            .orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
            .joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        val removeBackground = AppServices.prefs.removeGuideBackground
        val showRoutes = AppServices.prefs.showRoutes
        val thickness = AppServices.prefs.routeLineThickness
        val key = "$map|$floor|${file.canonicalPath}|${file.length()}|${file.lastModified()}|$selected|$freeCrop|$maxDimension|$metadata|" +
            "$removeBackground|$classMarkedForRemoval|$showRoutes|$thickness"
        val render: () -> Bitmap? = render@{
            var next = decodeMapRegion(file, selected, maxDimension, freeCrop) ?: return@render null
            try {
                if (removeBackground) {
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
                if (showRoutes) {
                    val rendered = com.idvb.android.graphics.MapRouteRenderer.render(
                        next, AppServices.repository.loadRouteAnnotations(map, floor.key), selected,
                        floor.imageWidth, floor.imageHeight, freeCrop, thickness)
                    if (rendered !== next) next.recycle()
                    next = rendered
                }
                next
            } catch (error: Throwable) { if (!next.isRecycled) next.recycle(); throw error }
        }
        return key to render
    }

    private fun installGuideBitmap(key: String, next: Bitmap) {
        val previous = guideBitmap
        guideBitmap = next
        guideBitmapKey = key
        guideWindow.opacity = AppServices.prefs.opacity
        guideView?.showBitmap(next)
        previous?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun loadGuideFloor(): Boolean {
        val (key, render) = guideFloorPreparation() ?: return false
        guideBitmap?.takeIf { !it.isRecycled && guideBitmapKey == key }?.let {
            guideWindow.opacity = AppServices.prefs.opacity
            guideView?.showBitmap(it)
            return true
        }
        val next = render() ?: return false
        installGuideBitmap(key, next)
        return true
    }

    private fun prewarmGuideFloor() {
        if (destroyed || !com.idvb.android.UsageConsent.isAccepted(this)) return
        val (key, render) = guideFloorPreparation() ?: return
        if (guideBitmap?.isRecycled == false && guideBitmapKey == key || guidePreparationKey == key || guidePreparationFailedKey == key) return
        guidePreparationKey = key
        val mapId = currentMap?.id.orEmpty()
        val started = System.nanoTime()
        guidePreparationExecutor.execute {
            val result = runCatching(render)
            val ended = System.nanoTime()
            mainHandler.post {
                if (guidePreparationKey == key) guidePreparationKey = null
                val valid = !destroyed && guideFloorPreparation()?.first == key
                if (valid) guidePreparationFailedKey = key.takeIf { result.getOrNull() == null }
                if (!destroyed) autoDiagnostics.captureEvent(AlignmentLogEvent("guide.prewarm", if (valid && result.getOrNull() != null) "ready" else "discarded-or-unavailable",
                    durationNanos = ended - started, labels = mapOf("mapId" to mapId, "cacheKey" to key,
                        "costScope" to "background-preparation-before-map-opening", "failure" to result.exceptionOrNull()?.toString().orEmpty())))
                result.getOrNull()?.let { bitmap ->
                    if (valid) installGuideBitmap(key, bitmap) else bitmap.recycle()
                }
                if (valid) {
                    autoStaticResume = autoDetector?.isOpen == true
                    scheduleAutoMapOpen(0L)
                }
            }
        }
    }

    private fun applyGuidePlacement(region: RectF) {
        val aligned = alignedGuideBounds
        val viewport = alignedGuideViewport
        if (aligned != null && viewport != null) {
            val screen = screenSize()
            guideWindow.x = 0; guideWindow.y = 0
            guideWindow.width = screen.first; guideWindow.height = screen.second
            guideView?.showAlignment(aligned, viewport)
        } else {
            guideView?.clearAlignment()
            applyGuideBounds(region)
        }
        guideWindow.update()
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
        cancelAlignment()
        if (currentMap == null) {
            notifyOverlay("请先锁定地图")
            return
        }
        val screen = screenSize()
        val resetRegion = captureRegionPixels(screen.first, screen.second)
        if (resetRegion == null) {
            notifyOverlay("请先校准显示区域")
            return
        }
        if (!guideVisible && !showGuide()) return
        autoDetector?.manualClose(); autoGuideOwned = false
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
                    alignedGuideBounds = null
                    alignedGuideViewport = null
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
                    notifyOverlay("攻略图显示已保存")
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
        applyGuidePlacement(region)
        guideWindow.opacity = AppServices.prefs.opacity
        guideView?.showBitmap(guideBitmap)
        guideWindow.update()
        guideView?.visibility = android.view.View.VISIBLE
    }

    private fun enterBlueprintMode() {
        if (blueprintView != null || adjustView != null || candidateView != null) return
        cancelAlignment()
        val screen = screenSize()
        blueprintWindow.x = 0; blueprintWindow.y = 0
        blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
        blueprintView = BlueprintCalibrationView(overlayContext).apply {
            listener = object : BlueprintCalibrationView.Listener {
                override fun onConfirmed(region: RectF) {
                    alignedGuideBounds = null
                    alignedGuideViewport = null
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
                        applyGuidePlacement(guideRegion)
                        guideWindow.update()
                    }
                    exitBlueprintMode()
                    notifyOverlay("显示区域已校准")
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
        alignedGuideBounds?.let { return RectF(it) }
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

    private fun scheduleAutoMapOpen(delayMs: Long = com.idvb.android.alignment.AutoMapOpenTiming.INTERVAL_MS) {
        mainHandler.removeCallbacks(autoTick)
        if (!destroyed && AppServices.prefs.autoDetectMapOpenEnabled)
            mainHandler.postDelayed(autoTick, delayMs)
    }

    private fun handleOutsideTouch() {
        // Ordinary gameplay touches do not control or toggle automatic recognition.
    }

    private fun handleAutoForegroundChanged(name: String?) {
        if (destroyed) return
        if (name != null && autoExternalPackage() == name) scheduleAutoMapOpen(0L)
        else {
            mainHandler.removeCallbacks(autoTick)
            stopAutoMapGuide("game-not-foreground-or-capture-unavailable")
            autoDetector?.reset()
        }
    }

    private fun stopAutoMapGuide(reason: String) {
        autoStaticResume = false
        autoPreviousMapFrame?.recycle(); autoPreviousMapFrame = null
        autoCaptureGeneration++
        val abort = abortAutoCapture
        abortAutoCapture = null; autoCaptureBusy = false
        if (autoGuideOwned) {
            autoDetector?.alignmentInterrupted()
            hideGuide(manual = false)
        }
        abort?.invoke()
        if (autoPauseReason != reason) {
            autoPauseReason = reason
            autoDiagnostics.event(reason, autoReference, sessionLogs.sessionId, lastAlignmentRequestId,
                state = mapOf("foregroundPackage" to AccessibilityScreenCaptureService.foregroundPackage.orEmpty(),
                    "targetPackage" to autoReference?.targetPackage.orEmpty(),
                    "captureAvailable" to AccessibilityScreenCaptureService.available.toString(),
                    "blocked" to autoBlocked.toString(), "aligning" to aligning.toString(),
                    "alignmentCapturing" to alignmentCapturing.toString()))
        }
    }

    private fun autoReferenceBounds(reference: com.idvb.android.alignment.AutoMapOpenReference): RectF {
        val r = reference.region
        return RectF(kotlin.math.floor(r[0].toDouble() * reference.screenWidth).toFloat(),
            kotlin.math.floor(r[1].toDouble() * reference.screenHeight).toFloat(),
            kotlin.math.ceil(r[2].toDouble() * reference.screenWidth).toFloat(),
            kotlin.math.ceil(r[3].toDouble() * reference.screenHeight).toFloat())
    }

    private fun autoRegionOccluded(region: RectF): Boolean {
        val overlays = OverlayWindowManager.visibleScreenBounds(setOf(guideWindow)).toMutableList()
        if (guideView?.isShown == true && guideView?.alpha != 0f) {
            val rendered = alignedGuideBounds?.let(::RectF) ?: RectF(guideWindow.x.toFloat(), guideWindow.y.toFloat(),
                (guideWindow.x + guideWindow.width).toFloat(), (guideWindow.y + guideWindow.height).toFloat())
            val viewport = alignedGuideViewport
            if (viewport == null || rendered.intersect(viewport)) overlays.add(rendered)
        }
        return com.idvb.android.alignment.AutoMapOpenOcclusion.overlaps(region, overlays)
    }

    private fun autoExternalPackage(): String? {
        if (hostForeground) return null
        val name = AccessibilityScreenCaptureService.foregroundPackage
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        if (name != null) {
            return name.takeUnless { it == packageName || it == "com.idvb.android" || it == home || it == "android" || it.startsWith("com.android.") }
        }
        // Projection works without an accessibility service. This is a display source,
        // not an invented foreground application identity.
        return "media-projection-display".takeIf {
            AppServices.prefs.screenCaptureMethod == ScreenCaptureMethod.MEDIA_PROJECTION &&
                !AccessibilityScreenCaptureService.available
        }
    }

    private fun pollAutoMapOpen() {
        if (destroyed) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!com.idvb.android.UsageConsent.isAccepted(this) || hostForeground || practiceForeground || !OverlayState.state.value.visible) {
            stopAutoMapGuide("not-visible-or-consented")
            return
        }
        if (!AppServices.prefs.autoDetectMapOpenEnabled) {
            stopAutoMapGuide("disabled")
            return
        }
        // Static screens do not start a long screenshot wait. The next distinct image
        // resumes observation directly, so opening latency begins at that frame.
        if (AppServices.prefs.screenCaptureMethod == ScreenCaptureMethod.MEDIA_PROJECTION &&
            captureSession?.hasFreshAutoFrame() != true && !autoStaticResume) return

        val screen = screenSize()
        if (screen.first <= screen.second) {
            stopAutoMapGuide("landscape-required")
            return
        }

        val calibrated = captureRegionPixels(screen.first, screen.second)
        if (calibrated == null) {
            stopAutoMapGuide("capture-region-missing")
            return
        }

        val targetPackage = autoExternalPackage() ?: run {
            stopAutoMapGuide("foreground-package-unavailable")
            return
        }

        val calibRight = calibrated.right
        val screenKey = "$screen:${calibRight}:$targetPackage"
        if (autoReferenceScreen != screenKey || autoReference == null) {
            autoReferenceScreen = screenKey
            autoReference = autoReferenceStore.loadBuiltin(screen.first, screen.second, calibRight / screen.first, targetPackage)
            autoDetector = null
            autoContext = ""
            autoBlocked = false
            autoDiagnostics.clearFrames()
        }
        val reference = autoReference
        if (reference == null) {
            stopAutoMapGuide("builtin-reference-unavailable")
            return
        }

        val method = AppServices.prefs.screenCaptureMethod
        val interval = if (method == ScreenCaptureMethod.MEDIA_PROJECTION)
            com.idvb.android.alignment.AutoMapOpenTiming.INTERVAL_MS else 350L
        val context = "${reference.id}:$targetPackage:$screen:${currentMap?.id}:$floorIndex:${AppServices.prefs.alignmentMethodId}:$method:${calibrated.toShortString()}"
        if (context != autoContext || autoDetector == null) {
            val widthFraction = reference.sidebarAspectRatio?.let { aspect ->
                (aspect * screen.second / ((1.0 - reference.region[0]) * screen.first)).coerceIn(1e-6, 1.0)
            }
            val config = com.idvb.android.alignment.AutoMapOpenConfig(sidebarWidthFraction = widthFraction,
                openFrames = 1, closeFrames = 1,
                maximumFrameAgeMs = 1_000L)
            autoDiagnostics.configure(config, method.name, interval)
            prewarmGuideFloor()
            stopAutoMapGuide("context-changed")
            autoOpeningFrameNanos = 0L
            autoContext = context
            autoBlocked = false
            autoDetector = com.idvb.android.alignment.AutoMapOpenDetector(
                com.idvb.android.alignment.AutoMapOpenDetector.signature(reference.signaturePixels),
                config
            )
        }
        val detector = requireNotNull(autoDetector)
        val currentPkg = AccessibilityScreenCaptureService.foregroundPackage
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val isBlockedPkg = currentPkg != null && (currentPkg == packageName || currentPkg == "com.idvb.android" || currentPkg == home || currentPkg == "android" || currentPkg.startsWith("com.android."))
        val sourceAvailable = if (method == ScreenCaptureMethod.MEDIA_PROJECTION)
            captureSession != null && ScreenCaptureGrant.available else AccessibilityScreenCaptureService.available
        if (isBlockedPkg || !sourceAvailable || autoBlocked) {
            detector.observe(null, now)
            stopAutoMapGuide(if (autoBlocked) "reference-overlapped" else "game-not-foreground-or-capture-unavailable")
            return
        }

        if (autoCaptureBusy) {
            return
        }
        if (scanning || blueprintView != null || adjustView != null || candidateView != null || candidateResult != null) {
            detector.observe(null, now)
            stopAutoMapGuide("manual-work")
            scheduleAutoMapOpen(interval)
            return
        }

        val region = autoReferenceBounds(reference)
        if (autoRegionOccluded(region)) {
            detector.observe(null, now)
            if (autoPauseReason != "reference-occluded") {
                autoPauseReason = "reference-occluded"
                autoDiagnostics.event(autoPauseReason, reference, sessionLogs.sessionId, lastAlignmentRequestId)
            }
            scheduleAutoMapOpen(interval)
            return
        }

        autoCaptureBusy = true
        val generation = ++autoCaptureGeneration
        val started = System.nanoTime()
        val startedAtMs = android.os.SystemClock.uptimeMillis()

        val mapRegion = calibrated.let {
            Rect(it.left.toInt().coerceIn(0, screen.first), it.top.toInt().coerceIn(0, screen.second),
                it.right.toInt().coerceIn(0, screen.first), it.bottom.toInt().coerceIn(0, screen.second))
        }.takeIf { currentMap != null && !aligning && it.width() > 0 && it.height() > 0 && !autoRegionOccluded(RectF(it)) }

        val referenceRegion = Rect(region.left.toInt(), region.top.toInt(), region.right.toInt(), region.bottom.toInt())
        val captureBounds = Rect(referenceRegion).apply { if (mapRegion != null) union(mapRegion) }
        val session = captureSession
        val reuseStaticFrame = autoStaticResume
        autoStaticResume = false
        val observedFrameNanos = java.util.concurrent.atomic.AtomicLong(started)
        val observedFrameSequence = java.util.concurrent.atomic.AtomicLong(-1L)
        val sampleId = "$generation:$startedAtMs"
        val captureLog = AlignmentLogSink { event ->
            event.measurements["frameReceivedNanos"]?.let { observedFrameNanos.set(it.toLong()) }
            event.measurements["frameSequence"]?.let { observedFrameSequence.set(it.toLong()) }
            autoDiagnostics.captureEvent(event.copy(labels = event.labels + ("autoSampleId" to sampleId)))
        }
        val sampler = com.idvb.android.alignment.AutoMapOpenFrameSampler(mainHandler, accessibilityCaptureExecutor,
            captureFrame = { rect, callback ->
                if (method == ScreenCaptureMethod.MEDIA_PROJECTION) {
                    if (reuseStaticFrame) requireNotNull(session).captureRetained(rect, captureLog, callback)
                    else requireNotNull(session).captureAuto(rect, captureLog, callback)
                }
                else AccessibilityScreenCaptureService.capture(rect, accessibilityCaptureExecutor, callback, captureLog)
            }, captureMethod = method.name,
            compare = { pixels -> detector.compareCandidate(com.idvb.android.alignment.AutoMapOpenDetector.signature(pixels)) },
            frameSequence = { observedFrameSequence.get().takeIf { it >= 0 } })

        abortAutoCapture = sampler.sampleWithFrame(referenceRegion, captureBounds, mapRegion,
            isCurrent = { !destroyed && generation == autoCaptureGeneration && autoContext == context &&
                !scanning && blueprintView == null && adjustView == null && candidateView == null &&
                AppServices.prefs.autoDetectMapOpenEnabled && com.idvb.android.UsageConsent.isAccepted(this) &&
                !practiceForeground && OverlayState.state.value.visible && screenSize() == screen &&
                autoExternalPackage() == targetPackage && AppServices.prefs.screenCaptureMethod == method },
            isOccluded = { autoRegionOccluded(region) },
            isMapOccluded = { mapRegion == null || autoRegionOccluded(RectF(mapRegion)) },
            log = captureLog) { result ->
            if (destroyed || generation != autoCaptureGeneration) {
                result.getOrNull()?.mapFrame?.recycle(); return@sampleWithFrame
            }
            abortAutoCapture = null
            autoCaptureBusy = false
            val sampleTime = android.os.SystemClock.uptimeMillis()
            val pixels = result.getOrNull()?.pixels
            var mapFrame = result.getOrNull()?.mapFrame
            val previous = autoPreviousMapFrame
            var previousTransferred = false
            autoPreviousMapFrame = null
            val decidedAt = System.nanoTime()
            val observation = detector.observeComparison(result.getOrNull()?.comparison, sampleTime)
            val decisionMs = (result.getOrNull()?.decisionMs ?: 0.0) + (System.nanoTime() - decidedAt) / 1e6
            if (pixels != null) lastAutoFrameMs = sampleTime
            lastAutoSampleId = sampleId

            autoDiagnostics.frame(reference, pixels, observation, sampleTime, (decidedAt - started) / 1e6,
                startedAtMs, decisionMs, mapFrame != null, result.exceptionOrNull()?.toString(), sampleId,
                observedFrameSequence.get().takeIf { it >= 0 },
                observedFrameNanos.get().takeIf { observedFrameSequence.get() >= 0 })

            val isOpenScore = observation.comparison?.score?.let { it >= detector.config.openThreshold } == true
            if (observation.transition == com.idvb.android.alignment.AutoMapOpenTransition.OPENED) {
                autoOpeningFrameNanos = observedFrameNanos.get()
                autoPauseReason = ""
                autoDiagnostics.event("opened", reference, sessionLogs.sessionId, lastAlignmentRequestId)
            }

            if (observation.transition == com.idvb.android.alignment.AutoMapOpenTransition.CLOSED) {
                autoOpeningFrameNanos = 0L
                stopAutoMapGuide("closed")
            }

            if (observation.isOpen && currentMap == null && autoPauseReason != "map-not-selected") {
                autoPauseReason = "map-not-selected"
                autoDiagnostics.event(autoPauseReason, reference, sessionLogs.sessionId, lastAlignmentRequestId)
                notifyOverlay("自动开图已识别，请先扫描或选择地图")
            }

            val wantsAlignment = pixels != null && currentMap != null && !aligning &&
                (!guideVisible || autoGuideOwned) && detector.shouldAttemptAlignment(sampleTime)
            val guideReady = wantsAlignment && guideBitmap?.isRecycled == false && guideBitmapKey == guideFloorPreparation()?.first
            if (wantsAlignment && !guideReady) {
                prewarmGuideFloor()
                if (autoPauseReason != "guide-preparing") {
                    autoPauseReason = "guide-preparing"
                    autoDiagnostics.event("guide-preparing", reference, sessionLogs.sessionId, lastAlignmentRequestId)
                }
            }
            if (wantsAlignment && guideReady) {
                val cycle = detector.alignmentStarted(sampleTime)
                if (cycle != null) {
                    autoPauseReason = ""
                    autoGuideOwned = true
                    alignedGuideBounds = null; alignedGuideViewport = null; guideView?.clearAlignment()
                    // A cold guide cache stays in the budget; waiting must not produce a false pass.
                    val began = if (detector.attemptsInCycle == 1 && autoOpeningFrameNanos > 0L)
                        autoOpeningFrameNanos else observedFrameNanos.get()
                    val preparedFrames = mutableListOf<com.idvb.android.alignment.PreparedMapFrame>()
                    if (mapFrame != null) {
                        if (previous?.isFreshFor(mapFrame.bounds, sampleTime) == true &&
                            (mapFrame.frameSequence == null || previous.frameSequence != mapFrame.frameSequence)) {
                            preparedFrames.add(previous); previousTransferred = true
                        }
                        preparedFrames.add(mapFrame); mapFrame = null
                    }
                    try {
                        if (showGuide(renderGuide = false)) {
                            alignGuide(began, System.nanoTime() - began, cycle, preparedFrames)
                            if (!aligning && detector.alignmentInFlight) detector.alignmentFinished(false, sampleTime, cycle)
                        } else {
                            autoGuideOwned = false; detector.alignmentFinished(false, sampleTime, cycle)
                        }
                    } finally {
                        preparedFrames.forEach { it.recycle() }
                    }
                }
            }

            if (mapFrame != null && isOpenScore && !aligning) {
                autoPreviousMapFrame = mapFrame
                mapFrame = null
            }
            mapFrame?.recycle()
            if (!previousTransferred) previous?.recycle()

            val nextAt = android.os.SystemClock.uptimeMillis()
            if (method == ScreenCaptureMethod.ACCESSIBILITY || captureSession?.hasFreshAutoFrame() == true)
                scheduleAutoMapOpen(com.idvb.android.alignment.AutoMapOpenTiming.delayAfterSample(startedAtMs, nextAt, interval))
        }
    }

    override fun onDestroy() {
        AccessibilityScreenCaptureService.foregroundChangedListener = null
        autoPreviousMapFrame?.recycle(); autoPreviousMapFrame = null
        mainHandler.removeCallbacks(autoTick)
        autoCaptureGeneration++; abortAutoCapture?.invoke(); abortAutoCapture = null
        autoDiagnostics.close()
        assistController?.cancel(); assistController = null
        assistEditorWindow?.remove(); assistEditorWindow = null
        sessionLogs.stop()
        if (currentService === this) currentService = null
        destroyed = true
        notifications?.close(); notifications = null
        activeAlignmentCancellation?.cancel("service-destroyed")
        activeAlignmentTrace?.emit(AlignmentLogEvent("request.cancelled", "service-destroyed"))
        alignmentGeneration++
        aligning = false
        alignmentCapturing = false
        abortAlignmentCapture?.invoke(); abortAlignmentCapture = null
        getSharedPreferences("overlay", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(capturePreferencesListener)
        scanGeneration++
        scanning = false
        hideScanProgress()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        accessibilityCaptureExecutor.shutdown()
        guidePreparationExecutor.shutdown()
        recognitionExecutor.close()
        closeCandidates(recycleCapture = true)
        captureSession?.close(); captureSession = null
        guideWindow.remove(); guideView = null
        guideBitmap?.let { if (!it.isRecycled) it.recycle() }; guideBitmap = null
        blueprintWindow.remove(); blueprintView = null; adjustView = null
        ballMenu?.hide(); ballMenu = null
        buttonLayout?.dispose(); buttonLayout = null
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
        notifications?.position()
        if (blueprintView != null) {
            blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
            blueprintWindow.x = 0; blueprintWindow.y = 0
            blueprintWindow.update()
        }
        // Display notifications also include refresh-rate/mode changes. Reattaching
        // this focusable window on those notifications can trigger another mode
        // change, causing a remove/add loop, touch gaps and lost in-progress edits.
        // Only rebuild when the actual coordinate space changes (e.g. rotation).
        if (adjustView != null &&
            (blueprintWindow.width != screen.first || blueprintWindow.height != screen.second)
        ) {
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
            assistController?.cancel()
            assistEditorWindow?.remove(); assistEditorWindow = null
            cancelCaptureScan()
            alignedGuideBounds = null
            alignedGuideViewport = null
            guideView?.clearAlignment()
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
                applyGuidePlacement(guideRegion)
            }
        }
        window.x = window.x.coerceIn(0, (screen.first - window.width).coerceAtLeast(0))
        window.y = window.y.coerceIn(0, (screen.second - window.height).coerceAtLeast(0))
        window.update()
        ballMenu?.updatePosition()
        buttonLayout?.refresh()
        applyPracticeVisibility()
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
