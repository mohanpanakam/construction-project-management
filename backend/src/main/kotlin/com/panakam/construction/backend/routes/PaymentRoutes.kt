package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.sdk.kotlin.services.textract.TextractClient
import aws.smithy.kotlin.runtime.content.toByteArray
import com.panakam.construction.backend.db.CustomerPayments
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.UnitCollections
import com.panakam.construction.backend.service.AuditService
import com.panakam.construction.backend.service.DateUtils
import com.panakam.construction.backend.service.NotificationService
import com.panakam.construction.backend.service.OcrService
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val paymentLog = LoggerFactory.getLogger("PaymentRoutes")

fun Route.paymentRoutes(
    s3Client: S3Client,
    s3PresignClient: S3Client,
    textractClient: TextractClient?,
    ocrProvider: String,
    ocrHttpClient: HttpClient,
    paddleOcrUrl: String
) {
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"

    route("/payments") {

        // ── GET /payments/customer/{customerId}  ──────────────────────────────
        get("/customer/{customerId}") {
            val cid = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val list = dbQuery {
                CustomerPayments
                    .join(Units, JoinType.LEFT, onColumn = CustomerPayments.unitId, otherColumn = Units.unitId)
                    .join(Customers, JoinType.LEFT, onColumn = CustomerPayments.customerId, otherColumn = Customers.customerId)
                    .selectAll()
                    .where { CustomerPayments.customerId eq cid }
                    .orderBy(CustomerPayments.createdAt, SortOrder.DESC)
                    .map { it.toPaymentMapEnriched() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /payments/by-phone/{phone}  (all payments across all units for a customer phone) ──
        get("/by-phone/{phone}") {
            val phone = java.net.URLDecoder.decode(
                call.parameters["phone"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing phone")),
                "UTF-8"
            )
            val list = dbQuery {
                CustomerPayments
                    .join(Customers, JoinType.INNER, onColumn = CustomerPayments.customerId, otherColumn = Customers.customerId)
                    .join(Units, JoinType.LEFT, onColumn = CustomerPayments.unitId, otherColumn = Units.unitId)
                    .selectAll()
                    .where { Customers.phone eq phone }
                    .orderBy(CustomerPayments.createdAt, SortOrder.DESC)
                    .map { it.toPaymentMapEnriched() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── POST /payments  ───────────────────────────────────────────────────
        post {
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val customerId = json.str("customerId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId")) }
            val amount        = json.str("amount").toDoubleOrNull() ?: 0.0
            // Normalize to "yyyy-MM-dd[ time]" regardless of what format the caller sent —
            // see DateUtils for why (mixed "/" vs "-" and day-first vs year-first dates
            // were being stored verbatim, breaking sorting AND the duplicate check below,
            // which compares this string for equality).
            val paymentDate   = DateUtils.normalizeDateTime(json.str("paymentDate"))
            val transactionId = json.str("transactionId")

            findDuplicatePayment(customerId, amount, paymentDate, transactionId)?.let {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to duplicateMessage(transactionId)))
            }

            val paymentId  = UUID.randomUUID().toString()

            dbQuery {
                CustomerPayments.insert {
                    it[CustomerPayments.paymentId]          = paymentId
                    it[CustomerPayments.customerId]         = customerId
                    it[CustomerPayments.projectId]          = json.str("projectId")
                    it[CustomerPayments.unitId]             = json.str("unitId")
                    it[CustomerPayments.amount]             = amount
                    it[CustomerPayments.paymentDate]        = paymentDate
                    it[CustomerPayments.transactionId]      = transactionId
                    it[CustomerPayments.utrNumber]          = json.str("utrNumber")
                    it[CustomerPayments.transactionType]    = json.str("transactionType")
                    it[CustomerPayments.payerName]          = json.str("payerName")
                    it[CustomerPayments.payerBank]          = json.str("payerBank")
                    it[CustomerPayments.payerAccount]       = json.str("payerAccount")
                    it[CustomerPayments.beneficiaryName]    = json.str("beneficiaryName")
                    it[CustomerPayments.beneficiaryBank]    = json.str("beneficiaryBank")
                    it[CustomerPayments.beneficiaryAccount] = json.str("beneficiaryAccount")
                    it[CustomerPayments.receiptS3Key]       = json.str("receiptS3Key")
                    it[CustomerPayments.receiptFileId]      = json.str("receiptFileId")
                    it[CustomerPayments.notes]              = json.str("notes")
                    it[CustomerPayments.verified]           = json.str("verified") == "true"
                    it[CustomerPayments.auditStatus]        = "PENDING"
                    it[CustomerPayments.chequeNumber]       = json.str("chequeNumber")
                    it[CustomerPayments.chequeDate]         = DateUtils.normalizeDateTime(json.str("chequeDate"))
                    it[CustomerPayments.createdAt]          = System.currentTimeMillis()
                    it[CustomerPayments.createdBy]          = json.str("createdBy")
                }
            }
            AuditService.log("customer_payments", paymentId, "CREATE",
                changedBy = json.str("createdBy"), newValues = json.toString())
            notifyPaymentCreatedSafely(
                paymentId = paymentId, customerId = customerId,
                projectId = json.str("projectId"), unitId = json.str("unitId"),
                amount = amount, createdByUserId = json.str("createdBy")
            )
            call.respond(HttpStatusCode.Created, mapOf("message" to "Payment recorded", "paymentId" to paymentId))
        }

        // ── POST /payments/receipt/upload-url  ────────────────────────────────
        post("/receipt/upload-url") {
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val customerId = json.str("customerId")
            val fileName   = json.str("fileName").ifBlank { "receipt_${System.currentTimeMillis()}" }
            val contentType = json.str("contentType", "application/octet-stream")
            val fileId     = UUID.randomUUID().toString()
            val s3Key      = "customers/$customerId/receipts/$fileId-$fileName"

            val presigned = s3PresignClient.presignPutObject(PutObjectRequest {
                bucket = bucketName; key = s3Key
            }, 15.minutes)

            call.respond(HttpStatusCode.OK, mapOf(
                "fileId"    to fileId,
                "uploadUrl" to presigned.url.toString(),
                "s3Key"     to s3Key
            ))
        }

        // ── POST /payments/extract  ───────────────────────────────────────────
        // Extracts payment details from a PDF stored in MinIO OR from plain text.
        // Body: { "s3Key": "...", "rawText": "..." }  (s3Key OR rawText)
        post("/extract") {
            val json    = Json.parseToJsonElement(call.receiveText()).jsonObject
            val rawText = json.str("rawText")
            val s3Key   = json.str("s3Key")
            val declaredType = json.str("declaredType", "AUTO")

            val warnings = mutableListOf<String>()
            val source = when {
                rawText.isNotBlank() -> "TEXT"
                s3Key.endsWith(".pdf", ignoreCase = true) -> "PDF"
                s3Key.endsWith(".png", ignoreCase = true) ||
                    s3Key.endsWith(".jpg", ignoreCase = true) ||
                    s3Key.endsWith(".jpeg", ignoreCase = true) -> "IMAGE"
                s3Key.isNotBlank() -> "FILE"
                else -> "UNKNOWN"
            }
            val textToparse = when {
                rawText.isNotBlank() -> rawText
                s3Key.isNotBlank()   -> {
                    try {
                        val bytes = s3Client.getObject(GetObjectRequest {
                            bucket = bucketName; key = s3Key
                        }) { resp -> resp.body?.toByteArray() ?: ByteArray(0) }
                        if (bytes.isEmpty()) {
                            warnings += "Uploaded file is empty"
                            ""
                        } else if (source == "PDF") {
                            OcrService.extractTextFromPdf(bytes.inputStream())
                        } else if (source == "IMAGE") {
                            // Prefer AWS Textract or PaddleOCR only when explicitly enabled;
                            // otherwise fall back to free, local Tesseract OCR (no cloud cost,
                            // no extra container).
                            val text = when {
                                ocrProvider.equals("TEXTRACT", ignoreCase = true) && textractClient != null ->
                                    OcrService.extractTextFromImage(textractClient, bytes)
                                ocrProvider.equals("PADDLE", ignoreCase = true) ->
                                    OcrService.extractTextFromImagePaddle(ocrHttpClient, paddleOcrUrl, bytes)
                                else ->
                                    OcrService.extractTextFromImageLocal(bytes)
                            }
                            if (text.isBlank()) {
                                warnings += "Image text could not be extracted confidently; please review and edit fields"
                            }
                            text
                        } else {
                            warnings += "Unsupported file type for extraction; please review and edit fields"
                            ""
                        }
                    } catch (e: Exception) {
                        paymentLog.warn("OCR-EXTRACT failed to fetch/process s3Key={}: {}: {}", s3Key, e.javaClass.simpleName, e.message)
                        ""
                    }
                }
                else -> ""
            }


            val parsed = if (textToparse.isNotBlank()) OcrService.parsePaymentText(textToparse) else OcrService.ParsedPayment()
            val normalizedType = normalizeTransactionType(parsed.transactionType, declaredType)

            // Diagnostic logging: without this, when OCR mis-reads a receipt (wrong/missing
            // fields) there is NO way to tell from logs whether (a) Tesseract extracted
            // garbage/empty text, or (b) the regex parser in OcrService.parsePaymentText
            // just didn't recognize a valid-looking text. Log both raw + parsed so
            // `docker logs construction-backend | grep OCR-EXTRACT` shows the full picture
            // for whichever receipt a user just reported as "details not filling properly".
            paymentLog.info(
                "OCR-EXTRACT source={} s3Key={} rawTextLen={} rawTextPreview={} parsed={}",
                source, s3Key, textToparse.length,
                textToparse.take(500).replace("\n", " \\n "),
                parsed
            )
            val missingFields = listOfNotNull(
                "amount".takeIf { parsed.amount.isBlank() },
                "paymentDate".takeIf { parsed.paymentDate.isBlank() },
                "transactionType".takeIf { normalizedType.isBlank() || normalizedType == "Unknown" }
            )
            if (source == "IMAGE" && rawText.isBlank()) {
                warnings += "Customer review is required for image receipts"
            }
            val confidence = estimateConfidence(parsed, source, missingFields.size)
            val needsReview = missingFields.isNotEmpty() || confidence < 0.80

            val responseJson = buildJsonObject {
                put("message", if (textToparse.isBlank()) "No extractable text; manual confirmation required" else "Parsed successfully")
                put("draftId", UUID.randomUUID().toString())
                put("documentType", inferDocumentType(normalizedType, textToparse, declaredType))
                put("source", source)
                put("confidence", confidence.toString())
                put("needsReview", needsReview.toString())
                putJsonArray("missingFields") { missingFields.forEach { add(it) } }
                putJsonArray("warnings") { warnings.forEach { add(it) } }
                putJsonObject("parsed") {
                    put("amount", parsed.amount)
                    put("paymentDate", parsed.paymentDate)
                    put("transactionId", parsed.transactionId)
                    put("utrNumber", parsed.utrNumber)
                    put("transactionType", normalizedType)
                    put("payerName", parsed.payerName)
                    put("payerBank", parsed.payerBank)
                    put("payerAccount", parsed.payerAccount)
                    put("beneficiaryName", parsed.beneficiaryName)
                    put("beneficiaryBank", parsed.beneficiaryBank)
                    put("beneficiaryAccount", parsed.beneficiaryAccount)
                    put("chequeNumber", parsed.chequeNumber)
                    put("chequeDate", parsed.chequeDate)
                }
            }
            call.respond(HttpStatusCode.OK, responseJson)
        }

        // ── POST /payments/confirm  ───────────────────────────────────────────
        // Final customer-confirmed payload save (always starts as unaudited/PENDING).
        post("/confirm") {
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val fields = json.obj("fields")
            val customerId = json.str("customerId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            }
            val amount = fields.str("amount").toDoubleOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid amount"))
            val paymentDate = DateUtils.normalizeDateTime(fields.str("paymentDate").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing paymentDate"))
            })
            val txType = normalizeTransactionType(fields.str("transactionType"), json.str("declaredType", "AUTO")).ifBlank { "Unknown" }
            val transactionId = fields.str("transactionId")

            findDuplicatePayment(customerId, amount, paymentDate, transactionId)?.let {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to duplicateMessage(transactionId)))
            }

            val paymentId = UUID.randomUUID().toString()
            dbQuery {
                CustomerPayments.insert {
                    it[CustomerPayments.paymentId]          = paymentId
                    it[CustomerPayments.customerId]         = customerId
                    it[CustomerPayments.projectId]          = json.str("projectId")
                    it[CustomerPayments.unitId]             = json.str("unitId")
                    it[CustomerPayments.amount]             = amount
                    it[CustomerPayments.paymentDate]        = paymentDate
                    it[CustomerPayments.transactionId]      = transactionId
                    it[CustomerPayments.utrNumber]          = fields.str("utrNumber")
                    it[CustomerPayments.transactionType]    = txType
                    it[CustomerPayments.payerName]          = fields.str("payerName")
                    it[CustomerPayments.payerBank]          = fields.str("payerBank")
                    it[CustomerPayments.payerAccount]       = fields.str("payerAccount")
                    it[CustomerPayments.beneficiaryName]    = fields.str("beneficiaryName")
                    it[CustomerPayments.beneficiaryBank]    = fields.str("beneficiaryBank")
                    it[CustomerPayments.beneficiaryAccount] = fields.str("beneficiaryAccount")
                    it[CustomerPayments.chequeNumber]       = fields.str("chequeNumber")
                    it[CustomerPayments.chequeDate]         = DateUtils.normalizeDateTime(fields.str("chequeDate"))
                    it[CustomerPayments.receiptS3Key]       = json.str("receiptS3Key")
                    it[CustomerPayments.receiptFileId]      = json.str("receiptFileId")
                    it[CustomerPayments.notes]              = fields.str("notes")
                    it[CustomerPayments.verified]           = false
                    it[CustomerPayments.auditStatus]        = "PENDING"
                    it[CustomerPayments.createdAt]          = System.currentTimeMillis()
                    it[CustomerPayments.createdBy]          = json.str("confirmedBy")
                }
            }
            AuditService.log("customer_payments", paymentId, "CONFIRM",
                changedBy = json.str("confirmedBy"), newValues = json.toString())
            notifyPaymentCreatedSafely(
                paymentId = paymentId, customerId = customerId,
                projectId = json.str("projectId"), unitId = json.str("unitId"),
                amount = amount, createdByUserId = json.str("confirmedBy")
            )
            call.respond(HttpStatusCode.Created, mapOf(
                "message" to "Payment recorded",
                "paymentId" to paymentId,
                "auditStatus" to "PENDING"
            ))
        }

        // ── PUT /payments/{paymentId}  ────────────────────────────────────────
        put("/{paymentId}") {
            val id   = call.parameters["paymentId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing paymentId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val old  = dbQuery {
                CustomerPayments.selectAll().where { CustomerPayments.paymentId eq id }.singleOrNull()?.toPaymentMap()
            }
            dbQuery {
                CustomerPayments.update({ CustomerPayments.paymentId eq id }) {
                    json.str("amount").toDoubleOrNull()?.let              { v -> it[CustomerPayments.amount]             = v }
                    json.str("paymentDate").takeIf { it.isNotBlank() }?.let { v -> it[CustomerPayments.paymentDate]      = DateUtils.normalizeDateTime(v) }
                    json.str("transactionId").let                          { v -> it[CustomerPayments.transactionId]      = v }
                    json.str("utrNumber").let                              { v -> it[CustomerPayments.utrNumber]          = v }
                    json.str("transactionType").let                        { v -> it[CustomerPayments.transactionType]    = v }
                    json.str("payerName").let                              { v -> it[CustomerPayments.payerName]          = v }
                    json.str("payerBank").let                              { v -> it[CustomerPayments.payerBank]          = v }
                    json.str("payerAccount").let                           { v -> it[CustomerPayments.payerAccount]       = v }
                    json.str("beneficiaryName").let                        { v -> it[CustomerPayments.beneficiaryName]    = v }
                    json.str("beneficiaryBank").let                        { v -> it[CustomerPayments.beneficiaryBank]    = v }
                    json.str("beneficiaryAccount").let                     { v -> it[CustomerPayments.beneficiaryAccount] = v }
                    json.str("receiptS3Key").takeIf { it.isNotBlank() }?.let { v -> it[CustomerPayments.receiptS3Key]   = v }
                    json.str("receiptFileId").takeIf { it.isNotBlank() }?.let { v -> it[CustomerPayments.receiptFileId] = v }
                    json.str("notes").let                                  { v -> it[CustomerPayments.notes]             = v }
                    json.str("verified").takeIf { it.isNotBlank() }?.let  { v -> it[CustomerPayments.verified]          = v == "true" }
                }
            }
            AuditService.log("customer_payments", id, "UPDATE",
                changedBy = json.str("updatedBy"), oldValues = old.toString(), newValues = json.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Payment updated", "paymentId" to id))
        }

        // ── PUT /payments/{paymentId}/audit  (auditor/admin changes status) ────────
        put("/{paymentId}/audit") {
            val id   = call.parameters["paymentId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing paymentId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val newStatus = json.str("auditStatus").uppercase()
            if (newStatus !in listOf("AUDITED", "REJECTED", "PENDING"))
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "auditStatus must be AUDITED, REJECTED or PENDING"))

            // Fetch current payment BEFORE updating (need old status + amount + unitId)
            val old = dbQuery {
                CustomerPayments.selectAll().where { CustomerPayments.paymentId eq id }.singleOrNull()?.toPaymentMap()
            }
            val oldStatus     = old?.get("auditStatus")?.toString() ?: ""
            val paymentAmount = old?.get("amount")?.toString()?.toDoubleOrNull() ?: 0.0
            val unitId        = old?.get("unitId")?.toString() ?: ""
            val paymentDate   = old?.get("paymentDate")?.toString() ?: ""

            // Update audit status on the payment
            dbQuery {
                CustomerPayments.update({ CustomerPayments.paymentId eq id }) {
                    it[CustomerPayments.auditStatus]  = newStatus
                    it[CustomerPayments.auditedBy]    = json.str("auditedBy")
                    it[CustomerPayments.auditedAt]    = System.currentTimeMillis()
                    it[CustomerPayments.rejectReason] = json.str("rejectReason")
                    it[CustomerPayments.verified]     = newStatus == "AUDITED"
                }
            }

            // ── Reconcile UnitCollections ─────────────────────────────────────
            // Compute the net delta to apply to paidAmount:
            //  +amount when newly AUDITED
            //  -amount when un-audited (AUDITED → REJECTED or PENDING)
            //  no change when REJECTED → PENDING or PENDING → REJECTED
            val delta = when {
                newStatus == "AUDITED" && oldStatus != "AUDITED" -> paymentAmount   // credit
                oldStatus == "AUDITED" && newStatus != "AUDITED" -> -paymentAmount  // reverse
                else -> 0.0
            }
            reconcileUnitCollection(unitId, delta, paymentDate, json.str("auditedBy"))

            AuditService.log("customer_payments", id, "AUDIT",
                changedBy = json.str("auditedBy"), oldValues = old.toString(),
                newValues = "auditStatus=$newStatus reason=${json.str("rejectReason")}")

            if (newStatus != oldStatus) {
                val customerId = old?.get("customerId")?.toString() ?: ""
                val projectId  = old?.get("projectId")?.toString() ?: ""
                notifyPaymentAuditedSafely(
                    paymentId = id, customerId = customerId, projectId = projectId, unitId = unitId,
                    amount = paymentAmount, newStatus = newStatus,
                    auditedByUserId = json.str("auditedBy"), rejectReason = json.str("rejectReason")
                )
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Payment audit status updated", "auditStatus" to newStatus))
        }

        // ── GET /payments/all  (auditor: all payments across projects, optional ?status=PENDING) ──
        get("/all") {
            val statusFilter = call.request.queryParameters["status"]
            val projectFilter = call.request.queryParameters["projectId"]
            val list = dbQuery {
                var q = CustomerPayments
                    .join(Units, JoinType.LEFT, onColumn = CustomerPayments.unitId, otherColumn = Units.unitId)
                    .join(Customers, JoinType.LEFT, onColumn = CustomerPayments.customerId, otherColumn = Customers.customerId)
                    .selectAll()
                if (!statusFilter.isNullOrBlank())  q = q.andWhere { CustomerPayments.auditStatus eq statusFilter.uppercase() }
                if (!projectFilter.isNullOrBlank()) q = q.andWhere { CustomerPayments.projectId  eq projectFilter }
                q.orderBy(CustomerPayments.createdAt, SortOrder.DESC).map { it.toPaymentMapEnriched() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /payments/receipt/download-url?s3Key=...  (view attached receipt) ──
        get("/receipt/download-url") {
            val s3Key = call.request.queryParameters["s3Key"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing s3Key"))
            if (s3Key.isBlank())
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No receipt attached"))

            val presigned = s3PresignClient.presignGetObject(GetObjectRequest {
                bucket = bucketName; key = s3Key
            }, 30.minutes)

            call.respond(HttpStatusCode.OK, mapOf("downloadUrl" to presigned.url.toString()))
        }

                // ── DELETE /payments/{paymentId}  ─────────────────────────────────────
        delete("/{paymentId}") {
            val id  = call.parameters["paymentId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing paymentId"))
            val old = dbQuery {
                CustomerPayments.selectAll().where { CustomerPayments.paymentId eq id }.singleOrNull()?.toPaymentMap()
            }
            dbQuery { CustomerPayments.deleteWhere { CustomerPayments.paymentId eq id } }

            // If the deleted payment had already been AUDITED, it had been credited
            // into UnitCollections.paidAmount — reverse that credit now, otherwise the
            // unit would be left showing a paidAmount that no longer has any backing
            // payment record (a silent over-statement of how much the customer paid).
            if (old?.get("auditStatus")?.toString() == "AUDITED") {
                val unitId        = old["unitId"]?.toString() ?: ""
                val paymentAmount = old["amount"]?.toString()?.toDoubleOrNull() ?: 0.0
                reconcileUnitCollection(unitId, -paymentAmount, "", "")
            }

            AuditService.log("customer_payments", id, "DELETE", newValues = old.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Payment deleted"))
        }

        // ── POST /payments/admin/normalize-dates  ─────────────────────────────
        // One-time (idempotent, safe to re-run) backfill: re-normalizes paymentDate/
        // chequeDate on EVERY existing row through DateUtils, fixing rows that were
        // stored before normalization was added (mixed "/" vs "-", day-first vs
        // year-first, etc). New writes are already normalized at insert/update time
        // (see above) — this just cleans up historical data. Not exposed in the
        // Android app UI; trigger manually via curl after deploying this fix.
        post("/admin/normalize-dates") {
            var updated = 0
            var scanned = 0
            dbQuery {
                CustomerPayments.selectAll().forEach { row ->
                    scanned++
                    val id = row[CustomerPayments.paymentId]
                    val oldPaymentDate = row[CustomerPayments.paymentDate]
                    val oldChequeDate  = row[CustomerPayments.chequeDate]
                    val newPaymentDate = DateUtils.normalizeDateTime(oldPaymentDate)
                    val newChequeDate  = DateUtils.normalizeDateTime(oldChequeDate)
                    if (newPaymentDate != oldPaymentDate || newChequeDate != oldChequeDate) {
                        CustomerPayments.update({ CustomerPayments.paymentId eq id }) {
                            it[CustomerPayments.paymentDate] = newPaymentDate
                            it[CustomerPayments.chequeDate]  = newChequeDate
                        }
                        updated++
                    }
                }
            }
            AuditService.log("customer_payments", "ALL", "NORMALIZE_DATES",
                newValues = "scanned=$scanned updated=$updated")
            call.respond(HttpStatusCode.OK, mapOf("scanned" to scanned, "updated" to updated))
        }

        // ── POST /payments/admin/backfill-utr  ────────────────────────────────
        // One-time (idempotent, safe to re-run) backfill: re-downloads and re-OCRs the
        // receipt attached to EVERY existing payment that has one, extracts the bank UTR
        // specifically (see OcrService.extractUtr — added after utrNumber didn't exist
        // yet, so historical payments never had it populated), and fills in utrNumber
        // wherever it's currently blank. Never overwrites a UTR that's already set (e.g.
        // manually entered by a user) — only backfills genuinely missing values. Not
        // exposed in the Android app UI; trigger manually via curl after deploying.
        post("/admin/backfill-utr") {
            var scanned = 0
            var updated = 0
            var skippedNoReceipt = 0
            var skippedAlreadySet = 0
            var notFound = 0
            val rows = dbQuery {
                CustomerPayments.selectAll()
                    .where { CustomerPayments.receiptS3Key neq "" }
                    .map { row ->
                        Triple(row[CustomerPayments.paymentId], row[CustomerPayments.receiptS3Key], row[CustomerPayments.utrNumber])
                    }
            }
            for ((paymentId, s3Key, existingUtr) in rows) {
                scanned++
                if (s3Key.isBlank()) { skippedNoReceipt++; continue }
                if (existingUtr.isNotBlank()) { skippedAlreadySet++; continue }
                try {
                    val bytes = s3Client.getObject(GetObjectRequest {
                        bucket = bucketName; key = s3Key
                    }) { resp -> resp.body?.toByteArray() ?: ByteArray(0) }
                    if (bytes.isEmpty()) { notFound++; continue }
                    val text = when {
                        s3Key.endsWith(".pdf", ignoreCase = true) -> OcrService.extractTextFromPdf(bytes.inputStream())
                        s3Key.endsWith(".png", ignoreCase = true) || s3Key.endsWith(".jpg", ignoreCase = true) ||
                            s3Key.endsWith(".jpeg", ignoreCase = true) -> when {
                            ocrProvider.equals("TEXTRACT", ignoreCase = true) && textractClient != null ->
                                OcrService.extractTextFromImage(textractClient, bytes)
                            ocrProvider.equals("PADDLE", ignoreCase = true) ->
                                OcrService.extractTextFromImagePaddle(ocrHttpClient, paddleOcrUrl, bytes)
                            else -> OcrService.extractTextFromImageLocal(bytes)
                        }
                        else -> ""
                    }
                    if (text.isBlank()) { notFound++; continue }
                    val parsed = OcrService.parsePaymentText(text)
                    if (parsed.utrNumber.isBlank()) { notFound++; continue }
                    dbQuery {
                        CustomerPayments.update({ CustomerPayments.paymentId eq paymentId }) {
                            it[CustomerPayments.utrNumber] = parsed.utrNumber
                        }
                    }
                    updated++
                    paymentLog.info("BACKFILL-UTR paymentId={} s3Key={} utrNumber={}", paymentId, s3Key, parsed.utrNumber)
                } catch (e: Exception) {
                    paymentLog.warn("BACKFILL-UTR failed for paymentId={} s3Key={}: {}: {}",
                        paymentId, s3Key, e.javaClass.simpleName, e.message)
                    notFound++
                }
            }
            AuditService.log("customer_payments", "ALL", "BACKFILL_UTR",
                newValues = "scanned=$scanned updated=$updated skippedAlreadySet=$skippedAlreadySet " +
                    "skippedNoReceipt=$skippedNoReceipt notFound=$notFound")
            call.respond(HttpStatusCode.OK, mapOf(
                "scanned" to scanned, "updated" to updated,
                "skippedAlreadySet" to skippedAlreadySet, "skippedNoReceipt" to skippedNoReceipt,
                "notExtractable" to notFound
            ))
        }
    }
}

/** Looks up the customer name + unit label (e.g. "204 (3rd Floor)") needed for a
 *  friendly notification message, then fans out via NotificationService.
 *  Never throws — a notification failure must never break the payment flow. */
private suspend fun notifyPaymentCreatedSafely(
    paymentId: String, customerId: String, projectId: String, unitId: String,
    amount: Double, createdByUserId: String
) {
    try {
        val (customerName, unitLabel) = lookupCustomerAndUnitLabel(customerId, unitId)
        NotificationService.notifyPaymentCreated(
            paymentId, customerId, customerName, projectId, unitId, unitLabel, amount, createdByUserId
        )
    } catch (e: Exception) {
        paymentLog.warn("Failed to create PAYMENT_CREATED notifications for payment {}: {}", paymentId, e.message)
    }
}

private suspend fun notifyPaymentAuditedSafely(
    paymentId: String, customerId: String, projectId: String, unitId: String,
    amount: Double, newStatus: String, auditedByUserId: String, rejectReason: String
) {
    try {
        val (customerName, unitLabel) = lookupCustomerAndUnitLabel(customerId, unitId)
        NotificationService.notifyPaymentAudited(
            paymentId, customerId, customerName, projectId, unitId, unitLabel, amount,
            newStatus, auditedByUserId, rejectReason
        )
    } catch (e: Exception) {
        paymentLog.warn("Failed to create PAYMENT_{} notifications for payment {}: {}", newStatus, paymentId, e.message)
    }
}

private suspend fun lookupCustomerAndUnitLabel(customerId: String, unitId: String): Pair<String, String> = dbQuery {
    val customerName = Customers.selectAll().where { Customers.customerId eq customerId }
        .singleOrNull()?.get(Customers.name) ?: ""
    val unitRow = Units.selectAll().where { Units.unitId eq unitId }.singleOrNull()
    val unitLabel = unitRow?.get(Units.unitNumber) ?: ""
    customerName to unitLabel
}

/** Shared by the audit endpoint (crediting/reversing on status change) and the delete
 *  endpoint (reversing an already-AUDITED payment's credit). Applies [delta] to the
 *  unit's most recent UnitCollections row's paidAmount/pendingAmount/paymentStatus.
 *  [newLastPaymentDate] is only used when crediting (delta > 0); pass "" when
 *  reversing/deleting since there's no new payment date to record. */
private suspend fun reconcileUnitCollection(unitId: String, delta: Double, newLastPaymentDate: String, changedBy: String) {
    if (delta == 0.0 || unitId.isBlank()) return
    val collection = dbQuery {
        UnitCollections.selectAll()
            .where { UnitCollections.unitId eq unitId }
            .orderBy(UnitCollections.createdAt, SortOrder.DESC)
            .firstOrNull()
    } ?: return

    val collectionId = collection[UnitCollections.collectionId]
    val currentPaid   = collection[UnitCollections.paidAmount]
    val totalAmount   = collection[UnitCollections.totalAmount]
    val newPaid       = (currentPaid + delta).coerceAtLeast(0.0)
    val newPending    = (totalAmount - newPaid).coerceAtLeast(0.0)
    val newPayStatus  = when {
        newPaid <= 0.0         -> "Unpaid"
        newPaid >= totalAmount -> "Fully Paid"
        else                   -> "Partial"
    }
    val newLastDate = if (delta > 0 && newLastPaymentDate.isNotBlank()) newLastPaymentDate
                      else collection[UnitCollections.lastPaymentDate]

    dbQuery {
        UnitCollections.update({ UnitCollections.collectionId eq collectionId }) {
            it[UnitCollections.paidAmount]      = newPaid
            it[UnitCollections.pendingAmount]   = newPending
            it[UnitCollections.paymentStatus]   = newPayStatus
            it[UnitCollections.lastPaymentDate] = newLastDate
        }
    }
    AuditService.log("unit_collections", collectionId, "PAYMENT_RECONCILE",
        changedBy = changedBy,
        newValues = "delta=$delta paidAmount=$newPaid pendingAmount=$newPending status=$newPayStatus")
}

/** Looks for an existing payment for the same customer with the same amount and
 *  payment date — and, when the new payment has a transaction ID, the same
 *  transaction ID too. Returns the existing payment's ID if found (a likely
 *  accidental double-submission of the same receipt), or null if none. When
 *  [transactionId] is blank we can't disambiguate by ID, so amount+date alone
 *  is treated as sufficient evidence of a duplicate for that customer. */
private suspend fun findDuplicatePayment(
    customerId: String, amount: Double, paymentDate: String, transactionId: String
): String? = dbQuery {
    var q = CustomerPayments.selectAll().where {
        (CustomerPayments.customerId eq customerId) and
        (CustomerPayments.amount eq amount) and
        (CustomerPayments.paymentDate eq paymentDate)
    }
    if (transactionId.isNotBlank()) {
        q = q.andWhere { CustomerPayments.transactionId eq transactionId }
    }
    q.firstOrNull()?.get(CustomerPayments.paymentId)
}

private fun duplicateMessage(transactionId: String): String =
    if (transactionId.isNotBlank())
        "Duplicate transaction: a payment with this amount, date, and transaction ID already exists for this customer."
    else
        "Duplicate transaction: a payment with this amount and date already exists for this customer."

private fun ResultRow.toPaymentMap() = mapOf(
    "paymentId"          to this[CustomerPayments.paymentId],
    "customerId"         to this[CustomerPayments.customerId],
    "projectId"          to this[CustomerPayments.projectId],
    "unitId"             to this[CustomerPayments.unitId],
    "amount"             to this[CustomerPayments.amount].toString(),
    "paymentDate"        to this[CustomerPayments.paymentDate],
    "transactionId"      to this[CustomerPayments.transactionId],
    "utrNumber"          to this[CustomerPayments.utrNumber],
    "transactionType"    to this[CustomerPayments.transactionType],
    "payerName"          to this[CustomerPayments.payerName],
    "payerBank"          to this[CustomerPayments.payerBank],
    "payerAccount"       to this[CustomerPayments.payerAccount],
    "beneficiaryName"    to this[CustomerPayments.beneficiaryName],
    "beneficiaryBank"    to this[CustomerPayments.beneficiaryBank],
    "beneficiaryAccount" to this[CustomerPayments.beneficiaryAccount],
    "receiptS3Key"       to this[CustomerPayments.receiptS3Key],
    "receiptFileId"      to this[CustomerPayments.receiptFileId],
    "notes"              to this[CustomerPayments.notes],
    "verified"           to this[CustomerPayments.verified].toString(),
    "auditStatus"        to this[CustomerPayments.auditStatus],
    "auditedBy"          to this[CustomerPayments.auditedBy],
    "auditedAt"          to this[CustomerPayments.auditedAt].toString(),
    "rejectReason"       to this[CustomerPayments.rejectReason],
    "chequeNumber"       to this[CustomerPayments.chequeNumber],
    "chequeDate"         to this[CustomerPayments.chequeDate],
    "createdAt"          to this[CustomerPayments.createdAt].toString(),
    "createdBy"          to this[CustomerPayments.createdBy]
)

/** Same as [toPaymentMap] plus unit (number/floor/type/SBA) and customer name,
 *  read from a query that LEFT JOINs Units and Customers alongside CustomerPayments. */
private fun ResultRow.toPaymentMapEnriched() = toPaymentMap() + mapOf(
    "unitNumber"   to (getOrNull(Units.unitNumber) ?: ""),
    "floor"        to (getOrNull(Units.floor) ?: ""),
    "unitType"     to (getOrNull(Units.type) ?: ""),
    "sba"          to (getOrNull(Units.sba)?.toString() ?: "0"),
    "customerName" to (getOrNull(Customers.name) ?: "")
)

private fun JsonObject.obj(key: String): JsonObject =
    this[key]?.jsonObject ?: JsonObject(emptyMap())

private fun normalizeTransactionType(extracted: String, declaredType: String): String {
    val raw = extracted.ifBlank { if (declaredType.equals("AUTO", ignoreCase = true)) "" else declaredType }.trim()
    return when (raw.uppercase()) {
        "UPI" -> "UPI"
        "NEFT" -> "NEFT"
        "RTGS" -> "RTGS"
        "IMPS" -> "IMPS"
        "CHEQUE", "CHECK" -> "Cheque"
        "POST-DATED CHEQUE", "PDC" -> "Post-dated Cheque"
        "DD", "D/D", "DEMAND DRAFT" -> "DD"
        "CASH" -> "Cash"
        "SCREENSHOT" -> "Screenshot"
        "" -> ""
        else -> raw
    }
}

private fun inferDocumentType(normalizedType: String, parsedText: String, declaredType: String): String {
    if (!declaredType.equals("AUTO", ignoreCase = true) && declaredType.isNotBlank()) return declaredType
    if (normalizedType.isNotBlank()) return normalizedType
    val upper = parsedText.uppercase()
    return when {
        "CHEQUE" in upper || "CHQ" in upper -> "Cheque"
        "DEMAND DRAFT" in upper || " D/D " in " $upper " -> "DD"
        "UPI" in upper -> "UPI"
        "NEFT" in upper -> "NEFT"
        "RTGS" in upper -> "RTGS"
        "IMPS" in upper -> "IMPS"
        else -> "Unknown"
    }
}

private fun estimateConfidence(parsed: OcrService.ParsedPayment, source: String, missingCount: Int): Double {
    if (source == "IMAGE" && parsed.amount.isBlank() && parsed.transactionId.isBlank()) return 0.20
    var score = 0.35
    if (parsed.amount.isNotBlank()) score += 0.25
    if (parsed.paymentDate.isNotBlank()) score += 0.15
    if (parsed.transactionType.isNotBlank()) score += 0.10
    if (parsed.transactionId.isNotBlank()) score += 0.10
    if (parsed.payerName.isNotBlank() || parsed.beneficiaryName.isNotBlank()) score += 0.05
    score -= (missingCount * 0.08)
    return score.coerceIn(0.05, 0.98)
}

