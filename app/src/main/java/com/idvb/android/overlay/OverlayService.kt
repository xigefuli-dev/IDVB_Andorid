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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.idvb.android.AppServices
import com.idvb.android.MainActivity
import com.idvb.android.R

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
    private var balls: OverlayBallView? = null
    private var blueprintView: BlueprintCalibrationView? = null

    override fun onCreate() {
        super.onCreate()
        window = OverlayWindowManager(this)
        blueprintWindow = OverlayWindowManager(this)
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
        // 收起菜单时窗口必须与小球同高，避免不可见的透明区域拦截底层应用触摸。
        window.width = (212 * density).toInt(); window.height = (48 * density).toInt()
        window.x = (screen.first - window.width).coerceAtLeast(0); window.y = (screen.second * .28f).toInt()
        balls = OverlayBallView(this).apply {
            mapLocked = false; floorLabel = "--"
            listener = object : OverlayBallView.Listener {
                override fun onSearch() {
                    val current = screenSize()
                    val region = captureRegionPixels(current.first, current.second)
                    val message = if (region == null) {
                        "请先在 ··· 中校准显示区域"
                    } else {
                        "将仅捕获校准区域 ${region.width().toInt()} × ${region.height().toInt()} px（截图接口待接入）"
                    }
                    Toast.makeText(this@OverlayService, message, Toast.LENGTH_SHORT).show()
                }
                override fun onToggleGuide() = Unit
                override fun onNextFloor() = Unit
                override fun onCalibrate() = enterBlueprintMode()
                override fun onClose() = stopSelf()
                override fun onMenuExpanded(expanded: Boolean) {
                    window.height = ((if (expanded) 142 else 48) * density).toInt()
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
        window.add(balls!!, locked = false)
        OverlayState.update { it.copy(running = true, visible = true, locked = false) }
    }

    private fun enterBlueprintMode() {
        if (blueprintView != null) return
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

    override fun onDestroy() {
        blueprintWindow.remove(); blueprintView = null
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
