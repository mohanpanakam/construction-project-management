package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import com.panakam.construction.backend.db.AgreementTemplates
import com.panakam.construction.backend.db.Agreements
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Projects
import com.panakam.construction.backend.db.UnitCollections
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.service.AuditService
import com.panakam.construction.backend.service.OcrService
import com.panakam.construction.backend.service.PdfGenerator
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val agreementLog = LoggerFactory.getLogger("AgreementRoutes")

/**
 * SPEC items #1, #3, #4 — agreement templates (per project, admin-uploaded) and
 * generated agreement drafts (auto-filled from unit + customer KYC details, shared
 * to the customer portal, accepted/countersigned).
 */
fun Route.agreementRoutes(s3Client: S3Client, s3PresignClient: S3Client) {
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"

    // ── Templates (per project) ────────────────────────────────────────────────
    route("/projects/{projectId}/agreement-templates") {

        get {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                AgreementTemplates.selectAll().where { AgreementTemplates.projectId eq projectId }
                    .orderBy(AgreementTemplates.uploadedAt, SortOrder.DESC)
                    .map { it.toTemplateMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // Presigned PUT for the raw template file (PDF/DOCX/TXT).
        post("/upload-url") {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val fileName    = json.str("fileName").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileName"))
            }
            val contentType = json.str("contentType", "application/octet-stream")
            val templateId  = UUID.randomUUID().toString()
            val s3Key       = "projects/$projectId/agreement-templates/$templateId-$fileName"

            val presigned = s3PresignClient.presignPutObject(PutObjectRequest {
                bucket = bucketName; key = s3Key
            }, 15.minutes)

            call.respond(HttpStatusCode.OK, mapOf(
                "templateId" to templateId,
                "uploadUrl"  to presigned.url.toString(),
                "s3Key"      to s3Key
            ))
        }

        // Registers the uploaded template + extracts its placeholder text.
        post {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val templateId = json.str("templateId").ifBlank { UUID.randomUUID().toString() }
            val name        = json.str("name").ifBlank { "Agreement Template" }
            val s3Key       = json.str("s3Key")
            val contentType = json.str("contentType", "application/octet-stream")
            val uploadedBy  = json.str("uploadedBy")
            // Allow the template text to be provided directly (e.g. typed/pasted in the
            // app), otherwise extract it from the uploaded file (PDF text extraction;
            // plain text files are read as-is).
            var templateText = json.str("templateText")

            if (templateText.isBlank() && s3Key.isNotBlank()) {
                templateText = try {
                    val bytes = s3Client.getObject(GetObjectRequest { bucket = bucketName; key = s3Key }) { resp ->
                        resp.body?.toByteArray() ?: ByteArray(0)
                    }
                    when {
                        s3Key.endsWith(".pdf", ignoreCase = true) -> OcrService.extractTextFromPdf(bytes.inputStream())
                        else -> String(bytes, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    agreementLog.warn("Failed to extract template text from s3Key={}: {}", s3Key, e.message)
                    ""
                }
            }

            dbQuery {
                AgreementTemplates.insert {
                    it[AgreementTemplates.templateId]   = templateId
                    it[AgreementTemplates.projectId]    = projectId
                    it[AgreementTemplates.name]         = name
                    it[AgreementTemplates.s3Key]        = s3Key
                    it[AgreementTemplates.contentType]  = contentType
                    it[AgreementTemplates.templateText] = templateText
                    it[AgreementTemplates.uploadedAt]   = System.currentTimeMillis()
                    it[AgreementTemplates.uploadedBy]   = uploadedBy
                }
            }
            AuditService.log("agreement_templates", templateId, "CREATE", changedBy = uploadedBy)
            call.respond(HttpStatusCode.Created, mapOf("message" to "Template saved", "templateId" to templateId))
        }

        get("/{templateId}") {
            val templateId = call.parameters["templateId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing templateId"))
            val row = dbQuery {
                AgreementTemplates.selectAll().where { AgreementTemplates.templateId eq templateId }.singleOrNull()?.toTemplateMap()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Template not found"))
            call.respond(HttpStatusCode.OK, row)
        }

        delete("/{templateId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val templateId = call.parameters["templateId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing templateId"))
            dbQuery {
                AgreementTemplates.deleteWhere {
                    (AgreementTemplates.projectId eq projectId) and (AgreementTemplates.templateId eq templateId)
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Template deleted"))
        }
    }

    // ── Agreements (drafts sent to customer portal) ────────────────────────────
    route("/agreements") {

        // Create + auto-fill + send a draft agreement for a specific unit/customer.
        post {
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId  = json.str("projectId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId")) }
            val unitId     = json.str("unitId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId")) }
            val customerId = json.str("customerId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId")) }
            val templateId = json.str("templateId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing templateId")) }
            val createdBy  = json.str("createdBy")

            val template = dbQuery {
                AgreementTemplates.selectAll().where { AgreementTemplates.templateId eq templateId }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Template not found"))

            val customer = dbQuery {
                Customers.selectAll().where { Customers.customerId eq customerId }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Customer not found"))

            val unit = dbQuery {
                Units.selectAll().where { Units.unitId eq unitId }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unit not found"))

            val project = dbQuery {
                Projects.selectAll().where { Projects.projectId eq projectId }.singleOrNull()
            }

            val collection = dbQuery {
                UnitCollections.selectAll()
                    .where { (UnitCollections.unitId eq unitId) and (UnitCollections.status eq "Active") }
                    .orderBy(UnitCollections.createdAt, SortOrder.DESC)
                    .firstOrNull()
            }

            val totalAmount = collection?.get(UnitCollections.totalAmount) ?: customer[Customers.totalCost]
            val sba = collection?.get(UnitCollections.sba) ?: unit[Units.sba]
            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

            val placeholders = mapOf(
                "CUSTOMER_NAME"  to customer[Customers.name],
                "AADHAR_NUMBER"  to customer[Customers.aadharNumber],
                "ADDRESS"        to customer[Customers.address],
                "CUSTOMER_PHONE" to customer[Customers.phone],
                "PROJECT_NAME"   to (project?.get(Projects.name) ?: ""),
                "PROJECT_LOCATION" to (project?.get(Projects.location) ?: ""),
                "UNIT_NUMBER"    to unit[Units.unitNumber],
                "FLOOR"          to unit[Units.floor],
                "UNIT_TYPE"      to unit[Units.type],
                "SBA"            to (if (sba > 0) "${sba.toInt()}" else ""),
                "TOTAL_AMOUNT"   to "%,.2f".format(totalAmount),
                "DATE"           to dateStr
            )

            if (customer[Customers.aadharNumber].isBlank() || customer[Customers.name].isBlank()) {
                agreementLog.warn("Creating agreement for customerId={} with incomplete KYC (name/aadhar blank) — draft will have empty placeholders", customerId)
            }

            var content = template[AgreementTemplates.templateText]
            placeholders.forEach { (key, value) -> content = content.replace("{{$key}}", value) }
            if (content.isBlank()) {
                content = "Agreement for Unit ${placeholders["UNIT_NUMBER"]}, ${placeholders["PROJECT_NAME"]}\n\n" +
                    "This agreement is between the builder and ${placeholders["CUSTOMER_NAME"]} " +
                    "(Aadhaar: ${placeholders["AADHAR_NUMBER"]}), residing at ${placeholders["ADDRESS"]}, " +
                    "for Unit ${placeholders["UNIT_NUMBER"]}, Floor ${placeholders["FLOOR"]}, " +
                    "${placeholders["SBA"]} sq.ft, at a total consideration of Rs. ${placeholders["TOTAL_AMOUNT"]}.\n\n" +
                    "Date: ${placeholders["DATE"]}"
            }

            val agreementId = UUID.randomUUID().toString()
            val pdfBytes = PdfGenerator.textToPdf(
                "Sale Agreement — Unit ${placeholders["UNIT_NUMBER"]}", content
            )
            val pdfS3Key = "customers/$customerId/agreements/$agreementId.pdf"
            s3Client.putObject(PutObjectRequest {
                bucket = bucketName; key = pdfS3Key
                body = ByteStream.fromBytes(pdfBytes)
                contentType = "application/pdf"
            })

            dbQuery {
                Agreements.insert {
                    it[Agreements.agreementId] = agreementId
                    it[Agreements.projectId]   = projectId
                    it[Agreements.unitId]      = unitId
                    it[Agreements.customerId]  = customerId
                    it[Agreements.templateId]  = templateId
                    it[Agreements.content]     = content
                    it[Agreements.pdfS3Key]    = pdfS3Key
                    it[Agreements.status]      = "SENT" // immediately visible in customer portal
                    it[Agreements.createdAt]   = System.currentTimeMillis()
                    it[Agreements.createdBy]   = createdBy
                }
            }
            AuditService.log("agreements", agreementId, "CREATE_AND_SEND", changedBy = createdBy,
                newValues = "customerId=$customerId unitId=$unitId templateId=$templateId")
            call.respond(HttpStatusCode.Created, mapOf(
                "message" to "Agreement draft created and sent to customer portal",
                "agreementId" to agreementId,
                "status" to "SENT"
            ))
        }

        get("/customer/{customerId}") {
            val customerId = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val list = dbQuery {
                Agreements
                    .join(Units, JoinType.LEFT, onColumn = Agreements.unitId, otherColumn = Units.unitId)
                    .selectAll()
                    .where { (Agreements.customerId eq customerId) and (Agreements.status neq "DRAFT") }
                    .orderBy(Agreements.createdAt, SortOrder.DESC)
                    .map { it.toAgreementMapEnriched() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        get("/project/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                Agreements
                    .join(Units, JoinType.LEFT, onColumn = Agreements.unitId, otherColumn = Units.unitId)
                    .join(Customers, JoinType.LEFT, onColumn = Agreements.customerId, otherColumn = Customers.customerId)
                    .selectAll()
                    .where { Agreements.projectId eq projectId }
                    .orderBy(Agreements.createdAt, SortOrder.DESC)
                    .map { it.toAgreementMapEnriched() + mapOf("customerName" to (it.getOrNull(Customers.name) ?: "")) }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        get("/{agreementId}") {
            val id = call.parameters["agreementId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            val row = dbQuery {
                Agreements.selectAll().where { Agreements.agreementId eq id }.singleOrNull()?.toAgreementMap()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Agreement not found"))
            call.respond(HttpStatusCode.OK, row)
        }

        get("/{agreementId}/download-url") {
            val id = call.parameters["agreementId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            val row = dbQuery {
                Agreements.selectAll().where { Agreements.agreementId eq id }.singleOrNull()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Agreement not found"))
            val objectKey = row[Agreements.signedPdfS3Key].ifBlank { row[Agreements.pdfS3Key] }
            if (objectKey.isBlank()) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No document available"))
            val presigned = s3PresignClient.presignGetObject(GetObjectRequest { bucket = bucketName; key = objectKey }, 30.minutes)
            call.respond(HttpStatusCode.OK, mapOf("downloadUrl" to presigned.url.toString()))
        }

        // Customer accepts the draft. Blank comments => ACCEPTED (ready for builder
        // to sign, per SPEC "if customer accepts without comments"); non-blank
        // comments => REVISION_REQUESTED (sent back to the builder).
        put("/{agreementId}/accept") {
            val id = call.parameters["agreementId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val comments = json.str("comments").trim()
            val respondedBy = json.str("respondedBy")
            val newStatus = if (comments.isBlank()) "ACCEPTED" else "REVISION_REQUESTED"

            val updated = dbQuery {
                Agreements.update({ Agreements.agreementId eq id }) {
                    it[Agreements.status]            = newStatus
                    it[Agreements.customerComments]  = comments
                    it[Agreements.respondedAt]       = System.currentTimeMillis()
                }
            }
            if (updated == 0) return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Agreement not found"))
            AuditService.log("agreements", id, "CUSTOMER_RESPOND", changedBy = respondedBy,
                newValues = "status=$newStatus comments=$comments")
            call.respond(HttpStatusCode.OK, mapOf("message" to "Response recorded", "status" to newStatus))
        }

        // Builder/admin countersigns an ACCEPTED agreement.
        put("/{agreementId}/sign") {
            val id = call.parameters["agreementId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val signedBy      = json.str("signedBy")
            val signedPdfS3Key = json.str("signedPdfS3Key")

            val current = dbQuery {
                Agreements.selectAll().where { Agreements.agreementId eq id }.singleOrNull()
            } ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Agreement not found"))
            if (current[Agreements.status] != "ACCEPTED")
                return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "Agreement must be ACCEPTED by the customer before it can be signed"))

            dbQuery {
                Agreements.update({ Agreements.agreementId eq id }) {
                    it[Agreements.status]     = "SIGNED"
                    it[Agreements.signedBy]   = signedBy
                    it[Agreements.signedAt]   = System.currentTimeMillis()
                    if (signedPdfS3Key.isNotBlank()) it[Agreements.signedPdfS3Key] = signedPdfS3Key
                }
            }
            AuditService.log("agreements", id, "SIGN", changedBy = signedBy)
            call.respond(HttpStatusCode.OK, mapOf("message" to "Agreement signed", "status" to "SIGNED"))
        }

        // Presigned PUT for the builder to upload a scanned/countersigned copy.
        post("/{agreementId}/signed-upload-url") {
            val id = call.parameters["agreementId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val fileName    = json.str("fileName").ifBlank { "signed_$id.pdf" }
            val s3Key       = "agreements/$id/signed-$fileName"
            val presigned = s3PresignClient.presignPutObject(PutObjectRequest {
                bucket = bucketName; key = s3Key
            }, 15.minutes)
            call.respond(HttpStatusCode.OK, mapOf("uploadUrl" to presigned.url.toString(), "s3Key" to s3Key))
        }

        delete("/{agreementId}") {
            val id = call.parameters["agreementId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing agreementId"))
            dbQuery { Agreements.deleteWhere { Agreements.agreementId eq id } }
            AuditService.log("agreements", id, "DELETE")
            call.respond(HttpStatusCode.OK, mapOf("message" to "Agreement deleted"))
        }
    }
}

private fun ResultRow.toTemplateMap() = mapOf(
    "templateId"   to this[AgreementTemplates.templateId],
    "projectId"    to this[AgreementTemplates.projectId],
    "name"         to this[AgreementTemplates.name],
    "s3Key"        to this[AgreementTemplates.s3Key],
    "contentType"  to this[AgreementTemplates.contentType],
    "templateText" to this[AgreementTemplates.templateText],
    "uploadedAt"   to this[AgreementTemplates.uploadedAt].toString(),
    "uploadedBy"   to this[AgreementTemplates.uploadedBy]
)

private fun ResultRow.toAgreementMap() = mapOf(
    "agreementId"       to this[Agreements.agreementId],
    "projectId"         to this[Agreements.projectId],
    "unitId"            to this[Agreements.unitId],
    "customerId"        to this[Agreements.customerId],
    "templateId"        to this[Agreements.templateId],
    "content"           to this[Agreements.content],
    "pdfS3Key"          to this[Agreements.pdfS3Key],
    "status"            to this[Agreements.status],
    "customerComments"  to this[Agreements.customerComments],
    "signedPdfS3Key"    to this[Agreements.signedPdfS3Key],
    "createdAt"         to this[Agreements.createdAt].toString(),
    "createdBy"         to this[Agreements.createdBy],
    "respondedAt"       to this[Agreements.respondedAt].toString(),
    "signedAt"          to this[Agreements.signedAt].toString(),
    "signedBy"          to this[Agreements.signedBy]
)

private fun ResultRow.toAgreementMapEnriched() = toAgreementMap() + mapOf(
    "unitNumber" to (getOrNull(Units.unitNumber) ?: ""),
    "floor"      to (getOrNull(Units.floor) ?: ""),
    "unitType"   to (getOrNull(Units.type) ?: "")
)



