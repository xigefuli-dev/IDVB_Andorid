package com.idvb.android.keepalive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 忽略电池优化白名单。
 * 国产定制系统（MIUI/EMUI/ColorOS 等）会把后台服务按「耗电」杀掉，
 * 加入白名单可显著降低被杀的几率。
 */
object BatteryOptimization {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 尝试直接向系统申请加入白名单（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）。
     * @return true 表示已发起系统请求（弹窗），false 表示不可直接授予（需跳设置页兜底）
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return false
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 兜底：跳转系统「忽略电池优化」列表 */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 忽略：个别 ROM 无此入口
        }
    }
}
