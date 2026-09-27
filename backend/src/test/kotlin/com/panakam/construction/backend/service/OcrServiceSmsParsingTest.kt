package com.panakam.construction.backend.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for parsing a bank RTGS/NEFT/IMPS confirmation SMS pasted directly by the
 * user (as opposed to OCR'd from a receipt image/PDF) — added after a real bug where the
 * single-line, unstructured nature of SMS text (no "Label: Value" layout) caused
 * [OcrService.parsePaymentText] to return garbled beneficiary name/bank ("09:04 IST - Axis
 * Bank") and a blank beneficiary account, because [extractField] naively split the WHOLE
 * line on its first colon regardless of where that colon actually was relative to the
 * matched keyword — and the only colon on this SMS happens to sit inside the transaction
 * TIME ("11:09:04"), nowhere near the "beneficiary"/"to" keyword matched near the start.
 */
class OcrServiceSmsParsingTest {
    @Test
    fun `parses beneficiary name, account, amount, date, UTR and type from an RTGS credit SMS`() {
        val sms = "Your RTGS txn with Ref. No. UTIBR72026090300572895 for INR 1000000.00 is credited to " +
            "beneficiary Jagadhabi Co, A/c no. XX0129 on 03-09-26 at 11:09:04 IST - Axis Bank"

        val parsed = OcrService.parsePaymentText(sms)

        assertEquals("1000000.00", parsed.amount)
        assertEquals("2026-09-03 11:09:04", parsed.paymentDate)
        assertEquals("UTIBR72026090300572895", parsed.transactionId)
        assertEquals("UTIBR72026090300572895", parsed.utrNumber)
        assertEquals("RTGS", parsed.transactionType)
        assertEquals("Jagadhabi Co", parsed.beneficiaryName)
        assertEquals("XX0129", parsed.beneficiaryAccount)
    }

    /**
     * Regression test for a real receipt that failed to decode properly in production on
     * 2026-09-24 (see AGENT_NOTES): PaddleOCR misread the digit '0' as the letter 'o' inside
     * the amount ("1,ooo.o0" instead of "1,000.00"), leaving [ParsedPayment.amount] blank;
     * and a stacked "Payment Method"/IMPS value being too short/digit-free for the old
     * [OcrService] value-line heuristic broke positional label/value pairing for the
     * Reference Number that followed it, leaving transactionId/utrNumber blank too — even
     * though the reference number was right there in the OCR'd text.
     */
    @Test
    fun `parses amount and reference number from a garbled IMPS fund-transfer receipt`() {
        val raw = "FUND TRANSFER\n" +
            "1,ooo.o0 successfully transferred\n" +
            "JAGADHABHICO\n" +
            "From\n" +
            "003101541747-Savings\n" +
            "546205010000129\n" +
            "REMAR\n" +
            "PAYMENT METHOD\n" +
            "TRANSACTION DATE\n" +
            "REFERENCENUMBER\n" +
            "IMPS\n" +
            "24September 2026\n" +
            "626714809350\n" +
            "DOWNLOAD E-RECEIPT\n" +
            "REPEAT THIS TRANSACTION\n" +
            "WHAT'S NEXT"

        val parsed = OcrService.parsePaymentText(raw)

        assertEquals("1000.00", parsed.amount)
        assertEquals("IMPS", parsed.transactionType)
        assertEquals("626714809350", parsed.utrNumber)
        assertEquals("626714809350", parsed.transactionId)
        // The account number glued to "Savings" must NOT be mistaken for the payer's name.
        assertEquals("", parsed.payerName)
    }
}

