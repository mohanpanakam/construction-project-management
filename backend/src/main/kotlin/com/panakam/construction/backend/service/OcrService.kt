package com.panakam.construction.backend.service

import aws.sdk.kotlin.services.textract.TextractClient
import aws.sdk.kotlin.services.textract.model.BlockType
import aws.sdk.kotlin.services.textract.model.DetectDocumentTextRequest
import aws.sdk.kotlin.services.textract.model.Document
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
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
    private val log = LoggerFactory.getLogger(OcrService::class.java)

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
            newTesseractInstance().doOCR(image) ?: ""
        } catch (e: Throwable) {
            // Previously this silently swallowed all errors, which made it impossible to
            // tell (from logs) whether OCR is genuinely misreading a receipt vs. Tesseract
            // simply not being installed/available on the runtime image (the classic
            // symptom when the Docker image was built/deployed before the tesseract-ocr
            // apt package was added, or on a fresh EC2 host that never had it installed).
            log.warn(
                "Local Tesseract OCR failed ({}): {}. If this happens for every receipt, " +
                "verify tesseract-ocr is installed in the running container " +
                "(docker exec <container> tesseract --version) and that TESSDATA_PREFIX " +
                "points to a valid tessdata directory.",
                e.javaClass.simpleName, e.message
            )
            ""
        }
    }

    private fun newTesseractInstance(): Tesseract = Tesseract().apply {
        setDatapath(System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata")
        setLanguage("eng")
        setPageSegMode(6) // assume a single uniform block of text
    }

    /**
     * Startup / diagnostic self-test: runs Tesseract against a tiny in-memory image to
     * confirm the native tesseract binary + trained data are actually reachable, instead
     * of only finding out the first time a real user uploads a receipt (where a failure
     * silently looks like "OCR just didn't extract anything").
     *
     * Returns Pair(available, message) — message explains what went wrong when not available.
     */
    fun checkTesseractAvailable(): Pair<Boolean, String> {
        return try {
            val probe = BufferedImage(100, 30, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, 100, 30)
                    color = java.awt.Color.BLACK
                    drawString("TEST", 10, 20)
                    dispose()
                }
            }
            newTesseractInstance().doOCR(probe)
            true to "ok"
        } catch (e: Throwable) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            log.warn(
                "Tesseract OCR self-test FAILED at startup — image receipt OCR will not " +
                "work until this is fixed. Cause: {}. Likely fix: rebuild the Docker image " +
                "(tesseract-ocr apt package must be installed — see Dockerfile) and redeploy, " +
                "or set OCR_PROVIDER=TEXTRACT to use AWS Textract instead.",
                msg
            )
            false to msg
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

    /**
     * Calls the PaddleOCR sidecar microservice (see ocr-service/main.py) over plain HTTP.
     * PaddleOCR has no JVM/Kotlin binding, so it runs as a separate Python container on
     * the internal Docker network; this just POSTs the image bytes as multipart form data
     * and returns the recognized text (one line per detected text region), same shape as
     * [extractTextFromImageLocal]/[extractTextFromImage] so the line-based parser in
     * [parsePaymentText] works identically regardless of which OCR engine produced it.
     */
    suspend fun extractTextFromImagePaddle(client: HttpClient, baseUrl: String, imageBytes: ByteArray): String {
        return try {
            if (imageBytes.isEmpty()) return ""
            val response: HttpResponse = client.submitFormWithBinaryData(
                url = "$baseUrl/ocr",
                formData = formData {
                    append("file", imageBytes, Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"receipt.jpg\"")
                    })
                }
            )
            if (!response.status.isSuccess()) {
                log.warn("PaddleOCR service returned {}: {}", response.status, response.bodyAsText())
                return ""
            }
            val json = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText())
                .jsonObject
            json["text"]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            log.warn("PaddleOCR service call failed ({}): {}. Is the 'ocr-service' container " +
                "running and reachable at {}?", e.javaClass.simpleName, e.message, baseUrl)
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
            .ifBlank { extractAccountNearLabel(lines, listOf("debit account", "from account", "sender account", "payer account")) }
            .ifBlank { maskedAccounts.getOrNull(0) ?: "" }
        val beneficiaryAccount = extractAccount(full, listOf("to a/c", "credit a/c", "to account", "beneficiary account"))
            .ifBlank { extractAccountNearLabel(lines, listOf("credit account", "to account", "receiver account", "beneficiary account")) }
            .ifBlank { maskedAccounts.getOrNull(1) ?: "" }
            // Last resort for bank/UPI screenshots that show "<BANK NAME>:" followed by a
            // bare account/UPI number on the next line, with no explicit "credit/to account"
            // label at all (e.g. the receiving business's own bank block at the top).
            .ifBlank { extractAccountAfterBankLine(lines).takeIf { it != payerAccount } ?: "" }

        val beneficiaryBank = extractField(lines, listOf("to bank", "beneficiary bank", "credit bank"))
            .ifBlank { extractField(lines, listOf("bank")) }
        val payerBank = extractField(lines, listOf("from bank", "remitter bank", "sending bank", "debit bank"))
            .ifBlank { if (beneficiaryBank.isBlank()) extractField(lines, listOf("bank")) else "" }

        return ParsedPayment(
            amount             = extractAmount(full),
            paymentDate        = extractDate(full),
            transactionId      = extractTransactionId(lines),
            transactionType    = extractTransactionType(full),
            chequeNumber       = extractInstrumentNumber(lines),
            chequeDate         = extractInstrumentDate(lines),
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
        // Note: OCR frequently misreads the ₹ glyph as ~, -, =, Z, or a bare "R" directly
        // glued to the digits (no space) — e.g. "R200000.00" on SBI YONO screenshots.
        // The `R(?=\d)` lookahead requires a digit IMMEDIATELY after the R (no whitespace).
        // The `(?<![A-Za-z0-9])` lookbehind is equally important: it requires the R NOT be
        // glued to a preceding letter/digit — otherwise this false-positives on reference/
        // transaction IDs that happen to contain "R" directly before a digit run, e.g.
        // "BB54R17875590429658948938" or "HDFCR52026082499708513" (both real IDs seen on
        // an HDFC RTGS receipt) — without the lookbehind, the 20-digit ID after the R got
        // mistaken for the amount.
        val patterns = listOf(
            Regex("""(?:₹|~|Rs\.?|(?<![A-Za-z0-9])R(?=\d)|INR|Amount[:\s]+)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // Indian-format comma-grouped number (e.g. 4,99,000.00 or 12,34,567 or 5,00,000)
            // with no currency prefix at all — happens when OCR drops the ₹ glyph entirely.
            // Decimal part is optional (Indian UPI screenshots very often show round amounts
            // with no paise, e.g. "5,00,000"). MUST be tried BEFORE the plain Western-style
            // pattern below: e.g. for "5,00,000.00", the Western pattern's \d{1,3} greedily
            // matches only 2 leading digits, then its mandatory 3-digit comma-group can't
            // align with the 2-digit Indian grouping at that position — but the regex engine
            // just retries starting from the NEXT digit run instead ("00,000.00"), silently
            // producing a truncated "00000.00" instead of failing outright. Trying this
            // Indian-aware pattern first (its own backtracking naturally handles the mixed
            // 2-then-3-digit grouping correctly) avoids ever reaching that broken submatch.
            Regex("""\b(\d{1,2}(?:,\d{2})+,\d{3}(?:\.\d{1,2})?)\b"""),
            // Plain Western-style thousands-grouped amount with cents, no currency symbol at
            // all — very common on bank/UPI app "success" screenshots where the amount is
            // shown as its own big line (e.g. "50,000.00"). Must have a decimal part so we
            // don't accidentally swallow account/reference numbers.
            Regex("""\b(\d{1,3}(?:,\d{3})+\.\d{2})\b"""),
            Regex("""([\d,]{4,}(?:\.\d{1,2})?)\s*(?:/-|only)""", RegexOption.IGNORE_CASE),
            // Last resort: a bare no-comma decimal amount with no currency prefix/symbol at
            // all (e.g. "200000.00" on its own line) — needs at least 3 digits before the
            // decimal point to avoid swallowing small numbers/percentages elsewhere.
            Regex("""\b(\d{3,}\.\d{2})\b""")
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
            // DD Mon YYYY  e.g. 15 Aug 2026 (or merged "15Aug2026" — `\s*` handles both)
            Regex("""\b(\d{1,2}\s*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s*\d{4})\b""", RegexOption.IGNORE_CASE),
            // DD-Mon-YYYY or DD/Mon/YYYY  e.g. 03-Sep-2026
            Regex("""\b(\d{1,2}[/-](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[/-]\d{4})\b""", RegexOption.IGNORE_CASE),
            // Mon DD, YYYY  e.g. "Aug 24, 2026" — common on bank/UPI app "success" screens.
            // Separators are `\s*` (zero-or-more), not `\s+`, because OCR sometimes glues
            // the month/day/year together with NO space at all, e.g. "Aug25,2026" (RTGS
            // "Request Accepted" screen) — with `\s+` this pattern silently failed to match
            // at all, leaving paymentDate blank.
            Regex("""\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s*\d{1,2},?\s*\d{4})\b""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            // also try to capture time
            val dateStr = m.groupValues[1]
            val timePattern = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?)\b""", RegexOption.IGNORE_CASE)
            val time = timePattern.find(text.substring(m.range.first))?.groupValues?.get(1) ?: ""
            // Normalize to "yyyy-MM-dd" regardless of which of the patterns above matched —
            // otherwise the SAME receipt format inconsistency ("24/08/2026" vs "2026-08-24"
            // vs "Aug 24, 2026") ends up stored verbatim, making dates impossible to sort/
            // compare/deduplicate reliably. See DateUtils for details.
            return DateUtils.normalizeDateTime(if (time.isNotBlank()) "$dateStr $time" else dateStr)
        }
        return ""
    }

    /**
     * Finds the position of any of [keywords] in [line] as a whole word/phrase (not a
     * substring buried inside a longer, unrelated word) — e.g. "\bdd\b" won't match inside
     * "added", and "\bref\b" won't match inside "reference" (that's handled by also listing
     * "reference" explicitly). Case-insensitive.
     */
    private fun findKeywordRange(line: String, keywords: List<String>): IntRange? {
        for (kw in keywords) {
            val m = Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE).find(line) ?: continue
            return m.range
        }
        return null
    }

    /**
     * Transaction/UTR/reference ID extraction — operates strictly PER LINE.
     *
     * Root cause this fixes: the previous implementation ran its regex against the whole
     * document joined into ONE line (`lines.joinToString(" ")`), with `[^\d]*` intended to
     * stop at newlines — but since there were no newlines left after joining, that filler
     * could span hundreds of characters across totally unrelated sentences. E.g. the common
     * English word "check" (from "...you can check the status...") would match, then the
     * filler would run all the way to the next digit sequence anywhere later in the
     * document — silently grabbing an unrelated account number as the "transaction ID".
     * Restricting matching to one line at a time makes that structurally impossible.
     */
    private fun extractTransactionId(lines: List<String>): String {
        // Each pattern requires an explicit "Number"/"No"/"ID" qualifier right after the
        // label word (`\s*` allows either a real space OR zero characters, so this matches
        // BOTH normally-spaced labels like "Transaction Number" AND OCR-merged ones like
        // "TransactionNumber" with a single pattern). The qualifier is mandatory for
        // transaction/reference because bare "transaction"/"reference" show up constantly in
        // OTHER non-ID lines on bank/UPI screenshots — "Transaction Successful!",
        // "Transaction Date", "Transaction Time", "Transaction Details", "Transaction
        // Status" — which must NOT be mistaken for the ID label (previously they were,
        // causing whatever line came right after one of those headers, e.g. the amount, to
        // get grabbed as the "transaction ID"). UTR/cheque keep the qualifier optional since
        // a bare "UTR:" or "Cheque:" line directly followed by the value is common and not
        // ambiguous with any other frequent non-ID label.
        val labelPatterns = listOf(
            Regex("""\butr\s*(?:number|no\.?|id)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:transaction|txn)\s*(?:number|no\.?|id)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:reference|ref)\s*(?:number|no\.?|id)\b""", RegexOption.IGNORE_CASE),
            // "Receipt no:" / "Receipt Number" — seen on IMPS/UPI app receipts as the
            // primary reference ID when there's no separate "Transaction Number" field.
            Regex("""\breceipt\s*(?:number|no\.?|id)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcheque\s*(?:number|no\.?)?\b""", RegexOption.IGNORE_CASE)
        )
        for (i in lines.indices) {
            val line  = lines[i]
            val range = labelPatterns.firstNotNullOfOrNull { it.find(line)?.range } ?: continue
            val after = line.substring(range.last + 1)
            val inline = Regex("""[:\s#-]*([A-Za-z0-9]{8,30})""").find(after)?.groupValues?.get(1)?.trim()
            // A real ID is always alphanumeric WITH at least one digit — this rejects the
            // false-positive of capturing another nearby label word (all letters, no digits).
            if (inline != null && inline.any { it.isDigit() }) return inline

            // Label-only line — the value is on the NEXT line (common in bank/UPI app
            // screenshots, e.g. "Transaction Number" \n "Y2M1307626577391009792").
            if (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (next.length in 8..30 && next.any { it.isDigit() } && next.none { it.isWhitespace() }) return next
            }
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

    /** Same per-line fix as [extractTransactionId] — see its comment for why this can't
     *  safely search the whole joined document (bare "Check"/"DD" are common English
     *  substrings/words that would otherwise match unrelated numbers far away). */
    private fun extractInstrumentNumber(lines: List<String>): String {
        val keywords = listOf("cheque", "chq", "dd", "demand draft", "instrument")
        for (line in lines) {
            val range = findKeywordRange(line, keywords) ?: continue
            val after = line.substring(range.last + 1)
            val inline = Regex("""[:\s#-]*(?:No\.?|Number|#|ID)?[:\s-]*([A-Za-z0-9]{4,20})""").find(after)?.groupValues?.get(1)?.trim()
            if (inline != null && inline.any { it.isDigit() }) return inline
        }
        return ""
    }

    private fun extractInstrumentDate(lines: List<String>): String {
        val keywords = listOf("cheque", "chq", "dd", "demand draft")
        for (line in lines) {
            val range = findKeywordRange(line, keywords) ?: continue
            val after = line.substring(range.last + 1)
            val m = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{4})""").find(after)
                ?: Regex("""(\d{4}-\d{2}-\d{2})""").find(after)
            if (m != null) return DateUtils.normalizeDateTime(m.groupValues[1].trim())
        }
        return ""
    }

    private fun extractField(lines: List<String>, keywords: List<String>): String {
        for (i in lines.indices) {
            val line  = lines[i]
            val lower = line.lowercase()
            for (kw in keywords) {
                if (lower.contains(kw)) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val before = parts[0].trim()
                        val after  = parts[1].trim()
                        if (after.isNotBlank()) return after
                        // Nothing after the colon on this line. Two possibilities:
                        //  1) The line itself IS the value with a trailing colon, e.g.
                        //     "UNION BANK OF INDIA:" (common in bank/UPI app screenshots) —
                        //     here `before` is much longer than just the bare keyword, so
                        //     treat it as a self-contained value.
                        //  2) It's a real "Label:" line with the value on the NEXT line.
                        if (before.length > kw.length + 2) return before
                        if (i + 1 < lines.size) {
                            val next = lines[i + 1].trim()
                            if (next.isNotBlank()) return next
                        }
                    }
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

    /**
     * Handles bank/UPI app screenshots where a label sits on its OWN line and the actual
     * account number is on the line right after it, e.g.:
     *   "Debit Account"
     *   "Savings Account - 20392915262"
     * Pulls the first run of 6-20 digits out of that following line.
     */
    private fun extractAccountNearLabel(lines: List<String>, labels: List<String>): String {
        for (i in lines.indices) {
            val lower = lines[i].lowercase()
            if (labels.any { lower.contains(it) } && i + 1 < lines.size) {
                val digits = Regex("""\d{6,20}""").find(lines[i + 1])?.value
                if (digits != null) return digits
            }
        }
        return ""
    }

    /**
     * Last-resort fallback: a line naming a bank (contains "bank") followed by a line that
     * contains a digit run — common when a bank/UPI screenshot shows the counterparty's
     * bank name and account/UPI number as a pair with no explicit "credit/beneficiary
     * account" label at all (e.g. "Union Bank of India" \n "Savings A/c: 546205010000129").
     */
    private fun extractAccountAfterBankLine(lines: List<String>): String {
        for (i in lines.indices) {
            if (findKeywordRange(lines[i], listOf("bank")) != null && i + 1 < lines.size) {
                val digits = Regex("""\d{6,20}""").find(lines[i + 1])?.value
                if (digits != null) return digits
            }
        }
        return ""
    }

    /** Finds all masked account numbers (e.g. XXXX1234) in order of appearance. */
    private fun extractMaskedAccounts(text: String): List<String> =
        Regex("""[Xx*]{4,}\s*(\d{4})""").findAll(text).map { "XXXX${it.groupValues[1]}" }.toList()
}

