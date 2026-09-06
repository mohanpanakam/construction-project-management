package com.panakam.construction.backend.service

/**
 * Normalizes the many date formats seen coming out of OCR (and, historically, typed in
 * manually before the app started using a proper date picker) into ONE canonical form:
 * "yyyy-MM-dd" for the date part, optionally followed by whatever time string was
 * attached (e.g. "2026-08-24 02:07 PM").
 *
 * Root problem this fixes: [OcrService.parsePaymentText] (and, before this, plain manual
 * entry) stored whatever format the receipt/user happened to use verbatim —
 * "24/08/2026", "2026-08-24", "03-Sep-2026", "Aug 24, 2026" all ended up side-by-side in
 * the same `payment_date` column. That's not just a display inconsistency: it also
 * silently broke the duplicate-transaction check (which compares paymentDate strings for
 * equality) and made date-based sorting/filtering unreliable.
 *
 * Day-first convention: bare numeric "D/M/Y" or "D-M-Y" is interpreted as DD/MM/YYYY
 * (not the US MM/DD/YYYY), consistent with this being an Indian-market app and with the
 * comment already on [OcrService]'s own extraction regex.
 */
object DateUtils {
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private data class DateMatch(val range: IntRange, val year: Int, val month: Int, val day: Int)

    private fun matchIso(s: String): DateMatch? =
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let {
            val (y, m, d) = it.destructured
            DateMatch(it.range, y.toInt(), m.toInt(), d.toInt())
        }

    private fun matchNumericDayFirst(s: String): DateMatch? =
        Regex("""^(\d{1,2})[/-](\d{1,2})[/-](\d{4})""").find(s)?.let {
            val (d, m, y) = it.destructured
            DateMatch(it.range, y.toInt(), m.toInt(), d.toInt())
        }

    /**
     * Same as [matchNumericDayFirst] but for a 2-digit year, e.g. "30-08-26" — seen on
     * bank/UPI app "Date&Time:" fields (RTGS/IMPS success screens commonly abbreviate the
     * year). Tried only after the 4-digit-year variant fails, and only as a fallback in
     * [normalizeDateTime]'s chain, since 2-digit years are inherently century-ambiguous.
     * Assumes 2000+YY (this app has no historical data before 2000).
     */
    private fun matchNumericDayFirstShortYear(s: String): DateMatch? =
        Regex("""^(\d{1,2})[/-](\d{1,2})[/-](\d{2})\b(?!\d)""").find(s)?.let {
            val (d, m, y) = it.destructured
            val yy = y.toInt()
            val fullYear = if (yy <= 69) 2000 + yy else 1900 + yy
            DateMatch(it.range, fullYear, m.toInt(), d.toInt())
        }

    private fun matchDayMonYear(s: String): DateMatch? =
        Regex("""^(\d{1,2})\s*([A-Za-z]{3})[A-Za-z]*\s*(\d{4})""", RegexOption.IGNORE_CASE).find(s)?.let {
            val (d, mon, y) = it.destructured
            val m = MONTHS[mon.lowercase()] ?: return null
            DateMatch(it.range, y.toInt(), m, d.toInt())
        }

    private fun matchDaySepMonSepYear(s: String): DateMatch? =
        Regex("""^(\d{1,2})[/-]([A-Za-z]{3})[A-Za-z]*[/-](\d{4})""", RegexOption.IGNORE_CASE).find(s)?.let {
            val (d, mon, y) = it.destructured
            val m = MONTHS[mon.lowercase()] ?: return null
            DateMatch(it.range, y.toInt(), m, d.toInt())
        }

    private fun matchMonDayYear(s: String): DateMatch? =
        // `\s*` (not `\s+`) — OCR sometimes glues these together with zero spaces at all,
        // e.g. "Aug25,2026" (seen on an RTGS "Request Accepted" screen).
        Regex("""^([A-Za-z]{3})[A-Za-z]*\s*(\d{1,2}),?\s*(\d{4})""", RegexOption.IGNORE_CASE).find(s)?.let {
            val (mon, d, y) = it.destructured
            val m = MONTHS[mon.lowercase()] ?: return null
            DateMatch(it.range, y.toInt(), m, d.toInt())
        }

    /**
     * Parses a string that begins with a date (optionally followed by a time or other
     * trailing text, e.g. "24/08/2026 02:07 PM") and returns it as
     * "yyyy-MM-dd <rest-of-string-unchanged>". If the leading text isn't a recognizable
     * date, returns [raw] completely unchanged (never throws, never drops data).
     */
    fun normalizeDateTime(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed

        val match = matchIso(trimmed)
            ?: matchNumericDayFirst(trimmed)
            ?: matchDayMonYear(trimmed)
            ?: matchDaySepMonSepYear(trimmed)
            ?: matchMonDayYear(trimmed)
            ?: matchNumericDayFirstShortYear(trimmed)
            ?: return trimmed

        if (match.month !in 1..12 || match.day !in 1..31) return trimmed

        val isoDate = "%04d-%02d-%02d".format(match.year, match.month, match.day)
        val rest = trimmed.substring(match.range.last + 1).trim().trimStart(',').trim()
        return if (rest.isBlank()) isoDate else "$isoDate $rest"
    }
}



