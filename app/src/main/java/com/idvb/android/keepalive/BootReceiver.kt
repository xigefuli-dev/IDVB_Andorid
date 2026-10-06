package com.idvb.android.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.idvb.android.AppServices
import com.idvb.android.R
import com.idvb.android.overlay.OverlayService

/**
 * 开机自启（RECEIVE_BOOT_COMPLETED）。
 * 开启「开机自启」且存在上次地图时，开机自动恢复悬浮窗；
 * Android 15/16 收紧 FGS 启动时降级为「点按启动」通知（避免 ForegroundServiceStartNotAllowedException）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!com.idvb.android.UsageConsent.isAccepted(context) || !com.idvb.android.tutorial.TutorialStore.get(context).state.value.completed) return
        val prefs = AppServices.prefs
        if (!prefs.autoStartOnBoot) return
        if (prefs.lastMapId == null) return
        try {
            OverlayService.start(context)
        } catch (e: Exception) {
            showTapToStartNotification(context)
        }
    }

    private fun showTapToStartNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("idvb_boot", "开机恢复", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getService(
            context, 0,
            Intent(context, OverlayService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, "idvb_boot")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("点按恢复地图悬浮窗")
            .setContentText("系统限制了后台启动，点按此通知恢复悬浮窗")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(102, notification)
    }
}
