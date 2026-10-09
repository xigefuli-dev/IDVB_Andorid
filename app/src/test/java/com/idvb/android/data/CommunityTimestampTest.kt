package com.idvb.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeParseException

class CommunityTimestampTest {
    @Test fun screenshotTimestampPreservesAllSevenDigits() {
        val parsed = CommunityTimestamp.parse("2026-09-30T09:09:58.7288901+00:00")
        assertEquals("2026-09-30T09:09:58.728890100Z", parsed.toString())
        assertEquals(728890100, parsed.nano)
    }

    @Test fun offsetsRepresentTheSameInstant() {
        val utc = CommunityTimestamp.parse("2026-09-30T09:09:58.7288901Z")
        for (value in listOf(
            "2026-09-30T09:09:58.7288901+00:00",
            "2026-09-30T17:09:58.7288901+08:00",
            "2026-09-30T03:39:58.7288901-05:30",
        )) assertEquals(utc, CommunityTimestamp.parse(value))
    }

    @Test fun acceptsZeroThroughNineFractionDigits() {
        for (digits in 0..9) {
            val fraction = "123456789".take(digits)
            val suffix = if (digits == 0) "" else ".$fraction"
            val parsed = CommunityTimestamp.parse("2026-09-30T09:09:58${suffix}+00:00")
            assertEquals(if (digits == 0) 0 else fraction.padEnd(9, '0').toInt(), parsed.nano)
        }
    }

    @Test fun rollbackWithinOneMillisecondRemainsDetectable() {
        val previous = CommunityTimestamp.parse("2026-09-30T09:09:58.7288901+00:00")
        val older = CommunityTimestamp.parse("2026-09-30T09:09:58.7288900Z")
        assertTrue(older.isBefore(previous))
        assertEquals(previous, CommunityTimestamp.parse("2026-09-30T17:09:58.7288901+08:00"))
    }

    @Test fun rejectsMalformedOrUnzonedTimestamps() {
        for (value in listOf(
            "2026-09-30T09:09:58.7288901",
            "2026-09-31T09:09:58Z",
            "2026-09-30T09:09:58.1234567890Z",
            "2026-09-30T09:09:58+25:00",
            "2026-09-30T09:09:58Zgarbage",
        )) {
            try {
                CommunityTimestamp.parse(value)
                throw AssertionError("Accepted invalid timestamp: $value")
            } catch (_: DateTimeParseException) { }
        }
    }
}
