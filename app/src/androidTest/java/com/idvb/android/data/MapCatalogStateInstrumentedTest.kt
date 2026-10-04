package com.idvb.android.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.idvb.android.idvm.ClassRecord
import com.idvb.android.idvm.MapCatalogDocument
import com.idvb.android.idvm.MapRecord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapCatalogStateInstrumentedTest {
    // Only the test APK's private cache is used; the installed app's maps are untouched.
    private fun repository(): MapRepository {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val root = File(testContext.cacheDir, "catalog-state-${UUID.randomUUID()}")
        return MapRepository(object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        })
    }

    private val populated = MapCatalogDocument(
        classes = listOf(ClassRecord("class", "关卡")),
        maps = listOf(MapRecord("map", "class", "地图", "source", 1, emptyList())),
    )

    @Test fun repositoryCommitUpdatesBothConsumersWithoutPageRefresh() = runBlocking {
        val repo = repository()
        repo.loadCatalog()
        val startCheck = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { repo.catalogState.first { it?.maps?.isNotEmpty() == true } }
        }
        val mapList = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { repo.catalogState.first { it?.maps?.isNotEmpty() == true } }
        }
        repo.saveCatalog(populated)
        assertEquals(populated, startCheck.await())
        assertEquals(startCheck.await(), mapList.await())
        assertEquals(populated, repo.loadCatalog())
        repo.saveCatalog(MapCatalogDocument())
        assertEquals(emptyList<MapRecord>(), repo.catalogState.value?.maps)
    }

    @Test fun firstLoadPublishesPersistedMapsForLateConsumers() {
        val writer = repository()
        writer.saveCatalog(populated)
        val reader = MapRepository(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = writer.mapsRoot.parentFile!!.parentFile!!
        })
        assertNull(reader.catalogState.value)
        assertEquals(populated, reader.loadCatalog())
        assertEquals(populated, reader.catalogState.value)
    }
}
