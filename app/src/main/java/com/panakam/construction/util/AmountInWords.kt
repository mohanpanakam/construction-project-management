package com.panakam.construction.util

import kotlin.math.roundToLong

/**
 * Converts a rupee amount to its Indian-English words form (e.g. 150000.0 ->
 * "One Lakh Fifty Thousand Rupees Only"), using the Indian numbering system
 * (Lakh/Crore groupings, not Western thousand/million) since all amounts in
 * this app are Indian Rupees.
 *
 * Added so that whenever a user enters/extracts a payment amount (typed by
 * hand, OCR'd from a receipt screenshot, or parsed from a pasted bank/UPI
 * SMS), the screen can ALSO show it spelled out in words right next to the
 * numeric value — the exact same way a cheque or a bank receipt does. This
 * was requested specifically to remove the "confusion when uploading a
 * payment screenshot/message" — seeing BOTH "₹ 1,50,000.00" and "One Lakh
 * Fifty Thousand Rupees Only" side by side makes it immediately obvious
 * whether OCR/parsing got the right number of zeros (a very easy mistake to
 * miss by eye on a long digit string, but not when it's also read out as
 * words).
 */
object AmountInWords {

    private val ONES = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )
    private val TENS = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    /** Converts an integer 0-999 to words (no trailing/leading spaces). */
    private fun threeDigitsToWords(n: Int): String {
        if (n == 0) return ""
        val hundreds = n / 100
        val rest = n % 100
        val parts = mutableListOf<String>()
        if (hundreds > 0) parts += "${ONES[hundreds]} Hundred"
        if (rest > 0) {
            parts += if (rest < 20) ONES[rest]
            else {
                val tensPart = TENS[rest / 10]
                val onesPart = ONES[rest % 10]
                if (onesPart.isBlank()) tensPart else "$tensPart $onesPart"
            }
        }
        return parts.joinToString(" ")
    }

    /** Converts a non-negative whole number of rupees to Indian-numbering words
     *  (no "Rupees"/"Only" suffix — see [toWords] for the full phrase). */
    private fun wholeRupeesToWords(amount: Long): String {
        if (amount == 0L) return "Zero"
        var n = amount
        val crore = n / 1_00_00_000; n %= 1_00_00_000
        val lakh = n / 1_00_000; n %= 1_00_000
        val thousand = n / 1_000; n %= 1_000
        val hundred = n

        val parts = mutableListOf<String>()
        if (crore > 0) parts += "${threeDigitsToWords(crore.toInt())} Crore"
        if (lakh > 0) parts += "${threeDigitsToWords(lakh.toInt())} Lakh"
        if (thousand > 0) parts += "${threeDigitsToWords(thousand.toInt())} Thousand"
        if (hundred > 0) parts += threeDigitsToWords(hundred.toInt())
        return parts.joinToString(" ")
    }

    /**
     * Full phrase for a rupee amount, Indian-cheque style, e.g.:
     *   150000.0   -> "One Lakh Fifty Thousand Rupees Only"
     *   150000.50  -> "One Lakh Fifty Thousand Rupees and Fifty Paise Only"
     *   0.0        -> "Zero Rupees Only"
     * Returns "" for a negative amount (shouldn't occur for a payment, but
     * never throws/crashes the UI over bad input).
     */
    fun toWords(amount: Double): String {
        if (amount < 0 || amount.isNaN() || amount.isInfinite()) return ""
        val totalPaise = (amount * 100).roundToLong()
        val rupees = totalPaise / 100
        val paise = totalPaise % 100

        val rupeesWords = "${wholeRupeesToWords(rupees)} Rupee${if (rupees == 1L) "" else "s"}"
        return if (paise > 0L) {
            val paiseWords = "${threeDigitsToWords(paise.toInt())} Paise"
            "$rupeesWords and $paiseWords Only"
        } else {
            "$rupeesWords Only"
        }
    }

    /** Same as [toWords], but takes a raw text-field string (e.g. "1,50,000" or
     *  "150000.5") and returns "" for blank/unparseable input instead of throwing —
     *  convenient for live-updating a Compose Text as the user types an amount. */
    fun toWords(amountText: String): String {
        val cleaned = amountText.replace(",", "").trim()
        val value = cleaned.toDoubleOrNull() ?: return ""
        return toWords(value)
    }
}

