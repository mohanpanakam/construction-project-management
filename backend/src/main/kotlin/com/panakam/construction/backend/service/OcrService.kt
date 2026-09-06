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
            val image = ImageIO.read(ByteArrayInputStream(downscaleIfNeeded(imageBytes))) ?: return ""
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

    /**
     * Downscales an image to at most [maxDimension] px on its longest side (preserving
     * aspect ratio), re-encoding as JPEG. Large raw camera photos — Aadhaar card photos
     * in particular are commonly 3000-4000px / several MB — make Tesseract/PaddleOCR
     * take much longer to process on a resource-constrained server; that processing time
     * could intermittently exceed the Android client's read timeout, making OCR appear to
     * "just fail sometimes" for no obvious reason on larger images while smaller ones
     * worked fine. Downscaling also often IMPROVES Tesseract accuracy (very large images
     * can hurt it) and keeps well under AWS Textract's 10MB image-bytes limit. Returns the
     * ORIGINAL bytes unchanged if decoding fails or the image is already small enough —
     * never throws, so a downscale failure can never break OCR entirely.
     */
    private fun downscaleIfNeeded(imageBytes: ByteArray, maxDimension: Int = 1800): ByteArray {
        return try {
            val original = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return imageBytes
            val w = original.width
            val h = original.height
            val longest = maxOf(w, h)
            if (longest <= maxDimension) return imageBytes
            val scale = maxDimension.toDouble() / longest
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            val scaled = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original, 0, 0, newW, newH, null)
                dispose()
            }
            val out = java.io.ByteArrayOutputStream()
            ImageIO.write(scaled, "jpg", out)
            val result = out.toByteArray()
            log.info("Downscaled OCR image from {}x{} ({} bytes) to {}x{} ({} bytes)",
                w, h, imageBytes.size, newW, newH, result.size)
            result
        } catch (e: Exception) {
            log.warn("Image downscale failed ({}): {} — proceeding with original bytes",
                e.javaClass.simpleName, e.message)
            imageBytes
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
                    bytes = downscaleIfNeeded(imageBytes)
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
            val downscaled = downscaleIfNeeded(imageBytes)
            val response: HttpResponse = client.submitFormWithBinaryData(
                url = "$baseUrl/ocr",
                formData = formData {
                    append("file", downscaled, Headers.build {
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
        val utrNumber: String          = "",
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

        var beneficiaryBank = extractField(lines, listOf("to bank", "beneficiary bank", "credit bank"))
            .ifBlank { extractField(lines, listOf("bank")) }
        var payerBank = extractField(lines, listOf("from bank", "remitter bank", "sending bank", "debit bank"))
            .ifBlank { if (beneficiaryBank.isBlank()) extractField(lines, listOf("bank")) else "" }

        // "From"/"To" section boundaries — used below as a last-resort fallback for bank
        // names that [extractField]'s word-boundary "bank" search can't find because OCR
        // glued the words together with no spaces at all (e.g. "CANARABANK",
        // "UNIONBANKOFINDIA" — very common on bank-app screenshots where visually-adjacent
        // text elements get merged into one OCR "line").
        val fromIdx = lines.indexOfFirst { findKeywordRange(it, listOf("from", "remitter", "sender")) != null }
        val toIdx   = lines.indexOfFirst { findKeywordRange(it, listOf("to", "beneficiary", "recipient", "payee", "paid to")) != null }
        if (payerBank.isBlank()) {
            val range = if (fromIdx != -1) fromIdx until (if (toIdx > fromIdx) toIdx else lines.size) else lines.indices
            payerBank = range.firstNotNullOfOrNull { idx -> findKnownBankInLine(lines[idx]) } ?: ""
        }
        if (beneficiaryBank.isBlank()) {
            val range = if (toIdx != -1) toIdx until lines.size else IntRange.EMPTY
            beneficiaryBank = range.firstNotNullOfOrNull { idx -> findKnownBankInLine(lines[idx]) } ?: ""
        }

        // UTR (Unique Transaction Reference) resolution order, per auditor needs (the UTR
        // is what's actually looked up against the recipient bank's statement):
        //  1) An explicitly-labeled "UTR" value (extractUtr) — most authoritative.
        //  2) An explicitly-labeled "Reference"/"Ref No" value (extractReferenceNumber) —
        //     many receipts call it "Reference Number" instead of "UTR".
        //  3) Whatever generic reference extractTransactionId already found (which itself
        //     checks UTR/transaction/reference/receipt/cheque labels, in that priority).
        // transactionId, symmetrically, falls back to the resolved UTR/reference value
        // when extractTransactionId itself found nothing — so the two fields are never
        // inconsistently one-blank-one-filled when there's really just one number on the
        // receipt serving both purposes.
        val genericTxnId = extractTransactionId(lines)
        val utrNumber = extractUtr(lines)
            .ifBlank { extractReferenceNumber(lines) }
            .ifBlank { genericTxnId }
        val transactionId = genericTxnId.ifBlank { utrNumber }

        return ParsedPayment(
            amount             = extractAmount(full),
            paymentDate        = extractDate(full),
            transactionId      = transactionId,
            utrNumber          = utrNumber,
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

    /**
     * Extracts the bank-issued UTR (Unique Transaction Reference) specifically — distinct
     * from the generic reference captured by [extractTransactionId]. Auditors need the
     * actual UTR to confirm a transfer directly against the recipient bank's statement, so
     * conflating it with a receipt-app-specific "Transaction ID" (a DIFFERENT number) isn't
     * good enough.
     *
     * Handles the common bank/UPI app layout where "Transaction ID" and "UTR" appear as
     * two consecutive LABEL-ONLY lines, immediately followed by two consecutive VALUE-ONLY
     * lines in the SAME order, e.g.:
     *   "Transaction ID"
     *   "UTR"
     *   "421710722590"        <- this is the Transaction ID's value
     *   "CNRBR52026083095304" <- this is the actual UTR
     * Naively taking "whatever value follows a UTR-ish line" would grab the Transaction
     * ID's value instead, since both labels sit right next to each other with no value in
     * between. [extractLabeledValue] pairs each label in the run positionally with the
     * value at the same relative position in the following run of value-only lines.
     */
    private fun extractUtr(lines: List<String>): String =
        extractLabeledValue(lines, Regex("""\butr\s*(?:number|no\.?)?\b""", RegexOption.IGNORE_CASE))

    /**
     * Extracts an explicitly-labeled "Reference Number" / "Ref No" value — the fallback
     * used for [utrNumber][ParsedPayment.utrNumber] when a receipt doesn't literally say
     * "UTR" but does call out a reference number, which serves the same bank-lookup
     * purpose. Same label/value positional-pairing logic as [extractUtr].
     */
    private fun extractReferenceNumber(lines: List<String>): String =
        extractLabeledValue(lines, Regex("""\b(?:reference|ref)\s*(?:number|no\.?|id)?\b""", RegexOption.IGNORE_CASE))

    /**
     * Shared by [extractUtr] and [extractReferenceNumber]: finds [labelPattern] as a
     * whole-word match on some line, then either reads the value on the SAME line (after
     * a colon/space) or — for the common bank/UPI-app layout where several labels sit on
     * consecutive lines of their own, followed by a matching run of value-only lines —
     * pairs the label with the value at the same relative position within its run.
     */
    private fun extractLabeledValue(lines: List<String>, labelPattern: Regex): String {
        for (i in lines.indices) {
            val line = lines[i]
            val m = labelPattern.find(line) ?: continue

            // Same-line "LABEL: XXXX" / "LABEL XXXX"
            val after = line.substring(m.range.last + 1)
            val inline = Regex("""[:\s#-]*([A-Za-z0-9]{8,30})""").find(after)?.groupValues?.get(1)?.trim()
            if (inline != null && inline.any { it.isDigit() }) return inline

            // Label-only line — find the run of consecutive label-only lines this label
            // belongs to, then the matching run of value-only lines right after it, and
            // pick the value at the SAME relative position within its own label run.
            var labelStart = i
            while (labelStart > 0 && isLabelOnlyLine(lines[labelStart - 1])) labelStart--
            var labelEnd = i
            while (labelEnd + 1 < lines.size && isLabelOnlyLine(lines[labelEnd + 1])) labelEnd++
            val positionInRun = i - labelStart

            val values = mutableListOf<String>()
            var j = labelEnd + 1
            while (j < lines.size && values.size <= positionInRun && isValueOnlyLine(lines[j])) {
                values += lines[j].trim(); j++
            }
            values.getOrNull(positionInRun)?.let { return it }
        }
        return ""
    }

    /** A short, all-letters (no digits) line — plausible label text, e.g. "UTR" or
     *  "Transaction ID". Used by [extractLabeledValue] to find runs of consecutive labels. */
    private fun isLabelOnlyLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotBlank() && t.length in 2..30 && t.none { it.isDigit() } &&
            t.split(" ").filter { it.isNotBlank() }.size <= 3
    }

    /** A bare alphanumeric value with no internal whitespace and at least one digit —
     *  plausible reference/UTR value. Used by [extractLabeledValue] to find runs of values. */
    private fun isValueOnlyLine(line: String): Boolean {
        val t = line.trim()
        return t.length in 8..30 && t.any { it.isDigit() } && t.none { it.isWhitespace() }
    }

    // ── Aadhaar (KYC) parser ──────────────────────────────────────────────────

    data class ParsedAadhaar(
        val name: String          = "",
        val aadharNumber: String  = "",
        val address: String       = "",
        val dob: String           = "",
        val gender: String        = ""
    )

    /**
     * Parses name / Aadhaar number / address out of OCR'd text from an Aadhaar card
     * (front or back side, or the address-only back-page slip). Heuristic, per-line —
     * same rationale as [parsePaymentText]: never search the whole document joined
     * into one line, since common English words in unrelated lines would otherwise
     * false-positive match nearby unrelated numbers.
     */
    fun parseAadhaar(text: String): ParsedAadhaar {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val aadharNumber = extractAadhaarNumber(lines)
        val dob          = extractAadhaarDob(lines)
        val gender       = when {
            Regex("""\bfemale\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Female"
            Regex("""\bmale\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)   -> "Male"
            else -> ""
        }
        val name    = extractAadhaarName(lines)
        val address = extractAadhaarAddress(lines)

        return ParsedAadhaar(
            name = name, aadharNumber = aadharNumber, address = address, dob = dob, gender = gender
        )
    }

    /** Aadhaar numbers are always 12 digits, conventionally printed/OCR'd as 4-4-4
     *  space-separated groups (e.g. "1234 5678 9012"), sometimes with no spaces. */
    private fun extractAadhaarNumber(lines: List<String>): String {
        for (line in lines) {
            val m = Regex("""\b(\d{4}\s?\d{4}\s?\d{4})\b""").find(line) ?: continue
            val digits = m.groupValues[1].replace(" ", "")
            if (digits.length == 12) return "${digits.substring(0,4)} ${digits.substring(4,8)} ${digits.substring(8,12)}"
        }
        return ""
    }

    private fun extractAadhaarDob(lines: List<String>): String {
        for (line in lines) {
            if (!Regex("""\b(dob|birth|year of birth|yob)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
            val m = Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{4})""").find(line)
                ?: Regex("""\b(\d{4})\b""").find(line)
            if (m != null) return DateUtils.normalizeDateTime(m.groupValues[1])
        }
        return ""
    }

    /**
     * Name heuristic: on a standard Aadhaar card, the cardholder's name is the line
     * immediately BEFORE the "DOB"/"Date of Birth"/"Year of Birth" line, and is NOT
     * one of the standard boilerplate lines ("Government of India", "Unique
     * Identification Authority of India", etc). Falls back to the first all-letters
     * line (2+ words, title/upper case) that isn't boilerplate.
     */
    private fun extractAadhaarName(lines: List<String>): String {
        val boilerplate = listOf(
            "government of india", "unique identification authority", "uidai",
            "male", "female", "dob", "year of birth", "address", "download date",
            "aadhaar", "आधार"
        )
        fun isBoilerplate(l: String) = boilerplate.any { l.lowercase().contains(it) }

        for (i in lines.indices) {
            if (Regex("""\b(dob|date of birth|year of birth)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lines[i])) {
                for (back in 1..2) {
                    val candidate = lines.getOrNull(i - back) ?: continue
                    if (!isBoilerplate(candidate) && candidate.any { it.isLetter() } &&
                        candidate.count { it.isDigit() } == 0 && candidate.trim().split(" ").size in 1..5) {
                        return candidate.trim()
                    }
                }
            }
        }
        // Fallback: first plausible name-shaped line
        return lines.firstOrNull { l ->
            !isBoilerplate(l) && l.count { it.isDigit() } == 0 &&
            l.trim().split(" ").filter { it.isNotBlank() }.size in 2..5 &&
            l.all { it.isLetter() || it.isWhitespace() || it == '.' }
        }?.trim() ?: ""
    }

    /**
     * Address heuristic: text after an explicit "Address:" label (may span multiple
     * following lines up to the next line containing a 6-digit PIN code, which is
     * included as the end of the address), joined with ", ".
     */
    private fun extractAadhaarAddress(lines: List<String>): String {
        val startIdx = lines.indexOfFirst { it.contains("address", ignoreCase = true) }
        if (startIdx == -1) {
            // No explicit label — fall back to any line containing a 6-digit PIN code,
            // plus the 2 lines before it (common layout on the back-page address slip).
            val pinIdx = lines.indexOfFirst { Regex("""\b\d{6}\b""").containsMatchIn(it) }
            if (pinIdx == -1) return ""
            val start = (pinIdx - 3).coerceAtLeast(0)
            return lines.subList(start, pinIdx + 1).joinToString(", ")
        }
        val afterLabel = lines[startIdx].substringAfter(":", "").trim()
        val collected = mutableListOf<String>()
        if (afterLabel.isNotBlank()) collected += afterLabel
        var i = startIdx + 1
        while (i < lines.size && collected.size < 8) {
            val line = lines[i]
            collected += line
            if (Regex("""\b\d{6}\b""").containsMatchIn(line)) break
            i++
        }
        return collected.joinToString(", ")
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
            // Currency-prefixed amount. The "Amount" label alternative allows an optional
            // parenthetical unit right after it with NO space required — e.g. bank e-receipts
            // commonly print "Transaction Amount(Rs.) 1000000.0" with the "(Rs.)" glued
            // directly onto "Amount" (no space/colon before the "("). Previously this
            // alternative was just "Amount[:\s]+", which REQUIRED whitespace/colon
            // immediately after "Amount" — since "(" followed it instead, the whole
            // alternative failed to match and fell through to the plain-decimal patterns
            // below, which then ALSO failed (see next comment) leaving amount blank.
            Regex("""(?:₹|~|Rs\.?|(?<![A-Za-z0-9])R(?=\d)|INR|Amount\s*(?:\([^)]{0,20}\))?[:\s]*)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
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
            // don't accidentally swallow account/reference numbers. Decimal digits allowed to
            // be 1 or 2 (some e-receipts print a single trailing zero, e.g. "50,000.0").
            Regex("""\b(\d{1,3}(?:,\d{3})+\.\d{1,2})\b"""),
            Regex("""([\d,]{4,}(?:\.\d{1,2})?)\s*(?:/-|only)""", RegexOption.IGNORE_CASE),
            // Last resort: a bare no-comma decimal amount with no currency prefix/symbol at
            // all (e.g. "200000.00" on its own line) — needs at least 3 digits before the
            // decimal point to avoid swallowing small numbers/percentages elsewhere. Decimal
            // part allows 1 OR 2 digits: some bank e-receipt PDFs print amounts with only a
            // single trailing decimal digit (e.g. "1000000.0" instead of "1000000.00") —
            // requiring exactly 2 digits here silently rejected those and left amount blank.
            Regex("""\b(\d{3,}\.\d{1,2})\b""")
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
            Regex("""\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s*\d{1,2},?\s*\d{4})\b""", RegexOption.IGNORE_CASE),
            // DD/MM/YY or DD-MM-YY — 2-digit year, last resort (tried only after every
            // 4-digit-year pattern above has failed). Common on bank/UPI app "Date&Time:"
            // fields, e.g. "Date&Time:30-08-26,03:40PM" (RTGS success screen).
            Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2})\b(?!\d)""")
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

    /** Words that show up on their own line between a "From"/"To" label and the actual
     *  name value on bank/UPI app screenshots (account type or transaction type) — must be
     *  skipped over rather than mistaken for the name itself. */
    private val FIELD_FILLER_WORDS = setOf(
        "savings", "current", "account", "a/c", "ac", "rtgs", "neft", "imps", "upi", "cash", "bank"
    )

    private fun extractField(lines: List<String>, keywords: List<String>): String {
        for (i in lines.indices) {
            val line  = lines[i]
            // Whole-word match only (via [findKeywordRange]) — plain `contains` would let
            // a short keyword like "to" false-positive match inside unrelated words such
            // as "Total"/"Auto"/"Photo", especially now that a bare label-only line (no
            // colon) is also treated as a match below.
            val range = findKeywordRange(line, keywords) ?: continue
            val kw    = line.substring(range).lowercase()
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
            } else {
                // No colon at all — a label-only line (e.g. "From," / "To" on its own
                // line, common on bank/UPI app "success" screenshots). Only treat this as
                // a bare label — NOT a self-contained value that merely happens to contain
                // the keyword as one of its words, e.g. "Canara Bank" when searching for
                // "bank" — when the line, once punctuation is stripped, IS EXACTLY the
                // keyword and nothing else. The value is then on a SUBSEQUENT line,
                // possibly after an account-type/transaction-type filler word.
                val strippedLine = line.lowercase().trim().trimEnd(',', ':', '.').trim()
                if (strippedLine == kw) {
                    var j = i + 1
                    while (j < lines.size &&
                        lines[j].trim().trimEnd(',', ':', '.').lowercase() in FIELD_FILLER_WORDS
                    ) j++
                    if (j < lines.size) {
                        val candidate = lines[j].trim()
                        if (candidate.isNotBlank()) return candidate
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

    /**
     * Finds all masked account numbers in order of appearance, in either common layout:
     *  - mask-then-digits (reveals the LAST 4), e.g. "XXXX1234" / "XXXXXXXXXXX0129"
     *  - digits-then-mask (reveals the FIRST few), e.g. "6800XXXXXXXXX" — seen on some
     *    bank-app "From" account displays.
     * Order matters: callers pick element [0] as the payer's account and [1] as the
     * beneficiary's, assuming the payer's masked account is shown before the
     * beneficiary's in the receipt text (true for the "From ... To ..." layout).
     */
    private fun extractMaskedAccounts(text: String): List<String> =
        Regex("""(?:[Xx*]{4,}\s*\d{4})|(?:\d{2,6}[Xx*]{4,})""").findAll(text).map { m ->
            val v = m.value
            if (v.first().isDigit()) {
                v.replace(Regex("""\s+"""), "").uppercase()
            } else {
                val digits = Regex("""\d{4}""").find(v)?.value ?: ""
                "XXXX$digits"
            }
        }.toList()

    /** Known Indian bank names, used only as a last-resort fallback when OCR has glued
     *  words together with no spaces at all (e.g. "CANARABANK", "UNIONBANKOFINDIA") so
     *  [extractField]'s whole-word "bank" search can never match a substring buried
     *  inside such a token — this instead does plain substring containment against a
     *  fixed, spaces-stripped list. */
    private val KNOWN_BANKS = listOf(
        "STATE BANK OF INDIA", "PUNJAB NATIONAL BANK", "BANK OF BARODA", "BANK OF INDIA",
        "CANARA BANK", "UNION BANK OF INDIA", "INDIAN BANK", "CENTRAL BANK OF INDIA",
        "UCO BANK", "INDIAN OVERSEAS BANK", "BANK OF MAHARASHTRA", "PUNJAB AND SIND BANK",
        "HDFC BANK", "ICICI BANK", "AXIS BANK", "KOTAK MAHINDRA BANK", "YES BANK",
        "IDBI BANK", "IDFC FIRST BANK", "INDUSIND BANK", "FEDERAL BANK", "SOUTH INDIAN BANK",
        "KARUR VYSYA BANK", "CITY UNION BANK", "RBL BANK", "BANDHAN BANK",
        "AU SMALL FINANCE BANK"
    )

    private fun findKnownBankInLine(line: String): String? {
        val compact = line.uppercase().replace(Regex("""[^A-Z]"""), "")
        // Pick the LONGEST matching name, not the first in the list — otherwise a more
        // specific bank whose name is a superset of a shorter one (e.g. "UNION BANK OF
        // INDIA" vs. plain "BANK OF INDIA") would incorrectly match the shorter entry
        // first, since both are substrings of the same OCR'd token "UNIONBANKOFINDIA".
        return KNOWN_BANKS.filter { compact.contains(it.replace(" ", "")) }
            .maxByOrNull { it.length }
    }
}

