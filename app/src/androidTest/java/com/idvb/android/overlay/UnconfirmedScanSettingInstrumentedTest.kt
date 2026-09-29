package com.idvb.android.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.AppServices
import com.idvb.android.data.OverlayPrefs
import com.idvb.android.recognize.RecognitionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UnconfirmedScanSettingInstrumentedTest {
    @Test fun scanDefaultsMigrateOldValuesOnceAndPersistUserChoices() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val name="unconfirmed-setting-${UUID.randomUUID()}"
        val isolated=object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String,mode: Int): SharedPreferences = target.getSharedPreferences(name,mode)
        }
        try {
            val raw = isolated.getSharedPreferences("overlay", Context.MODE_PRIVATE)
            raw.edit().putBoolean("background_scan_enabled", false)
                .putBoolean("show_unconfirmed_candidates", false)
                .putBoolean("recognition_diagnostics_enabled", false).commit()
            val migrated = OverlayPrefs(isolated)
            assertTrue(migrated.backgroundScanEnabled)
            assertTrue(migrated.showUnconfirmedCandidates)
            assertTrue(migrated.recognitionDiagnosticsEnabled)
            migrated.backgroundScanEnabled = false
            migrated.recognitionDiagnosticsEnabled = false
            assertFalse(OverlayPrefs(isolated).backgroundScanEnabled)
            assertFalse(OverlayPrefs(isolated).recognitionDiagnosticsEnabled)
            OverlayPrefs(isolated).showUnconfirmedCandidates=true
            assertTrue(OverlayPrefs(isolated).showUnconfirmedCandidates)
            OverlayPrefs(isolated).showUnconfirmedCandidates=false
            assertFalse(OverlayPrefs(isolated).showUnconfirmedCandidates)
        } finally { target.deleteSharedPreferences(name) }
    }

    @Test fun actualResultHandlerShowsCandidatesOrRecyclesAndWaits() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val context=if(Build.VERSION.SDK_INT>=30) target.createDisplayContext(
            target.getSystemService(DisplayManager::class.java).getDisplay(0))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null) else target
        val previous=AppServices.prefs.showUnconfirmedCandidates
        val previousBackground=AppServices.prefs.backgroundScanEnabled
        val previousMap=AppServices.prefs.lastMapId
        instrumentation.runOnMainSync {
            val service=OverlayService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",Context::class.java).apply {
                isAccessible=true
            }.invoke(service,context)
            fun field(name: String)=OverlayService::class.java.getDeclaredField(name).apply { isAccessible=true }
            field("overlayContext").set(service,context)
            field("candidateWindow").set(service,OverlayWindowManager(context))
            val handle=OverlayService::class.java.getDeclaredMethod("handleScanResult",RecognitionResult::class.java).apply { isAccessible=true }
            val close=OverlayService::class.java.getDeclaredMethod("closeCandidates",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            try {
                for(background in listOf(false,true)) for(enabled in listOf(false,true)) {
                    AppServices.prefs.backgroundScanEnabled=background
                    AppServices.prefs.showUnconfirmedCandidates=enabled
                    val frame=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
                    try {
                        handle.invoke(service,RecognitionResult(frame,emptyList()))
                        assertEquals("Candidate window visibility",enabled && !background,field("candidateView").get(service)!=null)
                        assertEquals("Capture ownership",!enabled,frame.isRecycled)
                        if (enabled && background) {
                            assertNotNull(field("candidateResult").get(service))
                            OverlayService::class.java.getDeclaredMethod("toggleGuide").apply {
                                isAccessible=true
                            }.invoke(service)
                            assertNotNull("Eye button opens pending candidates",field("candidateView").get(service))
                            assertFalse(frame.isRecycled)
                        }
                        assertEquals("Unconfirmed scan must not lock a map",previousMap,AppServices.prefs.lastMapId)
                    } finally {
                        close.invoke(service,true)
                        if(!frame.isRecycled) frame.recycle()
                    }
                }
            } finally {
                AppServices.prefs.showUnconfirmedCandidates=previous
                AppServices.prefs.backgroundScanEnabled=previousBackground
            }
        }
    }
}
