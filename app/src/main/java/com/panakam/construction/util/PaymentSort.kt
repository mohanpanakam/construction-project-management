package com.panakam.construction.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Converts a normalized "paymentDate" string — "yyyy-MM-dd" optionally followed by a
 * time (e.g. "2026-08-24 04:25PM", "2026-08-24 01:33PM", or just "2026-08-24" with no
 * time at all) — into a sortable millis value. Falls back gracefully: an unparseable
 * date sorts to the very start (Long.MIN_VALUE) instead of crashing or throwing off the
 * whole list, and an unparseable trailing time is simply ignored (still sorts correctly
 * by date, just without the extra same-day time precision).
 *
 * Backend already normalizes payment_date to this "yyyy-MM-dd[ time]" form (see backend
 * DateUtils.kt) so this only has to handle a small number of trailing time shapes.
 */
private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
private val TIME_FORMATS = listOf("hh:mma", "hh:mm a", "HH:mm", "hh:mm:ss a", "HH:mm:ss")
    .map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }

fun paymentDateTimeSortKey(dateTimeStr: String?): Long {
    val s = dateTimeStr?.trim().orEmpty()
    if (s.isBlank()) return Long.MIN_VALUE

    val datePart = s.take(10)
    val baseMillis = runCatching { ISO_DATE_FORMAT.parse(datePart)?.time }.getOrNull() ?: return Long.MIN_VALUE

    val timePart = s.drop(10).trim()
    if (timePart.isBlank()) return baseMillis

    for (fmt in TIME_FORMATS) {
        val parsed = runCatching { fmt.parse(timePart) }.getOrNull() ?: continue
        val cal = Calendar.getInstance()
        cal.time = parsed
        val msOfDay = (cal.get(Calendar.HOUR_OF_DAY) * 60L + cal.get(Calendar.MINUTE)) * 60_000L
        return baseMillis + msOfDay
    }
    return baseMillis
}

/** Sorts payment record maps (each expected to have a "paymentDate" key) in ascending
 *  chronological order — oldest first, like a bank passbook/statement. */
fun List<Map<String, Any>>.sortedByPaymentDateAscending(): List<Map<String, Any>> =
    sortedBy { paymentDateTimeSortKey(it["paymentDate"]?.toString()) }

