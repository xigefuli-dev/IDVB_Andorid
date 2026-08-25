package com.idvb.android.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 厂商自启动/后台管理跳转引导（国产定制系统保活）。
 * 按 Build.MANUFACTURER / Build.BRAND 分类，依次尝试各家「自启动管理」入口；
 * 全部失败回退到本应用详情页。
 */
object VendorAutoStart {

    private data class Target(val component: String)

    /** 已知厂商自启动管理页（component 使用 ComponentName.unflattenFromString 可解析的格式） */
    private fun targetsForDevice(): List<Target> {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || b.contains("xiaomi") -> listOf(
                // MIUI：自启动管理
                Target("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
                // MIUI：省电策略（后台不限制）
                Target("com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                // EMUI / HarmonyOS：自启动管理
                Target("com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            m.contains("oppo") || b.contains("oppo") -> listOf(
                // ColorOS：自启动
                Target("com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                // ColorOS：电池后台高耗电管理
                Target("com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            )
            m.contains("vivo") || b.contains("vivo") -> listOf(
                // OriginOS / FuntouchOS：后台高耗电管理
                Target("com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            m.contains("meizu") -> listOf(
                // Flyme：后台管理
                Target("com.meizu.safe/com.meizu.safe.permission.SmartBGActivity"),
            )
            m.contains("samsung") -> listOf(
                // One UI：电池
                Target("com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
            else -> emptyList()
        }
    }

    /**
     * 依次尝试跳转厂商自启动管理页。
     * @return true 表示已成功跳转（或已回退到应用详情页），false 表示完全失败
     */
    fun openAutoStart(context: Context): Boolean {
        for (t in targetsForDevice()) {
            try {
                val intent = Intent()
                    .setComponent(ComponentName.unflattenFromString(t.component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 尝试下一个
            }
        }
        return openAppDetails(context)
    }

    /** 回退：跳转系统「应用详情页」，用户可手动设置自启动/后台权限 */
    fun openAppDetails(context: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
