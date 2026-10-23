package com.idvb.android.data

import com.idvb.android.idvm.IdvmHeader
import java.io.File
import java.util.zip.ZipFile

/** Downloading a desktop package must not imply that the mobile reader can render it. */
internal object CommunityPackageCompatibility {
    fun needsAdaptation(file: File): Boolean = ZipFile(file).use { zip ->
        val entry = zip.getEntry("header") ?: return false // Wrapped legacy packages use the existing importer.
        val bytes = ByteArray(IdvmHeader.SIZE)
        java.io.DataInputStream(zip.getInputStream(entry)).use {
            it.readFully(bytes)
            require(it.read() == -1) { "地图包头长度无效" }
        }
        require(bytes.size == IdvmHeader.SIZE && String(bytes, 0, 4, Charsets.US_ASCII) == "IDVM") {
            "地图包头无效"
        }
        val major = (bytes[4].toInt() and 255) or ((bytes[5].toInt() and 255) shl 8)
        val minor = (bytes[6].toInt() and 255) or ((bytes[7].toInt() and 255) shl 8)
        major != IdvmHeader.FORMAT_MAJOR || minor > IdvmHeader.MAX_SUPPORTED_MINOR
    }
}
