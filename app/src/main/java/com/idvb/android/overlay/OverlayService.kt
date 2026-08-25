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
import android.os.Build
import android.os.IBinder
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.R
import com.idvb.android.recognize.CandidateSelectionView
import com.idvb.android.recognize.RecognitionResult
import com.idvb.android.recognize.ScreenCaptureGrant
import com.idvb.android.recognize.ScreenCaptureSession
import com.idvb.android.recognize.SideEntranceRecognizer
import com.idvb.android.recognize.CandidateDisposition
import com.idvb.android.recognize.RecognitionCandidate
import com.idvb.android.idvm.MapRecord
import com.idvb.android.graphics.decodeMapRegion

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
    private lateinit var blueprintWindow: OverlayWindowManager
    private lateinit var candidateWindow: OverlayWindowManager
    private lateinit var guideWindow: OverlayWindowManager
    private var balls: OverlayBallView? = null
    private var blueprintView: BlueprintCalibrationView? = null
    private var adjustView: BlueprintImageAdjustView? = null
    private var candidateView: CandidateSelectionView? = null
    private var candidateResult: RecognitionResult? = null
    private var captureSession: ScreenCaptureSession? = null
    private var guideView: GuideMapView? = null
    private var guideBitmap: Bitmap? = null
    private var guideVisible = false
    private var currentMap: MapRecord? = null
    private var floorIndex = 0
    @Volatile private var scanning = false

    override fun onCreate() {
        super.onCreate()
        window = OverlayWindowManager(this)
        blueprintWindow = OverlayWindowManager(this)
        candidateWindow = OverlayWindowManager(this)
        guideWindow = OverlayWindowManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(); startForegroundCompat()
        if (intent?.action == ACTION_CLOSE) stopSelf() else ensureBalls()
        return START_STICKY
    }

    private fun ensureBalls() {
        if (balls != null) return
        val density = resources.displayMetrics.density
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
        window.width = (212 * density).toInt(); window.height = (48 * density).toInt()
        window.x = (screen.first - window.width).coerceAtLeast(0); window.y = (screen.second * .28f).toInt()
        balls = OverlayBallView(this).apply {
            mapLocked = currentMap != null
            floorLabel = orderedFloors.getOrNull(floorIndex)?.displayName ?: "--"
            listener = object : OverlayBallView.Listener {
                override fun onSearch() = runForegroundScan()
                override fun onToggleGuide() = toggleGuide()
                override fun onNextFloor() = nextFloor()
                override fun onFreeAdjust() = enterFreeAdjustMode()
                override fun onCalibrate() = enterBlueprintMode()
                override fun onClose() = stopSelf()
                override fun onMenuExpanded(expanded: Boolean) {
                    window.height = ((if (expanded) 190 else 48) * density).toInt()
                    val currentScreen = screenSize()
                    window.y = window.y.coerceIn(0, (currentScreen.second - window.height).coerceAtLeast(0))
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
        guideView = GuideMapView(this).apply { visibility = android.view.View.INVISIBLE }
        guideWindow.x = 0; guideWindow.y = 0; guideWindow.width = 1; guideWindow.height = 1
        guideWindow.add(guideView!!, locked = true)
        window.add(balls!!, locked = false)
        if (ScreenCaptureGrant.available) {
            captureSession = ScreenCaptureSession(this).also { it.start(screen.first, screen.second) }
        }
        OverlayState.update { it.copy(running = true, visible = true, locked = false) }
    }

    private fun runForegroundScan() {
        if (candidateView != null || blueprintView != null || adjustView != null) return
        if (AppServices.prefs.debugMode) {
            showDebugCandidates()
            return
        }
        if (scanning) {
            Toast.makeText(this, "正在扫描地图，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val size = screenSize()
        val region = captureRegionPixels(size.first, size.second)
        if (region == null) {
            Toast.makeText(this, "请先在 ··· 中校准显示区域", Toast.LENGTH_SHORT).show()
            return
        }
        val session = captureSession
        if (session == null || !session.start(size.first, size.second)) {
            Toast.makeText(this, "屏幕捕获授权已失效，请返回 IDVB 重新授权", Toast.LENGTH_LONG).show()
            return
        }
        scanning = true
        balls?.visibility = android.view.View.INVISIBLE
        guideView?.visibility = android.view.View.INVISIBLE
        balls?.postDelayed({
            session.capture(Rect(region.left.toInt(), region.top.toInt(), region.right.toInt(), region.bottom.toInt())) { captured ->
                val bitmap = captured.getOrElse { error ->
                    Handler(Looper.getMainLooper()).post {
                        scanning = false
                        balls?.visibility = android.view.View.VISIBLE
                        if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                        Toast.makeText(this, "截图失败：${error.message}", Toast.LENGTH_LONG).show()
                    }
                    return@capture
                }
                // 截图完成后立即恢复小球；识别算法在后台继续运行。
                Handler(Looper.getMainLooper()).post {
                    balls?.visibility = android.view.View.VISIBLE
                    if (guideVisible) guideView?.visibility = android.view.View.VISIBLE
                    Toast.makeText(this, "截图完成，正在扫描地图…", Toast.LENGTH_SHORT).show()
                }
                Thread {
                    val classId = resolveActiveClassId(AppServices.repository.loadCatalog())
                    val recognition = runCatching {
                        SideEntranceRecognizer(AppServices.repository, classId).recognize(bitmap)
                    }
                    Handler(Looper.getMainLooper()).post {
                        scanning = false
                        recognition.onSuccess(::showCandidates).onFailure { error ->
                            bitmap.recycle()
                            Toast.makeText(this, "扫描失败：${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }, 140L)
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
        showCandidates(RecognitionResult(preview, high + references + remaining))
    }

    private fun showCandidates(result: RecognitionResult) {
        closeCandidates(recycleCapture = true)
        candidateResult = result
        val size = screenSize()
        candidateWindow.x = 0; candidateWindow.y = 0; candidateWindow.width = size.first; candidateWindow.height = size.second
        candidateView = CandidateSelectionView(this, result, AppServices.repository).apply {
            listener = object : CandidateSelectionView.Listener {
                override fun onSelected(candidate: com.idvb.android.recognize.RecognitionCandidate) {
                    AppServices.prefs.lastMapId = candidate.map.id
                    AppServices.prefs.lastFloorKey = candidate.floorKey
                    currentMap = candidate.map
                    val floors = candidate.map.floors.sortedBy { it.sortOrder }
                    floorIndex = floors.indexOfFirst { it.key == candidate.floorKey }.let { if (it < 0) 0 else it }
                    balls?.mapLocked = true
                    balls?.floorLabel = floors.getOrNull(floorIndex)?.displayName ?: candidate.floorKey
                    if (guideVisible) loadGuideFloor()
                    closeCandidates(recycleCapture = true)
                    Toast.makeText(this@OverlayService, "已锁定地图：${candidate.map.title}", Toast.LENGTH_SHORT).show()
                }
                override fun onCancelled() = closeCandidates(recycleCapture = true)
            }
        }
        candidateWindow.add(candidateView!!, locked = false)
    }

    private fun closeCandidates(recycleCapture: Boolean) {
        candidateWindow.remove(); candidateView = null
        if (recycleCapture) candidateResult?.capturedRegion?.let { if (!it.isRecycled) it.recycle() }
        candidateResult = null
    }

    private fun toggleGuide() {
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

    private fun loadGuideFloor() {
        val map = currentMap ?: return
        val floor = map.floors.sortedBy { it.sortOrder }.getOrNull(floorIndex) ?: return
        val file = AppServices.repository.floorImageFile(map.id, floor.imagePath)
        val maxDimension = maxOf(screenSize().first, screenSize().second) * 2
        val selected = AppServices.repository.loadPreviewRegion(map.id, floor)
        val next = decodeMapRegion(file, selected, maxDimension) ?: return
        val previous = guideBitmap
        guideBitmap = next
        guideView?.showBitmap(next, AppServices.prefs.opacity)
        previous?.let { if (!it.isRecycled) it.recycle() }
    }

    private fun applyGuideBounds(region: RectF) {
        guideWindow.x = region.left.toInt()
        guideWindow.y = region.top.toInt()
        guideWindow.width = region.width().toInt().coerceAtLeast(1)
        guideWindow.height = region.height().toInt().coerceAtLeast(1)
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
            this,
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
        guideView?.showBitmap(guideBitmap, AppServices.prefs.opacity)
        guideWindow.update()
        guideView?.visibility = android.view.View.VISIBLE
    }

    private fun enterBlueprintMode() {
        if (blueprintView != null || adjustView != null || candidateView != null) return
        val screen = screenSize()
        blueprintWindow.x = 0; blueprintWindow.y = 0
        blueprintWindow.width = screen.first; blueprintWindow.height = screen.second
        blueprintView = BlueprintCalibrationView(this).apply {
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
        captureSession?.start(screen.first, screen.second)
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
                if (ScreenCaptureGrant.available) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
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
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.run { width() to height() }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.run { widthPixels to heightPixels }
        }
    }
}
