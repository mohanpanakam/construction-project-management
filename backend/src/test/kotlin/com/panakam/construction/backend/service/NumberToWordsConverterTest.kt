package com.panakam.construction.backend.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [NumberToWordsConverter] — used to auto-fill the {{TOTAL_AMOUNT_WORDS}}
 * agreement/registration placeholder (see AgreementRoutes.kt) with the Indian-numbering
 * "Rupees ... Only" form of the total consideration, so admins no longer need to type
 * this out by hand for every generated agreement.
 */
class NumberToWordsConverterTest {

    @Test
    fun `converts whole lakh amount with no paise`() {
        assertEquals(
            "Rupees Twenty-Five Lakh Fifty Thousand Only",
            NumberToWordsConverter.toIndianRupeesWords("25,50,000.00")
        )
    }

    @Test
    fun `converts crore amount`() {
        assertEquals(
            "Rupees One Crore Only",
            NumberToWordsConverter.toIndianRupeesWords("1,00,00,000.00")
        )
    }

    @Test
    fun `converts amount with paise`() {
        assertEquals(
            "Rupees One Thousand Two Hundred Fifty and Fifty Paise Only",
            NumberToWordsConverter.toIndianRupeesWords("1250.50")
        )
    }

    @Test
    fun `converts amount with no commas`() {
        assertEquals(
            "Rupees Nine Lakh Ninety-Nine Thousand Nine Hundred Ninety-Nine Only",
            NumberToWordsConverter.toIndianRupeesWords("999999.00")
        )
    }

    @Test
    fun `converts zero`() {
        assertEquals("Rupees Zero Only", NumberToWordsConverter.toIndianRupeesWords("0"))
    }

    @Test
    fun `converts complex mixed amount`() {
        // 12,34,56,789 -> Twelve Crore Thirty-Four Lakh Fifty-Six Thousand Seven Hundred Eighty-Nine
        assertEquals(
            "Rupees Twelve Crore Thirty-Four Lakh Fifty-Six Thousand Seven Hundred Eighty-Nine Only",
            NumberToWordsConverter.toIndianRupeesWords("12,34,56,789.00")
        )
    }

    @Test
    fun `blank or invalid input returns empty string`() {
        assertEquals("", NumberToWordsConverter.toIndianRupeesWords(""))
        assertEquals("", NumberToWordsConverter.toIndianRupeesWords("not a number"))
    }
}

