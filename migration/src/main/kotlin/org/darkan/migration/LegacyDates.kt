package org.darkan.migration

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LegacyDates {
    private val patterns = listOf(
        "MMM d, yyyy, h:mm:ss a",
        "MMM d, yyyy h:mm:ss a",
    )

    fun parse(text: String): Date? {
        val cleaned = text.replace(' ', ' ').replace(' ', ' ').trim()
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.isLenient = false
            try {
                return format.parse(cleaned)
            } catch (_: ParseException) {
            }
        }
        return null
    }

    fun toDate(value: Any?): Date? = when (value) {
        is Date -> value
        is Number -> Date(value.toLong())
        is String -> parse(value)
        else -> null
    }
}
