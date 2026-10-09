package com.idvb.android.alignment

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.json.*
import java.io.FilterInputStream
import java.util.zip.ZipFile

/** The replay inputs do not depend on the diagnostic candidate timeline. Keep that
 * timeline in the ZIP, but do not build its entire JSON tree just to load a request. */
internal object AlignmentReplayManifest {
    private const val MAX_MANIFEST_BYTES = 256L * 1024 * 1024
    private val requestFields = setOf("schemaVersion", "replayReady", "artifacts", "map",
        "floorKey", "viewport", "result", "methodId", "floorSelectedByIndicator")

    fun read(zip: ZipFile): JsonObject {
        val entry = requireNotNull(zip.getEntry("diagnostics.json")) { "Missing replay input: diagnostics.json" }
        require(entry.size in 0..MAX_MANIFEST_BYTES) { "Replay manifest exceeds limit" }
        val bounded = object : FilterInputStream(zip.getInputStream(entry)) {
            private var count = 0L
            private fun counted(n: Int): Int {
                if (n > 0) { count += n; require(count <= MAX_MANIFEST_BYTES) { "Replay manifest exceeds limit" } }
                return n
            }
            override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = counted(`in`.read(bytes, offset, length))
        }
        JsonReader(bounded.reader(Charsets.UTF_8).buffered()).use { reader ->
            // Other diagnostic fields may contain NaN/Infinity; they are skipped,
            // while the retained request metadata follows the existing JSON schema.
            reader.isLenient = true
            val fields = linkedMapOf<String, JsonElement>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in requestFields) {
                    require(name !in fields) { "Duplicate replay request field: $name" }
                    fields[name] = value(reader, 0)
                } else reader.skipValue()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing replay manifest data" }
            return JsonObject(fields)
        }
    }

    private fun value(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 64) { "Replay request metadata nesting exceeds limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val fields = linkedMapOf<String, JsonElement>(); reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name !in fields) { "Duplicate replay metadata field: $name" }
                    fields[name] = value(reader, depth + 1)
                }
                reader.endObject(); JsonObject(fields)
            }
            JsonToken.BEGIN_ARRAY -> {
                val items = mutableListOf<JsonElement>(); reader.beginArray()
                while (reader.hasNext()) items += value(reader, depth + 1)
                reader.endArray(); JsonArray(items)
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull }
            else -> error("Invalid replay metadata token: ${reader.peek()}")
        }
    }
}
