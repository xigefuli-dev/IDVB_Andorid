package com.idvb.android

import android.app.Application
import android.content.Context
import com.idvb.android.data.MapRepository
import com.idvb.android.data.CommunityDownloadQueue
import com.idvb.android.data.MapTemplateStore
import com.idvb.android.data.OverlayPrefs
import com.idvb.android.recognize.RecognitionDiagnosticsStore
import com.idvb.android.recognize.cv.OpenCvRuntime

/**
 * 应用入口，持有跨组件共享的服务（对齐参考项目 ServiceCollectionExtensions 的组合根思想）。
 */
class IDVBApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        OpenCvRuntime.initialize()
        AppServices.startRecognitionPreparation()
    }

    companion object {
        lateinit var instance: IDVBApp
            private set
    }
}

/** 简易服务定位器 */
object AppServices {
    val context: Context get() = IDVBApp.instance
    val repository: MapRepository by lazy { MapRepository(context) }
    val templates: MapTemplateStore by lazy { MapTemplateStore(context) }
    val prefs: OverlayPrefs by lazy { OverlayPrefs(context) }
    val recognitionDiagnostics: RecognitionDiagnosticsStore by lazy { RecognitionDiagnosticsStore(context) }
    val communityDownloads: CommunityDownloadQueue by lazy { CommunityDownloadQueue(context) }
    internal val scanPreparation by lazy {
        com.idvb.android.recognize.RecognitionPreparation(repository) { prefs.selectedMapClassId }
    }
    private val scanPreferencesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "selected_map_class_id") scanPreparation.request()
    }

    private var preparationStarted = false

    internal fun startRecognitionPreparation() {
        if (!UsageConsent.isAccepted(context) || preparationStarted) return
        preparationStarted = true
        repository.onCatalogChanged = { scanPreparation.request() }
        context.getSharedPreferences("overlay",Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(scanPreferencesListener)
        scanPreparation.request()
    }
}
