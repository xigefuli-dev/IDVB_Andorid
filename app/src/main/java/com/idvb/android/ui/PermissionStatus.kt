package com.idvb.android.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.idvb.android.keepalive.BatteryOptimization
import com.idvb.android.recognize.ScreenCaptureGrant
import com.idvb.android.recognize.AccessibilityScreenCaptureService
import com.idvb.android.data.ScreenCaptureMethod
import com.idvb.android.AppServices
import com.idvb.android.recognize.CaptureHealthMonitor
import com.idvb.android.recognize.CaptureHealthSnapshot
import com.idvb.android.recognize.CaptureAvailability

data class PermissionSnapshot(
    val overlay: Boolean,
    val screenCapture: Boolean,
    val notifications: Boolean,
    val foregroundService: Boolean,
    val mediaProjectionService: Boolean,
    val batteryOptimization: Boolean,
    val captureMethod: ScreenCaptureMethod,
    val batteryOptimizationSkipped: Boolean = false,
    val captureHealth: CaptureHealthSnapshot = CaptureHealthSnapshot(captureMethod),
) {
    val captureVerified: Boolean get() = screenCapture && captureHealth.source == captureMethod &&
        captureHealth.availability == CaptureAvailability.HEALTHY
    val captureStatusText: String get() = when {
        captureHealth.availability == CaptureAvailability.UNAVAILABLE ->
            (if (screenCapture) "开关已开，但实测失败：" else "截图不可用：") + captureHealth.description
        !screenCapture -> "尚未授权或服务未连接"
        else -> captureHealth.description
    }
    val batteryRequirementSatisfied: Boolean
        get() = batteryOptimization || batteryOptimizationSkipped

    val readyWithoutBattery: Boolean
        get() = overlay && (screenCapture || captureMethod == ScreenCaptureMethod.MEDIA_PROJECTION) &&
            notifications && foregroundService && mediaProjectionService

    val allGranted: Boolean
        get() = overlay && screenCapture && notifications && foregroundService &&
            mediaProjectionService && batteryOptimization

    val onlyScreenCaptureMissing: Boolean
        get() = overlay && !screenCapture && notifications && foregroundService &&
            mediaProjectionService && batteryOptimization

    /** MediaProjection asks for a fresh grant on start; accessibility must already be enabled. */
    val readyToStart: Boolean
        get() = readyWithoutBattery && batteryRequirementSatisfied
}

data class PermissionController(
    val snapshot: PermissionSnapshot,
    val refresh: () -> Unit,
    val requestNextMissing: () -> Unit,
    val requestScreenCapture: (onGranted: () -> Unit) -> Unit,
    val skipBatteryForSession: () -> Unit,
    val restoreBatteryRequirement: () -> Unit,
)

@Composable
fun rememberPermissionController(): PermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    var batterySkippedForSession by rememberSaveable { mutableStateOf(false) }
    var onScreenCaptureGranted by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val screenCaptureGranted = ScreenCaptureGrant.update(result.resultCode, result.data)
        val continuation = onScreenCaptureGranted
        onScreenCaptureGranted = null
        if (screenCaptureGranted) continuation?.invoke()
        refreshTick++
    }

    val requestScreenCapture = { onGranted: () -> Unit ->
        onScreenCaptureGranted = onGranted
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val screenCaptureAvailable = ScreenCaptureGrant.available
    val captureMethod = AppServices.prefs.screenCaptureMethod
    val health by CaptureHealthMonitor.state.collectAsState()
    val snapshot = remember(context, refreshTick, screenCaptureAvailable, captureMethod, batterySkippedForSession, health) {
        permissionSnapshot(context, screenCaptureAvailable, captureMethod).copy(
            batteryOptimizationSkipped = batterySkippedForSession || AppServices.prefs.skipBatteryOptimization,
            captureHealth = health.getValue(captureMethod),
        )
    }
    val requestNextMissing = {
        when {
            !snapshot.overlay -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
            !snapshot.screenCapture && snapshot.captureMethod == ScreenCaptureMethod.ACCESSIBILITY ->
                openAccessibilityServiceSettings(context)
            !snapshot.screenCapture -> requestScreenCapture {}
            !snapshot.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !snapshot.notifications -> context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            !snapshot.batteryRequirementSatisfied -> {
                if (!BatteryOptimization.requestIgnoreBatteryOptimizations(context)) {
                    BatteryOptimization.openBatteryOptimizationSettings(context)
                }
            }
        }
    }
    return PermissionController(snapshot, { refreshTick++ }, requestNextMissing, requestScreenCapture,
        { batterySkippedForSession = true }, {
            batterySkippedForSession = false
            refreshTick++
        })
}

fun openAccessibilityServiceSettings(context: Context) {
    val component = ComponentName(context, AccessibilityScreenCaptureService::class.java)
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(detail) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun permissionSnapshot(context: Context, screenCaptureGranted: Boolean, captureMethod: ScreenCaptureMethod): PermissionSnapshot {
    val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    return PermissionSnapshot(
        overlay = Settings.canDrawOverlays(context),
        screenCapture = when (captureMethod) {
            ScreenCaptureMethod.MEDIA_PROJECTION -> screenCaptureGranted
            ScreenCaptureMethod.ACCESSIBILITY -> AccessibilityScreenCaptureService.available
        },
        notifications = notifications,
        foregroundService = context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED,
        mediaProjectionService = if (captureMethod == ScreenCaptureMethod.ACCESSIBILITY) true
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) == PackageManager.PERMISSION_GRANTED
        } else true,
        batteryOptimization = BatteryOptimization.isIgnoringBatteryOptimizations(context),
        captureMethod = captureMethod,
    )
}
