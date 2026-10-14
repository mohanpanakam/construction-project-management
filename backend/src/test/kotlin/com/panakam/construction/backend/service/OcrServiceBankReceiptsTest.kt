package com.panakam.construction.backend.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression suite built from the RapidOCR output of real, auditor-AUDITED receipts in
 * production (Oct 2026 sweep of 66 receipts). Each fixture is the raw OCR text exactly as
 * the ocr-service returned it (including dropped spaces / misread characters), with payer
 * personal names anonymised. Add a new case here whenever a new bank/app layout fails.
 */
class OcrServiceBankReceiptsTest {

    private fun parse(vararg lines: String) = OcrService.parsePaymentText(lines.joinToString("\n"))

    // ── ICICI iMobile (UPI / IMPS "Payment successful") ──────────────────────

    @Test
    fun `ICICI iMobile - date with apostrophe 2-digit year and glued words`() {
        val p = parse(
            "iIcICIBank", "Payment successful!", "9,00,000.00", "ToHari", "S", "UNIONBANKOFINDIA",
            "546205010000129", "From TEST PAYER", "ICICIBANKLTD1072",
            "Paidat10:16PM,06Sep'26IST", "Tr.ID:ICICR12026090612688567"
        )
        assertEquals("900000.00", p.amount)
        assertTrue(p.paymentDate.startsWith("2026-09-06"), p.paymentDate)
        assertEquals("ICICR12026090612688567", p.transactionId)
        assertEquals("ICICR12026090612688567", p.utrNumber)
    }

    @Test
    fun `ICICI iMobile - Indian lakh grouping with crore-scale amount`() {
        val p = parse(
            "iICICIBank", "Paid successfully!", "46,68,000.00", "To Hari Jagadha", "ICICl Bank Limited",
            "369805000269", "From TEST PAYER", "Savings a/c", "Paidat10:59AM,25Jul'26IST", "Tr.ID:FGT7270832"
        )
        assertEquals("4668000.00", p.amount)
        assertTrue(p.paymentDate.startsWith("2026-07-25"), p.paymentDate)
        assertEquals("FGT7270832", p.transactionId)
        assertEquals("369805000269", p.beneficiaryAccount)
    }

    @Test
    fun `ICICI fund transfer screen - REFERENCE NUMBER label on its own line`() {
        val p = parse(
            "iICICIBank", "GOTOFUNDTRANSFER", "TRANSFERRINGFUNDS", "4,39,500.00", "successfully transferred",
            "TEST PAYER", "From", "625201047878-Savings", "S", "HARIJAGADHA", "To", "546205010000129",
            "REFERENCENUMBER", "624917378048"
        )
        assertEquals("439500.00", p.amount)
        assertEquals("624917378048", p.transactionId)
    }

    // ── Axis Bank mobile "Payment Complete" (IMPS) ───────────────────────────

    @Test
    fun `Axis payment complete - OCR reads thousands comma as a period`() {
        val p = parse(
            "7:27", "Payment Complete", "SENTTO", "SUCCESS", "903flat", "59.000.00", "000129",
            "Receipt no:", "XS99G48S0688", "Pay Now", "Mode:", "IMPS|Free", "Remarks:", "MOREDETAILS", "Done"
        )
        assertEquals("59000.00", p.amount)
        assertEquals("XS99G48S0688", p.transactionId)
        assertEquals("IMPS", p.transactionType)
    }

    @Test
    fun `Axis payment complete - receipt number with Indian lakh amount`() {
        val p = parse(
            "PaymentComplete", "SENTTO", "SUCCESS", "903flat", "00129", "5,00,000.00", "Receipt no:",
            "GTIW5DP56821", "Mode:", "Pay Now", "IMPS|Free", "Remarks:", "Investment", "IFSC", "UBIN0554626"
        )
        assertEquals("500000.00", p.amount)
        assertEquals("GTIW5DP56821", p.transactionId)
        assertEquals("IMPS", p.transactionType)
    }

    @Test
    fun `Union Bank style IMPS receipt - receipt no and RRN, numeric date`() {
        val p = parse(
            "Payment Complete", "SENTTO", "Devanahalli", "Apartment", "AMOUNT", "JC", "Builder", "Hari",
            "3,79,000.00", "XXXX-0129", "BRANCH:HSRLAYOUT,BANGALORE", "IFSC:", "UBIN0554626", "REMARKS:",
            "SENTFROM", "XXXX-5807", "SA", "Payment", "SUCCESS", "Details", "Pay Now", "MODE:", "IMPS|Free",
            "RECEIPTNO:", "393T74HN9673", "RRN:", "625106281363", "DATE:", "08/09/2026"
        )
        assertEquals("379000.00", p.amount)
        assertEquals("2026-09-08", p.paymentDate)
        assertEquals("393T74HN9673", p.transactionId)
        assertEquals("IMPS", p.transactionType)
    }

    // ── HDFC NetBanking / app ─────────────────────────────────────────────────

    @Test
    fun `HDFC RTGS request accepted - date and reference number`() {
        val p = parse(
            "2:00", "7,50,000", "Pay Again", "Share", "Request Accepted", "Aug24,2026",
            "You can check the status ofyour RTGSpaymentby", "visiting Recent Transactions.",
            "Transaction Summary", "Paid To:JAGADHABI CONSTRUCTIONS", "Union Bankof India",
            "SavingsA/c:546205010000129", "Paid By:TESTPAYER", "Savings A/c:********9200",
            "Payment Method", "BankTransfer|RTGS", "HDFC Transaction ID", "BB54R17875602104248282337",
            "Reference Number", "HDFCR52026082499728608", "Schedule transfer", "Home"
        )
        assertEquals("750000", p.amount)
        assertEquals("2026-08-24", p.paymentDate)
        assertEquals("RTGS", p.transactionType)
        assertEquals("HDFCR52026082499728608", p.utrNumber)
    }

