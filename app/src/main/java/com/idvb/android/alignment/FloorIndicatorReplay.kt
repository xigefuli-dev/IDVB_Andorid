package com.idvb.android.alignment

import android.graphics.BitmapFactory
import com.idvb.android.idvm.MapRecord
import kotlinx.serialization.json.*
import java.io.File
import java.util.zip.ZipFile

data class FloorIndicatorReplayReport(val recordedFloorKey: String, val result: FloorIndicatorResult) {
    val matches: Boolean get() = recordedFloorKey == result.winner.floorKey
}

/** Public replay adapter for the independent selection step, even when pose alignment fails.
 * Uses only the raw ROI and exact templates in the diagnostic package, never installed assets. */
object FloorIndicatorReplay {
    fun run(archive: File, token: AlignmentCancellation = AlignmentCancellation(),
        log: AlignmentLogSink = AlignmentLogSink.NONE): FloorIndicatorReplayReport = ZipFile(archive).use { zip ->
        val json = Json { ignoreUnknownKeys = true }
        val manifest = zip.getInputStream(requireNotNull(zip.getEntry("diagnostics.json"))).bufferedReader().use {
            json.parseToJsonElement(it.readText()).jsonObject
        }
        require(manifest["floorIndicatorFormat"]?.jsonPrimitive?.content == FloorIndicatorPolicy.VERSION)
        require(manifest["floorIndicatorReplayReady"]?.jsonPrimitive?.boolean == true) { "楼层回放输入不完整" }
        val artifacts = manifest.getValue("artifacts").jsonObject
        fun checked(name: String): ByteArray {
            val entry = requireNotNull(zip.getEntry(name))
            require(entry.size in 1..16L * 1024 * 1024)
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val expected = artifacts.getValue(name).jsonObject
            require(bytes.size.toLong() == expected.getValue("bytes").jsonPrimitive.long &&
                AlignmentDiagnosticsStore.sha256(bytes) == expected.getValue("sha256").jsonPrimitive.content)
            return bytes
        }
        val events = manifest.getValue("events").jsonArray.map { it.jsonObject }
        val templates = events.filter { it.getValue("stage").jsonPrimitive.content == "floor-indicator.template-source" }.map {
            val labels = it.getValue("labels").jsonObject
            val name = labels.getValue("template").jsonPrimitive.content
            FloorIndicatorTemplate(name, labels.getValue("floorKey").jsonPrimitive.content, checked("floor-template-$name"))
        }
        val input = checked("floor-indicator.png")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(input, 0, input.size))
        try {
            val map = json.decodeFromJsonElement<MapRecord>(manifest.getValue("map"))
            val recorded = events.first { it.getValue("stage").jsonPrimitive.content == "floor-indicator.selected" }
                .getValue("labels").jsonObject.getValue("floorKey").jsonPrimitive.content
            FloorIndicatorReplayReport(recorded, FloorIndicatorRecognizer(templates).recognize(bitmap, map.floors.map { it.key }.toSet(), token, log))
        } finally { bitmap.recycle() }
    }
}
