package com.idvb.android.alignment

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** Replays the exact sampled inputs, including timed-out requests with no alignment result. */
object MapOpenReadinessReplay {
    data class Frame(val attempt: Int, val recordedReady: Boolean, val replayed: MapFrameReadiness,
        val frameSequence: Long? = null, val frameReceivedNanos: Long? = null)
    fun run(archive: File): List<Frame> = ZipFile(archive).use { zip ->
        val manifest = Json.parseToJsonElement(zip.getInputStream(requireNotNull(zip.getEntry("diagnostics.json"))).bufferedReader().readText()).jsonObject
        require(manifest["readinessReplayReady"]?.jsonPrimitive?.boolean == true) { "Readiness inputs unavailable or incomplete" }
        val events = manifest.getValue("events").jsonArray.map { it.jsonObject }
        val referenceEvent = events.firstOrNull { it["stage"]?.jsonPrimitive?.content == "readiness.reference" }
        val reference = referenceEvent?.let {
            val values = it.getValue("measurements").jsonObject
            MapFrameSignature(it.getValue("series").jsonObject.getValue("histogram").jsonArray.map { v -> v.jsonPrimitive.double },
                values.getValue("blueGray").jsonPrimitive.double, values.getValue("meanValue").jsonPrimitive.double)
        }
        var previous: MapFrameSignature? = null
        var previousSequence: Long? = null
        events.filter { it["stage"]?.jsonPrimitive?.content == "readiness.frame" }.map { event ->
            val measurements = event.getValue("measurements").jsonObject
            val attempt = measurements.getValue("attempt").jsonPrimitive.double.toInt()
            val sequence = measurements["frameSequence"]?.jsonPrimitive?.double?.toLong()?.takeIf { it >= 0 }
            val received = measurements["frameReceivedNanos"]?.jsonPrimitive?.double?.toLong()?.takeIf { it > 0 }
            sequence?.let {
                require(previousSequence == null || it > checkNotNull(previousSequence)) { "Readiness reused a physical projection frame" }
                previousSequence = it
            }
            val name = "readiness-$attempt.argb"
            val bytes = zip.getInputStream(requireNotNull(zip.getEntry(name))).readBytes()
            require(bytes.size == MapOpenReadiness.WIDTH * MapOpenReadiness.HEIGHT * 4)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == manifest.getValue("artifacts").jsonObject.getValue(name).jsonObject.getValue("sha256").jsonPrimitive.content)
            val input = ByteBuffer.wrap(bytes)
            val signature = MapOpenReadiness.signature(IntArray(bytes.size / 4) { input.int })
            val decision = MapOpenReadiness.evaluate(signature, reference, previous)
            previous = signature
            Frame(attempt, event.getValue("labels").jsonObject.getValue("decision").jsonPrimitive.content == "ready", decision,
                sequence, received)
        }
    }
}