    @Test
    fun `HDFC payment initiated screen - amount only, no id or date, must not invent any`() {
        val p = parse(
            "16:47", "HDFCBANK", "Send Money", "Payment", "3,00,000.00", "Initiated for", "Copy&Share",
            "Download", "To", "jagadhatri flat", "UnionBankofIndia", "From", "TESTPAYER", "Showmoredetails"
        )
        assertEquals("300000.00", p.amount)
        assertEquals("", p.transactionId)
        assertEquals("", p.paymentDate)
    }

    // ── Axis NetBanking ───────────────────────────────────────────────────────

    @Test
    fun `Axis netbanking payment summary - UTR with pipe-joined internal id keeps only the UTR`() {
        val p = parse(
            "PaymentSummary", "Payeename", "JAGADHABICONSTRUCTIONS", "Account number", "XXXXXXXXXXX0129",
            "Payee Bank", "UNIONBANKOFINDIA", "Amount", "2,300,000.00",
            "TwentyThreeLakhsRupeesAndZeroPaiseOnly", "Fromaccount", "Savings-xxxx3801", "Remarks",
            "Jagadhabi Devanahalli Flat 714", "UTR No.", "UTIBR72026091000551275|15RKZ0BJ1246",
            "Date", "10 Sep 2026", "Frequency", "One time"
        )
        assertEquals("2300000.00", p.amount)
        assertEquals("2026-09-10", p.paymentDate)
        assertEquals("UTIBR72026091000551275", p.utrNumber)
    }

    @Test
    fun `Axis netbanking transaction details - date glued to time and receipt number`() {
        val p = parse(
            "TransactionDetails", "Amount", "Rs.2,000,000.00", "Date.", "16September202618:27:47", "To",
            "JAGADHABICONSTRUCTIONS", "To account", "546205010000129", "From account", "Savings-xxxx3801",
            "Payment Type", "One time", "Payment mode", "RTGS", "Receipt No.", "FT-9N97X5IS4131"
        )
        assertEquals("2000000.00", p.amount)
        assertEquals("2026-09-16 18:27:47", p.paymentDate)
        assertEquals("RTGS", p.transactionType)
        assertEquals("FT-9N97X5IS4131", p.transactionId)
    }

    @Test
    fun `Axis netbanking payment success - reference number inside sentence`() {
        val p = parse(
            "Payment successful.Your Reference No is 4MY6MLWX0301.", "PaymentSummary", "Payee name",
            "JAGADHABICONSTRUCTIONS", "Account number", "XXXXXXXXXXX0129", "Payee Bank", "UNION BANKOFINDIA",
            "Amount", "200,000.00", "Two Lakh RupeesAnd ZeroPaiseOnly"
        )
        assertEquals("200000.00", p.amount)
        assertEquals("4MY6MLWX0301", p.transactionId)
    }

    // ── NEFT / RTGS credit alerts (UTR inside narration) ─────────────────────

    @Test
    fun `NEFT credit alert - UTR glued to sender name in narration`() {
        val p = parse(
            "Sender", "03-09-2026", "U37438821", "NEFT:RANIHABIBUNNISATAXSK262460004515",
            "No:AXSK26246000", "500.00", "14:13:34", "4515"
        )
        assertEquals("500.00", p.amount)
        assertEquals("2026-09-03 14:13:34", p.paymentDate)
        assertEquals("AXSK262460004515", p.utrNumber)
        assertEquals("NEFT", p.transactionType)
        assertEquals("", p.payerName, "a date line must never be taken as the payer name")
    }

    @Test
    fun `RTGS credit alert - UTR glued to sender name in narration`() {
        val p = parse(
            "05-09-2026", "81629306", "RTGS:RANIHABIBUNNISATUTIBR52026090500355379", "1500000.00", "13:41:16"
        )
        assertEquals("1500000.00", p.amount)
        assertEquals("UTIBR52026090500355379", p.utrNumber)
        assertEquals("RTGS", p.transactionType)
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    @Test
    fun `Axis RTGS credit SMS with labelled ref no`() {
        val p = OcrService.parsePaymentText(
            "Your RTGS txn with Ref. No. UTIBR72026090300572895 for INR 1000000.00 is credited to " +
                "beneficiary Jagadhabi Co, A/c no. XX0129 on 03-09-26 at 11:09:04 IST - Axis Bank"
        )
        assertEquals("1000000.00", p.amount)
        assertEquals("UTIBR72026090300572895", p.utrNumber)
        assertEquals("RTGS", p.transactionType)
    }

    // ── Guards: new heuristics must not damage other values ──────────────────

    @Test
    fun `dotted numeric dates are not mistaken for amounts with dot thousands separators`() {
        val p = parse("Amount Rs. 25000.00", "Date 06.09.2026")
        assertEquals("25000.00", p.amount)
    }

    @Test
    fun `DateUtils - short year with apostrophe, with and without spaces`() {
        assertEquals("2026-09-06", DateUtils.normalizeDateTime("06Sep'26"))
        assertEquals("2026-09-06", DateUtils.normalizeDateTime("06 Sep '26"))
        assertEquals("2026-09-06", DateUtils.normalizeDateTime("06Sep26"))
    }

    @Test
    fun `DateUtils - four digit year formats still win over short year`() {
        assertEquals("2026-09-06", DateUtils.normalizeDateTime("06 Sep 2026"))
        assertEquals("2026-08-24", DateUtils.normalizeDateTime("Aug24,2026"))
        assertEquals("2026-09-03", DateUtils.normalizeDateTime("03-09-2026"))
    }
}

