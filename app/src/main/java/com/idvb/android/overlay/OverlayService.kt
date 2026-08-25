package com.idvb.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.R
import com.idvb.android.data.OverlayPrefs
import com.idvb.android.idvm.MapRecord
import java.io.File

/**
 * 悬浮窗前台服务（specialUse）：悬浮窗生命周期所有者。
 *
 * 对齐参考项目 MapOverlayWindow 的「常驻 + 悬浮层」：
 * - 常驻前台通知（可一键解锁/隐藏/关闭）
 * - 锁定态只能通过通知 action 解锁（避免点击穿透后无法操作）
 * - START_STICKY：系统回收后自动重建；stopWithTask=false 跟随任务存活
 * - 状态写入 [OverlayPrefs]，重启/开机自启时恢复
 */
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

        /** 窗口最小边 = 屏幕短边的 15% */
        private const val MIN_WINDOW_RATIO = 0.15f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** 向运行中的前台服务发送控制指令（服务未运行时静默失败） */
        fun sendAction(context: Context, action: String, block: Intent.() -> Unit = {}) {
            val intent = Intent(context, OverlayService::class.java).setAction(action).apply(block)
            runCatching { context.startService(intent) }
        }
    }

    private val prefs: OverlayPrefs get() = AppServices.prefs

    private lateinit var wm: OverlayWindowManager
    private var mapView: OverlayMapView? = null
    private var currentMap: MapRecord? = null
    private var floorIndex = 0
    private var bitmap: Bitmap? = null
    private var windowVisible = false

    private var screenW = 0
    private var screenH = 0

    private val viewListener = object : OverlayMapView.Listener {
        override fun onMove(dx: Float, dy: Float) {
            wm.x += dx.toInt()
            wm.y += dy.toInt()
            persistPosition()
            wm.update()
        }

        override fun onScale(factor: Float) {
            val min = (minOf(screenW, screenH) * MIN_WINDOW_RATIO).toInt().coerceAtLeast(1)
            wm.width = (wm.width * factor).toInt().coerceIn(min, screenW)
            wm.height = (wm.height * factor).toInt().coerceIn(min, screenH)
            persistSize()
            wm.update()
        }

        override fun onOpacityChanged(value: Float) {
            prefs.opacity = value
            mapView?.opacity = value
            OverlayState.update { it.copy(opacity = value) }
        }

        override fun onToggleLock() = toggleLock()

        override fun onSwitchFloor(delta: Int) = changeFloor(delta)

        override fun onHide() = toggleVisible()

        override fun onClose() = stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        wm = OverlayWindowManager(this)
        val size = screenSize()
        screenW = size.first
        screenH = size.second
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat()
        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> toggleLock()
            ACTION_TOGGLE_VISIBLE -> toggleVisible()
            ACTION_SET_OPACITY -> setOpacity(intent.getFloatExtra(EXTRA_OPACITY, prefs.opacity))
            ACTION_SET_FLOOR -> changeFloor(intent.getIntExtra(EXTRA_FLOOR_DELTA, 1))
            ACTION_CLOSE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> ensureWindow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wm.remove()
        bitmap?.recycle()
        bitmap = null
        mapView = null
        OverlayState.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 窗口 ----

    /** 若尚未创建悬浮窗则按持久化状态创建；已创建但隐藏则重新显示 */
    private fun ensureWindow() {
        val repo = AppServices.repository
        val mapId = prefs.lastMapId ?: return
        val map = repo.loadCatalog().maps.firstOrNull { it.id == mapId } ?: return
        if (map.floors.isEmpty()) return
        currentMap = map
        val floorKey = prefs.lastFloorKey
        floorIndex = map.floors.indexOfFirst { it.key == floorKey }.let { if (it < 0) 0 else it }

        if (mapView == null) {
            val pos = prefs.positionRatio()
            val size = prefs.sizeRatio()
            wm.width = ((size?.first ?: OverlayPrefs.DEFAULT_W_RATIO) * screenW).toInt()
            wm.height = ((size?.second ?: OverlayPrefs.DEFAULT_H_RATIO) * screenH).toInt()
            wm.x = (pos?.first?.times(screenW))?.toInt() ?: 0
            wm.y = (pos?.second?.times(screenH))?.toInt() ?: 0
            clampWindow()

            mapView = OverlayMapView(this).apply {
                listener = viewListener
                opacity = prefs.opacity
                locked = prefs.locked
                floors = map.floors.map { it.key to it.displayName }
                floorIndex = this@OverlayService.floorIndex
            }
            loadFloorImage()
            wm.add(this.mapView!!, prefs.locked)
        } else if (!windowVisible) {
            wm.add(mapView!!, prefs.locked)
        }
        windowVisible = true
        updateState()
        updateNotification()
    }

    private fun loadFloorImage() {
        val map = currentMap ?: return
        val floor = map.floors.getOrNull(floorIndex) ?: return
        val file: File = AppServices.repository.floorImageFile(map.id, floor.imagePath)
        val bmp = decodeBounded(file, maxDim = maxOf(screenW, screenH) * 2)
        bitmap?.recycle()
        bitmap = bmp
        mapView?.image = bmp
        prefs.lastFloorKey = floor.key
        updateState()
        updateNotification()
    }

    /** 限制解码尺寸避免内存爆炸（地图原图可能很大） */
    private fun decodeBounded(file: File, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, opts)
            ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }

    private fun clampWindow() {
        wm.x = wm.x.coerceIn(0, (screenW - wm.width).coerceAtLeast(0))
        wm.y = wm.y.coerceIn(0, (screenH - wm.height).coerceAtLeast(0))
    }

    // ---- 交互 ----

    private fun toggleLock() {
        val newLocked = !prefs.locked
        prefs.locked = newLocked
        mapView?.locked = newLocked
        wm.update(locked = newLocked)
        updateState()
        updateNotification()
    }

    private fun toggleVisible() {
        val v = mapView ?: return
        if (windowVisible) {
            wm.remove()
            windowVisible = false
        } else {
            wm.add(v, prefs.locked)
            windowVisible = true
        }
        updateState()
        updateNotification()
    }

    /** UI 滑杆远程设置透明度 */
    private fun setOpacity(value: Float) {
        val v = value.coerceIn(0.1f, 1f)
        prefs.opacity = v
        mapView?.opacity = v
        updateState()
        updateNotification()
    }

    private fun changeFloor(delta: Int) {
        val map = currentMap ?: return
        if (map.floors.isEmpty()) return
        floorIndex = ((floorIndex + delta) % map.floors.size + map.floors.size) % map.floors.size
        mapView?.floorIndex = floorIndex
        loadFloorImage()
    }

    // ---- 持久化 ----

    private fun persistPosition() {
        prefs.setPositionRatio(wm.x.toFloat() / screenW, wm.y.toFloat() / screenH)
    }

    private fun persistSize() {
        prefs.setSizeRatio(wm.width.toFloat() / screenW, wm.height.toFloat() / screenH)
    }

    // ---- 前台通知 ----

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val title = currentMap?.title ?: "IDVB 悬浮窗"
        val floorName = currentMap?.floors?.getOrNull(floorIndex)?.displayName ?: ""
        val text = buildString {
            append(if (windowVisible) "悬浮窗运行中" else "悬浮窗已隐藏")
            if (floorName.isNotEmpty()) append(" · $floorName")
            if (prefs.locked) append(" · 已锁定(点击穿透)")
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(action(ACTION_TOGGLE_LOCK, if (prefs.locked) "解锁" else "锁定"))
            .addAction(action(ACTION_TOGGLE_VISIBLE, if (windowVisible) "隐藏" else "显示"))
            .addAction(action(ACTION_CLOSE, "关闭"))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun action(action: String, title: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            0,
            title,
            PendingIntent.getService(
                this,
                action.hashCode(),
                Intent(this, OverlayService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateState() {
        OverlayState.update { s ->
            s.copy(
                running = true,
                visible = windowVisible,
                locked = prefs.locked,
                opacity = prefs.opacity,
                mapTitle = currentMap?.title,
                floorLabel = currentMap?.floors?.getOrNull(floorIndex)?.displayName,
            )
        }
    }

    // ---- 工具 ----

    private fun screenSize(): Pair<Int, Int> {
        val w = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = w.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.run { widthPixels to heightPixels }
        }
    }
}
