package com.panakam.construction.backend.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts a rupee amount (e.g. the numeric `TOTAL_AMOUNT` used across agreement templates
 * — see [com.panakam.construction.backend.routes.agreementRoutes]) into the words form
 * Indian sale agreements/registrations conventionally print alongside the numeric figure,
 * e.g. "Rupees Twenty-Five Lakh Fifty Thousand Only" for 2550000.00. Uses the Indian
 * numbering system (thousand / lakh / crore groupings), NOT the Western
 * thousand/million/billion grouping — since every amount in this app is INR and every
 * agreement/registration document is drafted for the Indian market.
 *
 * Kept intentionally separate from [DateUtils]/[OcrService] (different concern — this
 * converts a KNOWN clean numeric amount to words for DISPLAY on a generated document,
 * whereas OcrService only ever goes the other direction, words/glyphs -> a numeric string
 * parsed out of noisy OCR text).
 */
object NumberToWordsConverter {

    private val ONES = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen"
    )
    private val TENS = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    /** Converts an integer 0..999 to words (no "and" — kept out deliberately since Indian
     *  legal/financial documents conventionally omit it, e.g. "One Hundred Five" not "One
     *  Hundred and Five"). */
    private fun convertBelowThousand(n: Int): String {
        if (n == 0) return ""
        val sb = StringBuilder()
        var num = n
        if (num >= 100) {
            sb.append(ONES[num / 100]).append(" Hundred")
            num %= 100
            if (num > 0) sb.append(" ")
        }
        if (num in 1..19) {
            sb.append(ONES[num])
        } else if (num >= 20) {
            sb.append(TENS[num / 10])
            if (num % 10 > 0) sb.append("-").append(ONES[num % 10])
        }
        return sb.toString()
    }

    /**
     * Converts a non-negative whole-rupee integer amount into words using the Indian
     * numbering system: ...Crore, ...Lakh, ...Thousand, ...Hundred (each 2-digit group
     * after the first, except thousand/hundred split at 3, per the standard
     * 2-2-3 digit grouping — e.g. 12,34,56,789 = "Twelve Crore Thirty-Four Lakh
     * Fifty-Six Thousand Seven Hundred Eighty-Nine").
     */
    fun rupeesToWords(rupees: Long): String {
        if (rupees == 0L) return "Zero"
        require(rupees > 0) { "Amount must be non-negative" }

        var n = rupees
        val crore = n / 1_00_00_000; n %= 1_00_00_000
        val lakh = n / 1_00_000; n %= 1_00_000
        val thousand = n / 1_000; n %= 1_000
        val hundred = n

        val parts = mutableListOf<String>()
        if (crore > 0) parts += "${convertBelowThousand(crore.toInt())} Crore"
        if (lakh > 0) parts += "${convertBelowThousand(lakh.toInt())} Lakh"
        if (thousand > 0) parts += "${convertBelowThousand(thousand.toInt())} Thousand"
        if (hundred > 0) parts += convertBelowThousand(hundred.toInt())

        return parts.joinToString(" ").trim()
    }

    /**
     * Converts a full rupee amount (with optional paise) into the standard document-ready
     * words form, e.g. `rupeesAndPaiseToWords(2550000.00)` -> "Rupees Twenty-Five Lakh
     * Fifty Thousand Only" and `rupeesAndPaiseToWords(1250.50)` -> "Rupees One Thousand Two
     * Hundred Fifty and Fifty Paise Only". Accepts the same comma-formatted numeric string
     * already used for the `TOTAL_AMOUNT` placeholder (e.g. "25,50,000.00") as well as a
     * plain unformatted one — commas are stripped before parsing.
     */
    fun toIndianRupeesWords(amount: String): String {
        val cleaned = amount.replace(",", "").trim()
        val decimal = cleaned.toBigDecimalOrNull() ?: return ""
        return toIndianRupeesWords(decimal)
    }

    fun toIndianRupeesWords(amount: Double): String = toIndianRupeesWords(BigDecimal.valueOf(amount))

    fun toIndianRupeesWords(amount: BigDecimal): String {
        val rounded = amount.setScale(2, RoundingMode.HALF_UP)
        val rupees = rounded.toLong()
        val paise = rounded.subtract(BigDecimal(rupees)).multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP).toInt()

        val rupeesWords = rupeesToWords(rupees)
        return if (paise > 0) {
            "Rupees $rupeesWords and ${convertBelowThousand(paise)} Paise Only"
        } else {
            "Rupees $rupeesWords Only"
        }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(this)
    } catch (e: Exception) {
        null
    }
}

