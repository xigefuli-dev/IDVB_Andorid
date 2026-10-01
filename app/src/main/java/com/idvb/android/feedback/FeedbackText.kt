package com.idvb.android.feedback

object FeedbackText {
    fun weightedLength(text: String): Int = text.trim().codePoints().toArray().sumOf { c ->
        if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF ||
            c in 0x3000..0x303F || c in 0xFF01..0xFF5E || c in 0x20000..0x2EBEF ||
            c in setOf(0x2014, 0x2026, 0x2018, 0x2019, 0x201C, 0x201D)) 2 else 1
    }

    fun isValid(text: String) = weightedLength(text) > 10 && text.trim().length <= 4000
}
