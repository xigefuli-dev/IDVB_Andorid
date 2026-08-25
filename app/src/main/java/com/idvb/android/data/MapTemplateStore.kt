package com.idvb.android.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class TemplateFloor(val id: String, val name: String)

@Serializable
data class MapTemplate(val id: String, val name: String, val floors: List<TemplateFloor>)

class MapTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("map_templates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<MapTemplate> {
        val custom = prefs.getString("custom", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(MapTemplate.serializer()), it) }.getOrNull()
        }.orEmpty()
        return builtIns + custom
    }

    fun add(name: String, floors: List<TemplateFloor>) {
        val custom = load().filterNot { it.id.startsWith("builtin-") } + MapTemplate(
            id = "custom-${java.util.UUID.randomUUID()}", name = name, floors = floors,
        )
        prefs.edit().putString("custom", json.encodeToString(ListSerializer(MapTemplate.serializer()), custom)).apply()
    }

    /** 删除自定义模板；内置模板永远不会被删除。 */
    fun delete(id: String): Boolean {
        if (id.startsWith("builtin-")) return false
        val custom = load().filterNot { it.id.startsWith("builtin-") }
        if (custom.none { it.id == id }) return false
        val remaining = custom.filterNot { it.id == id }
        prefs.edit().putString("custom", json.encodeToString(ListSerializer(MapTemplate.serializer()), remaining)).apply()
        return true
    }

    companion object {
        val builtIns = listOf(
            MapTemplate("builtin-double", "常规双层", listOf(TemplateFloor("1f", "一楼"), TemplateFloor("2f", "二楼"))),
            MapTemplate("builtin-basement", "包含地下室", listOf(TemplateFloor("1f", "一楼"), TemplateFloor("2f", "二楼"), TemplateFloor("b1f", "地下室"))),
        )
    }
}
