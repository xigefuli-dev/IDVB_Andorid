package com.idvb.android.recognize

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.data.MapRepository
import com.idvb.android.idvm.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CandidateTagsInstrumentedTest {
    @Test fun tagsFilterNormalAndManualCandidatesAtPhoneWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val isolated = File(target.cacheDir,"candidate-tags-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        try {
            val repository = MapRepository(context)
            val candidates = listOf("左", "右").mapIndexed { index, value ->
                val id = "map-$index"
                val folder = File(repository.mapsRoot,"$id/data").apply { mkdirs() }
                val metadata = MetadataDocument(1,MetadataMap(id,"c",id,"test","normalized"),emptyList(),
                    tags=listOf(MetadataTag("direction","门方位",value)))
                File(folder,"metadata.json").writeText(IdvmJson.instance.encodeToString(MetadataDocument.serializer(),metadata))
                RecognitionCandidate(MapRecord(id,"c",id,id,1,emptyList()),"1f",
                    CandidateDisposition.NEEDS_VERIFICATION,evidenceLabel="test")
            }
            instrumentation.runOnMainSync {
                val frame = Bitmap.createBitmap(320,160,Bitmap.Config.ARGB_8888)
                try {
                    for (manual in listOf(false,true)) {
                        val view = CandidateSelectionView(context,RecognitionResult(frame,candidates),repository,manualSelection=manual)
                        var selected: String? = null
                        view.listener = object : CandidateSelectionView.Listener {
                            override fun onSelected(candidate: RecognitionCandidate) { selected = candidate.map.id }
                            override fun onCancelled() = Unit
                        }
                        val density = context.resources.displayMetrics.density
                        val bitmap = Bitmap.createBitmap((320*density).toInt(),(700*density).toInt(),Bitmap.Config.ARGB_8888)
                        try {
                            view.layout(0,0,bitmap.width,bitmap.height)
                            fun draw() = view.draw(Canvas(bitmap))
                            fun tap(x: Float,y: Float) {
                                for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                                    val event = MotionEvent.obtain(0,0,action,x*density,y*density,0)
                                    try { view.onTouchEvent(event) } finally { event.recycle() }
                                }
                                draw()
                            }
                            draw()
                            if (!manual) File(target.cacheDir,"candidate-tags-normal.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                            }
                            tap(50f,80f) // Open the tag selector loaded from candidate metadata.
                            tap(50f,198f) // All, left, right: choose right.
                            tap(60f,340f)
                            tap(60f,340f)
                            assertEquals("manual=$manual", "map-1", selected)
                        } finally { bitmap.recycle() }
                    }
                } finally { frame.recycle() }
            }
        } finally { isolated.deleteRecursively() }
    }
}
