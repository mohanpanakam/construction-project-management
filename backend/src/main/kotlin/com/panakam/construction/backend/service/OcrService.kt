package com.panakam.construction.backend.service

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Extracts and parses payment details from PDF receipts.
 * Supports:
 *  - PDFs  : full text extraction via Apache PDFBox
 *  - Images: caller passes pre-extracted text (e.g. from Android ML Kit)
 */
object OcrService {

    // ── PDF extraction ────────────────────────────────────────────────────────

    fun extractTextFromPdf(inputStream: InputStream): String {
        return try {
            val bytes = inputStream.readBytes()
            Loader.loadPDF(bytes).use { doc ->
                PDFTextStripper().getText(doc)
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ── Transaction detail parser ─────────────────────────────────────────────

    data class ParsedPayment(
        val amount: String             = "",
        val paymentDate: String        = "",
        val transactionId: String      = "",
        val transactionType: String    = "",
        val payerName: String          = "",
        val payerBank: String          = "",
        val payerAccount: String       = "",
        val beneficiaryName: String    = "",
        val beneficiaryBank: String    = "",
        val beneficiaryAccount: String = ""
    )

    fun parsePaymentText(text: String): ParsedPayment {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val full  = lines.joinToString(" ")

        return ParsedPayment(
            amount             = extractAmount(full),
            paymentDate        = extractDate(full),
            transactionId      = extractTransactionId(full),
            transactionType    = extractTransactionType(full),
            payerName          = extractField(lines, listOf("from", "remitter", "payer", "sender", "debit a/c name", "account holder")),
            payerBank          = extractField(lines, listOf("from bank", "remitter bank", "sending bank", "debit bank")),
            payerAccount       = extractAccount(full, listOf("from a/c", "debit a/c", "from account", "payer account")),
            beneficiaryName    = extractField(lines, listOf("to", "beneficiary", "recipient", "credit a/c name", "payee")),
            beneficiaryBank    = extractField(lines, listOf("to bank", "beneficiary bank", "credit bank")),
            beneficiaryAccount = extractAccount(full, listOf("to a/c", "credit a/c", "to account", "beneficiary account"))
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun extractAmount(text: String): String {
        // Patterns: ₹1,23,456.00  |  Rs. 50000  |  INR 75000  |  Amount: 25000.00
        val patterns = listOf(
            Regex("""(?:₹|Rs\.?|INR|Amount[:\s]+)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""([\d,]{4,}(?:\.\d{1,2})?)\s*(?:/-|only)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            return m.groupValues[1].replace(",", "")
        }
        return ""
    }

    private fun extractDate(text: String): String {
        val patterns = listOf(
            // DD/MM/YYYY or DD-MM-YYYY
            Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{4})\b"""),
            // YYYY-MM-DD
            Regex("""\b(\d{4}-\d{2}-\d{2})\b"""),
            // DD Mon YYYY  e.g. 15 Aug 2026
            Regex("""\b(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\b""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            // also try to capture time
            val dateStr = m.groupValues[1]
            val timePattern = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?)\b""", RegexOption.IGNORE_CASE)
            val time = timePattern.find(text.substring(m.range.first))?.groupValues?.get(1) ?: ""
            return if (time.isNotBlank()) "$dateStr $time" else dateStr
        }
        return ""
    }

    private fun extractTransactionId(text: String): String {
        val patterns = listOf(
            Regex("""(?:UTR|Ref(?:erence)?|Txn|Transaction|Cheque)[^\d]*(?:No\.?|Number|#|ID|Id)?[:\s]+([A-Z0-9]{8,30})""", RegexOption.IGNORE_CASE),
            Regex("""(?:UPI Ref|IMPS Ref|NEFT Ref|RTGS Ref)[:\s]+([A-Z0-9]+)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            return m.groupValues[1].trim()
        }
        return ""
    }

    private fun extractTransactionType(text: String): String {
        val upper = text.uppercase()
        return when {
            "NEFT" in upper  -> "NEFT"
            "RTGS" in upper  -> "RTGS"
            "IMPS" in upper  -> "IMPS"
            "UPI"  in upper  -> "UPI"
            "CHEQUE" in upper || "CHQ" in upper -> "Cheque"
            "DEMAND DRAFT" in upper || "DD" in upper -> "DD"
            "CASH" in upper  -> "Cash"
            else             -> ""
        }
    }

    private fun extractField(lines: List<String>, keywords: List<String>): String {
        for (line in lines) {
            val lower = line.lowercase()
            for (kw in keywords) {
                if (lower.contains(kw)) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) return parts[1].trim()
                }
            }
        }
        return ""
    }

    private fun extractAccount(text: String, labels: List<String>): String {
        for (label in labels) {
            val pattern = Regex("""${Regex.escape(label)}[:\s]+([X\d]{4,20})""", RegexOption.IGNORE_CASE)
            val m = pattern.find(text) ?: continue
            return m.groupValues[1].trim()
        }
        // Generic masked account pattern  XXXX1234
        val masked = Regex("""[Xx*]{4,}\s*(\d{4})""").find(text)
        if (masked != null) return "XXXX${masked.groupValues[1]}"
        return ""
    }
}

