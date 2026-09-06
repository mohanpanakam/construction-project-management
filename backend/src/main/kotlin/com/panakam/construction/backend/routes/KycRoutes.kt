package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.sdk.kotlin.services.textract.TextractClient
import aws.smithy.kotlin.runtime.content.toByteArray
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.service.AuditService
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
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val kycLog = LoggerFactory.getLogger("KycRoutes")

/**
 * Customer portal "Update KYC" flow (SPEC item #2):
 *  1. POST /customers/{customerId}/kyc/upload-url  — presigned S3 PUT for the Aadhaar image.
 *  2. Client uploads the image directly to S3/MinIO.
 *  3. POST /customers/{customerId}/kyc/extract      — backend OCRs the image, parses
 *     name/Aadhaar number/address, returns a draft for the customer to review/edit.
 *  4. POST /customers/{customerId}/kyc/confirm       — persists the (possibly edited)
 *     values into Customers.name/address/aadharNumber — the single source of truth
 *     later used by AgreementRoutes.kt to auto-fill agreement drafts.
 */
fun Route.kycRoutes(
    s3Client: S3Client,
    s3PresignClient: S3Client,
    textractClient: TextractClient?,
    ocrProvider: String,
    ocrHttpClient: HttpClient,
    paddleOcrUrl: String
) {
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"

    route("/customers/{customerId}/kyc") {

        // ── POST /customers/{customerId}/kyc/upload-url ───────────────────────
        post("/upload-url") {
            val customerId = call.parameters["customerId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val fileName    = json.str("fileName").ifBlank { "aadhaar_${System.currentTimeMillis()}" }
            val contentType = json.str("contentType", "image/jpeg")
            val fileId      = UUID.randomUUID().toString()
            val s3Key       = "customers/$customerId/kyc/$fileId-$fileName"

            val presigned = s3PresignClient.presignPutObject(PutObjectRequest {
                bucket = bucketName; key = s3Key
            }, 15.minutes)

            call.respond(HttpStatusCode.OK, mapOf(
                "fileId"    to fileId,
                "uploadUrl" to presigned.url.toString(),
                "s3Key"     to s3Key
            ))
        }

        // ── POST /customers/{customerId}/kyc/extract ──────────────────────────
        // Body: { "s3Key": "..." }
        post("/extract") {
            val customerId = call.parameters["customerId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json  = Json.parseToJsonElement(call.receiveText()).jsonObject
            val s3Key = json.str("s3Key").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing s3Key"))
            }

            val warnings = mutableListOf<String>()
            val text = try {
                val bytes = s3Client.getObject(GetObjectRequest {
                    bucket = bucketName; key = s3Key
                }) { resp -> resp.body?.toByteArray() ?: ByteArray(0) }
                if (bytes.isEmpty()) {
                    warnings += "Uploaded image is empty"
                    ""
                } else if (s3Key.endsWith(".pdf", ignoreCase = true)) {
                    OcrService.extractTextFromPdf(bytes.inputStream())
                } else {
                    val t = when {
                        ocrProvider.equals("TEXTRACT", ignoreCase = true) && textractClient != null ->
                            OcrService.extractTextFromImage(textractClient, bytes)
                        ocrProvider.equals("PADDLE", ignoreCase = true) ->
                            OcrService.extractTextFromImagePaddle(ocrHttpClient, paddleOcrUrl, bytes)
                        else ->
                            OcrService.extractTextFromImageLocal(bytes)
                    }
                    if (t.isBlank()) warnings += "Could not confidently read the image; please review and edit fields"
                    t
                }
            } catch (e: Exception) {
                kycLog.warn("KYC-EXTRACT failed to fetch/process s3Key={}: {}: {}", s3Key, e.javaClass.simpleName, e.message)
                warnings += "Failed to process uploaded image"
                ""
            }

            val parsed = if (text.isNotBlank()) OcrService.parseAadhaar(text) else OcrService.ParsedAadhaar()
            kycLog.info(
                "KYC-EXTRACT customerId={} s3Key={} rawTextLen={} parsed={}",
                customerId, s3Key, text.length, parsed
            )
            val missingFields = listOfNotNull(
                "name".takeIf { parsed.name.isBlank() },
                "aadharNumber".takeIf { parsed.aadharNumber.isBlank() },
                "address".takeIf { parsed.address.isBlank() }
            )
            if (missingFields.isNotEmpty()) warnings += "Some fields could not be read automatically — please fill them in"

            call.respond(HttpStatusCode.OK, buildJsonObject {
                put("s3Key", s3Key)
                put("name", parsed.name)
                put("aadharNumber", parsed.aadharNumber)
                put("address", parsed.address)
                put("dob", parsed.dob)
                put("gender", parsed.gender)
                putJsonArray("missingFields") { missingFields.forEach { add(it) } }
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            })
        }

        // ── POST /customers/{customerId}/kyc/confirm ──────────────────────────
        // Body: { "name":..., "aadharNumber":..., "address":..., "s3Key":... }
        // Persists into Customers (source of truth for agreement auto-fill).
        post("/confirm") {
            val customerId = call.parameters["customerId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val name         = json.str("name").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name is required"))
            }
            val aadharNumber = json.str("aadharNumber")
            val address      = json.str("address")
            val s3Key        = json.str("s3Key")

            val old = dbQuery {
                Customers.selectAll().where { Customers.customerId eq customerId }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Customer not found"))

            // Propagate to ALL Customers rows sharing this phone number — a customer
            // may have bought multiple units (one Customers row each), but there's
            // only one real person/KYC identity, and the agreement for ANY of their
            // units should read the same verified name/Aadhaar/address.
            val phone = old[Customers.phone]
            dbQuery {
                val whereClause: SqlExpressionBuilder.() -> Op<Boolean> =
                    if (phone.isNotBlank()) { { Customers.phone eq phone } } else { { Customers.customerId eq customerId } }
                Customers.update({ whereClause() }) {
                    it[Customers.name]         = name
                    it[Customers.address]      = address
                    it[Customers.aadharNumber] = aadharNumber
                    if (s3Key.isNotBlank()) it[Customers.aadharS3Key] = s3Key
                    it[Customers.kycStatus]    = "VERIFIED"
                    it[Customers.kycUpdatedAt] = System.currentTimeMillis()
                }
            }
            AuditService.log("customers", customerId, "KYC_CONFIRM",
                changedBy = customerId, oldValues = old[Customers.name], newValues = json.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "KYC updated", "kycStatus" to "VERIFIED"))
        }

        // ── GET /customers/{customerId}/kyc  (current KYC status/details) ─────
        get {
            val customerId = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val row = dbQuery {
                Customers.selectAll().where { Customers.customerId eq customerId }.singleOrNull()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Customer not found"))

            call.respond(HttpStatusCode.OK, mapOf(
                "customerId"   to customerId,
                "name"         to row[Customers.name],
                "address"      to row[Customers.address],
                "aadharNumber" to row[Customers.aadharNumber],
                "aadharS3Key"  to row[Customers.aadharS3Key],
                "kycStatus"    to row[Customers.kycStatus],
                "kycUpdatedAt" to row[Customers.kycUpdatedAt].toString()
            ))
        }

        // ── GET /customers/{customerId}/kyc/aadhaar-url  (view uploaded image) ─
        get("/aadhaar-url") {
            val customerId = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val s3Key = dbQuery {
                Customers.selectAll().where { Customers.customerId eq customerId }.singleOrNull()?.get(Customers.aadharS3Key)
            }
            if (s3Key.isNullOrBlank())
                return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No Aadhaar uploaded"))
            val presigned = s3PresignClient.presignGetObject(GetObjectRequest {
                bucket = bucketName; key = s3Key
            }, 30.minutes)
            call.respond(HttpStatusCode.OK, mapOf("downloadUrl" to presigned.url.toString()))
        }
    }
}


