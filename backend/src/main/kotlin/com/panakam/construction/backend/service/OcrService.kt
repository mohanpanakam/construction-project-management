package com.panakam.construction.backend.service

import aws.sdk.kotlin.services.textract.TextractClient
import aws.sdk.kotlin.services.textract.model.BlockType
import aws.sdk.kotlin.services.textract.model.DetectDocumentTextRequest
import aws.sdk.kotlin.services.textract.model.Document
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Extracts and parses payment details from PDF receipts.
 * Supports:
 *  - PDFs   : full text extraction via Apache PDFBox
 *  - Images : local, free OCR via Tesseract (Tess4J) — no cloud cost.
 *             AWS Textract remains available as an opt-in, higher-accuracy alternative.
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

    /** Free, local, offline OCR using Tesseract via Tess4J. No AWS/cloud cost. */
    fun extractTextFromImageLocal(imageBytes: ByteArray): String {
        return try {
            if (imageBytes.isEmpty()) return ""
            val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return ""
            val tesseract = Tesseract().apply {
                setDatapath(System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata")
                setLanguage("eng")
                setPageSegMode(6) // assume a single uniform block of text
            }
            tesseract.doOCR(image) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun extractTextFromImage(textractClient: TextractClient, imageBytes: ByteArray): String {
        return try {
            if (imageBytes.isEmpty()) return ""
            val response = textractClient.detectDocumentText(DetectDocumentTextRequest {
                document = Document {
                    bytes = imageBytes
                }
            })
            response.blocks
                ?.asSequence()
                ?.filter { it.blockType == BlockType.Line }
                ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotBlank() } }
                ?.joinToString("\n")
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }


    // ── Transaction detail parser ─────────────────────────────────────────────

    data class ParsedPayment(
        val amount: String             = "",
        val paymentDate: String        = "",
        val transactionId: String      = "",
        val transactionType: String    = "",
        val chequeNumber: String       = "",
        val chequeDate: String         = "",
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

        val maskedAccounts = extractMaskedAccounts(full)
        val payerAccount = extractAccount(full, listOf("from a/c", "debit a/c", "from account", "payer account"))
            .ifBlank { maskedAccounts.getOrNull(0) ?: "" }
        val beneficiaryAccount = extractAccount(full, listOf("to a/c", "credit a/c", "to account", "beneficiary account"))
            .ifBlank { maskedAccounts.getOrNull(1) ?: "" }

        val beneficiaryBank = extractField(lines, listOf("to bank", "beneficiary bank", "credit bank"))
        val payerBank = extractField(lines, listOf("from bank", "remitter bank", "sending bank", "debit bank"))
            .ifBlank { if (beneficiaryBank.isBlank()) extractField(lines, listOf("bank")) else "" }

        return ParsedPayment(
            amount             = extractAmount(full),
            paymentDate        = extractDate(full),
            transactionId      = extractTransactionId(full),
            transactionType    = extractTransactionType(full),
            chequeNumber       = extractInstrumentNumber(full),
            chequeDate         = extractInstrumentDate(full),
            payerName          = extractField(lines, listOf("from", "remitter", "payer", "sender", "paid by", "debit a/c name", "account holder")),
            payerBank          = payerBank,
            payerAccount       = payerAccount,
            beneficiaryName    = extractField(lines, listOf("paid to", "to", "beneficiary", "recipient", "credit a/c name", "payee")),
            beneficiaryBank    = beneficiaryBank,
            beneficiaryAccount = beneficiaryAccount
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun extractAmount(text: String): String {
        // Patterns: ₹1,23,456.00  |  Rs. 50000  |  INR 75000  |  Amount: 25000.00
        // Note: OCR frequently misreads the ₹ glyph as ~, -, =, or Z — accept those too.
        val patterns = listOf(
            Regex("""(?:₹|~|Rs\.?|INR|Amount[:\s]+)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""([\d,]{4,}(?:\.\d{1,2})?)\s*(?:/-|only)""", RegexOption.IGNORE_CASE),
            // Fallback: Indian-format comma-grouped number (e.g. 4,99,000.00 or 12,34,567)
            // with no currency prefix at all — happens when OCR drops the ₹ glyph entirely.
            Regex("""\b(\d{1,2}(?:,\d{2})+,\d{3}(?:\.\d{1,2})?)\b""")
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
            Regex("""\b(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\b""", RegexOption.IGNORE_CASE),
            // DD-Mon-YYYY or DD/Mon/YYYY  e.g. 03-Sep-2026
            Regex("""\b(\d{1,2}[/-](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[/-]\d{4})\b""", RegexOption.IGNORE_CASE)
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
            "CHEQUE" in upper || "CHQ" in upper || "CHEQUE NO" in upper -> "Cheque"
            "DEMAND DRAFT" in upper || "D/D" in upper || " DD " in " $upper " -> "DD"
            "CASH" in upper  -> "Cash"
            else             -> ""
        }
    }

    private fun extractInstrumentNumber(text: String): String {
        val patterns = listOf(
            Regex("""(?:Cheque|Chq|Check|DD|D/D|Demand Draft)[^\n\r\d]*(?:No\.?|Number|#|ID)?[:\s-]*([A-Z0-9]{4,20})""", RegexOption.IGNORE_CASE),
            Regex("""(?:Instrument)\s*(?:No\.?|Number|#)?[:\s-]*([A-Z0-9]{4,20})""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            return m.groupValues[1].trim()
        }
        return ""
    }

    private fun extractInstrumentDate(text: String): String {
        val scopedPatterns = listOf(
            Regex("""(?:Cheque|Chq|DD|D/D|Demand Draft)[^\n\r]{0,50}?(\d{1,2}[/-]\d{1,2}[/-]\d{4})""", RegexOption.IGNORE_CASE),
            Regex("""(?:Cheque|Chq|DD|D/D|Demand Draft)[^\n\r]{0,50}?(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)
        )
        for (p in scopedPatterns) {
            val m = p.find(text) ?: continue
            return m.groupValues[1].trim()
        }
        return ""
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
        return ""
    }

    /** Finds all masked account numbers (e.g. XXXX1234) in order of appearance. */
    private fun extractMaskedAccounts(text: String): List<String> =
        Regex("""[Xx*]{4,}\s*(\d{4})""").findAll(text).map { "XXXX${it.groupValues[1]}" }.toList()
}

