package com.idvb.android.data

import com.idvb.android.idvm.IdvmHeader
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CommunityProtocolTest {
    private val id = "3f2a9c1e-7b44-4d0a-9e61-2c5d8b7f0a13"
    private val publisher = "A".repeat(64)

    @Test fun keylessReferenceRoundTripsWithoutContentKey() {
        val ref = CommunityReference.parse("idvb-sub://v2?publisher=${publisher.lowercase()}&publication=${id.uppercase()}")
        assertEquals(id, ref.id)
        assertEquals(publisher, ref.publisher)
        assertEquals("idvb-sub://v2?publication=$id&publisher=$publisher", ref.canonical)
    }

    @Test fun rejectsAmbiguousOrSecretBearingReferences() {
        val valid = "idvb-sub://v2?publication=$id&publisher=$publisher"
        for (text in listOf("$valid&key=secret", "$valid&publication=$id", "$valid#fragment",
            valid.replace("v2?", "v2/?"), valid.replace(id, "00000000-0000-0000-0000-000000000000"),
            valid.replace(publisher, "invalid"), valid.replace("v2?", "user@v2?"))) {
            assertThrows(IllegalArgumentException::class.java) { CommunityReference.parse(text) }
        }
    }

    @Test fun acceptsSupportedDesktopPackageVersions() {
        for (minor in listOf(0, 4, 5, 6)) {
            val file = File.createTempFile("community-test-", ".idvm")
            try {
                ZipOutputStream(file.outputStream()).use {
                    it.putNextEntry(ZipEntry("header"))
                    it.write(IdvmHeader.write(IdvmHeader.Header(1, minor, UUID.randomUUID(), 0, ByteArray(32))))
                    it.closeEntry()
                }
                assertFalse(CommunityPackageCompatibility.needsAdaptation(file))
            } finally { file.delete() }
        }
    }

    @Test fun rejectsForgedGrantSignaturesAndUntrustedSigningKeys() {
        val forged = """{"typ":"idvb-key-grant+v1","kid":"subscription-grant-2026-10","payload":"e30","signature":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))}"}"""
        assertThrows(IllegalArgumentException::class.java) { CommunityGrant.verify(forged, id, publisher, "request") }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityGrant.verify(forged.replace("subscription-grant-2026-10", "untrusted"), id, publisher, "request")
        }
    }
}
