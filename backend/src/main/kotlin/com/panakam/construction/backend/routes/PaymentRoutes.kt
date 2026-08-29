package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import com.panakam.construction.backend.db.CustomerPayments
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.service.AuditService
import com.panakam.construction.backend.service.OcrService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun Route.paymentRoutes(s3Client: S3Client, s3PresignClient: S3Client) {
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"

    route("/payments") {

        // ── GET /payments/customer/{customerId}  ──────────────────────────────
        get("/customer/{customerId}") {
            val cid = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val list = dbQuery {
                CustomerPayments.selectAll()
                    .where { CustomerPayments.customerId eq cid }
                    .orderBy(CustomerPayments.createdAt, SortOrder.DESC)
                    .map { it.toPaymentMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── POST /payments  ───────────────────────────────────────────────────
        post {
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val customerId = json.str("customerId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId")) }
            val paymentId  = UUID.randomUUID().toString()

            dbQuery {
                CustomerPayments.insert {
                    it[CustomerPayments.paymentId]          = paymentId
                    it[CustomerPayments.customerId]         = customerId
                    it[CustomerPayments.projectId]          = json.str("projectId")
                    it[CustomerPayments.unitId]             = json.str("unitId")
                    it[CustomerPayments.amount]             = json.str("amount").toDoubleOrNull() ?: 0.0
                    it[CustomerPayments.paymentDate]        = json.str("paymentDate")
                    it[CustomerPayments.transactionId]      = json.str("transactionId")
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
                    it[CustomerPayments.createdAt]          = System.currentTimeMillis()
                    it[CustomerPayments.createdBy]          = json.str("createdBy")
                }
            }
            AuditService.log("customer_payments", paymentId, "CREATE",
                changedBy = json.str("createdBy"), newValues = json.toString())
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

            val textToparse = when {
                rawText.isNotBlank() -> rawText
                s3Key.isNotBlank()   -> {
                    // Download from MinIO and extract text
                    try {
                        val resp = s3Client.getObject(GetObjectRequest {
                            bucket = bucketName; key = s3Key
                        }) { it.body?.toByteArray()?.inputStream() }
                        if (resp != null) OcrService.extractTextFromPdf(resp) else ""
                    } catch (e: Exception) { "" }
                }
                else -> ""
            }

            if (textToparse.isBlank()) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "No text to parse", "parsed" to mapOf<String, String>()))
                return@post
            }

            val parsed = OcrService.parsePaymentText(textToparse)
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Parsed successfully",
                "parsed"  to mapOf(
                    "amount"             to parsed.amount,
                    "paymentDate"        to parsed.paymentDate,
                    "transactionId"      to parsed.transactionId,
                    "transactionType"    to parsed.transactionType,
                    "payerName"          to parsed.payerName,
                    "payerBank"          to parsed.payerBank,
                    "payerAccount"       to parsed.payerAccount,
                    "beneficiaryName"    to parsed.beneficiaryName,
                    "beneficiaryBank"    to parsed.beneficiaryBank,
                    "beneficiaryAccount" to parsed.beneficiaryAccount
                )
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
                    json.str("paymentDate").takeIf { it.isNotBlank() }?.let { v -> it[CustomerPayments.paymentDate]      = v }
                    json.str("transactionId").let                          { v -> it[CustomerPayments.transactionId]      = v }
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

        // ── DELETE /payments/{paymentId}  ─────────────────────────────────────
        delete("/{paymentId}") {
            val id  = call.parameters["paymentId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing paymentId"))
            val old = dbQuery {
                CustomerPayments.selectAll().where { CustomerPayments.paymentId eq id }.singleOrNull()?.toPaymentMap()
            }
            dbQuery { CustomerPayments.deleteWhere { CustomerPayments.paymentId eq id } }
            AuditService.log("customer_payments", id, "DELETE", newValues = old.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Payment deleted"))
        }
    }
}

private fun ResultRow.toPaymentMap() = mapOf(
    "paymentId"          to this[CustomerPayments.paymentId],
    "customerId"         to this[CustomerPayments.customerId],
    "projectId"          to this[CustomerPayments.projectId],
    "unitId"             to this[CustomerPayments.unitId],
    "amount"             to this[CustomerPayments.amount].toString(),
    "paymentDate"        to this[CustomerPayments.paymentDate],
    "transactionId"      to this[CustomerPayments.transactionId],
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
    "createdAt"          to this[CustomerPayments.createdAt].toString(),
    "createdBy"          to this[CustomerPayments.createdBy]
)

