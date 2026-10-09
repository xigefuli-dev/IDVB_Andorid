package com.idvb.android.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal object CommunityTimestamp {
    // Older Android ISO_INSTANT parsers require Z. Parse the offset explicitly,
    // preserving nanoseconds for the subscription rollback check.
    fun parse(value: String): Instant =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
}
