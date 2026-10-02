package com.idvb.android.alignment

import kotlinx.serialization.json.*

data class AutoMapOpenReplayReport(val knownFrames: Int, val unknownFrames: Int)

/** Replays exact spatial scores and matched windows. State transitions require the earlier
 * physical-opening history; a bounded snapshot must not claim full lifecycle acceptance. */
object AutoMapOpenReplay {
    fun replay(document: String): AutoMapOpenReplayReport {
        val snapshot = Json.parseToJsonElement(document).jsonObject
        require(snapshot.getValue("schemaVersion").jsonPrimitive.int == 3) {
            "自动开图旧格式未记录可靠的实际配置，请使用匹配版本回放"
        }
        require(snapshot.getValue("replayReady").jsonPrimitive.boolean &&
            Regex("[0-9a-f]{64}").matches(snapshot.getValue("sourceFingerprint").jsonPrimitive.content)) {
            "自动开图输入或源码指纹不完整，无法回放"
        }
        val reference = snapshot.getValue("reference").jsonObject.getValue("pixels").jsonArray
        val referenceSignature = AutoMapOpenDetector.signature(IntArray(reference.size) { reference[it].jsonPrimitive.int })
        val configuration = snapshot.getValue("configuration").jsonObject
        val width = configuration.getValue("sidebarWidthFraction").jsonPrimitive.doubleOrNull
        val config = AutoMapOpenConfig(
            openThreshold = configuration.getValue("openThreshold").jsonPrimitive.double,
            closeThreshold = configuration.getValue("closeThreshold").jsonPrimitive.double,
            openFrames = configuration.getValue("openFrames").jsonPrimitive.int,
            closeFrames = configuration.getValue("closeFrames").jsonPrimitive.int,
            maximumAttempts = configuration.getValue("maximumAttempts").jsonPrimitive.int,
            retryCooldownMs = configuration.getValue("retryCooldownMs").jsonPrimitive.long,
            maximumFrameAgeMs = configuration.getValue("maximumFrameAgeMs").jsonPrimitive.long,
            sidebarWidthFraction = width,
        )
        require(snapshot.getValue("algorithmVersion").jsonPrimitive.content ==
            if (config.sidebarWidthFraction == null) "spatial-roi-v1" else "builtin-sidebar-window-v2") {
            "自动开图算法与记录配置不匹配"
        }
        var known = 0
        var unknown = 0
        val frames = snapshot.getValue("frames").jsonArray
        for (index in frames.indices) {
            val frame = frames[index].jsonObject
            if (frame.getValue("pixels") == JsonNull) { unknown++; continue }
            val pixels = frame.getValue("pixels").jsonArray
            val signature = AutoMapOpenDetector.signature(IntArray(pixels.size) { pixels[it].jsonPrimitive.int })
            val comparison = if (width == null) AutoMapOpenDetector.compare(referenceSignature, signature)
                else AutoMapOpenDetector.compareSidebar(referenceSignature, signature, width)
            for ((key, actual) in mapOf("score" to comparison.score, "color" to comparison.color,
                "brightness" to comparison.brightness, "edge" to comparison.edge,
                "windowLeft" to comparison.windowLeft, "windowWidth" to comparison.windowWidth)) {
                require(kotlin.math.abs(frame.getValue(key).jsonPrimitive.double - actual) <= 1e-9) { "自动开图第 $index 帧 $key 回放不一致" }
            }
            require(frame.getValue("offsetX").jsonPrimitive.int == comparison.offsetX && frame.getValue("offsetY").jsonPrimitive.int == comparison.offsetY) {
                "自动开图第 $index 帧偏移回放不一致"
            }
            known++
        }
        require(known > 0) { "自动开图诊断只有未知帧，不能作为回放通过" }
        return AutoMapOpenReplayReport(known, unknown)
    }
}
