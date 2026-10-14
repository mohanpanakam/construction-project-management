package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.CustomerPayments
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Financials
import com.panakam.construction.backend.db.SuspenseEntries
import com.panakam.construction.backend.db.UnitCollections
import com.panakam.construction.backend.service.AuditService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.suspenseRoutes() {

    route("/suspense") {

        // ── GET /suspense?projectId=xxx ───────────────────────────────────────
        get {
            val projectId = call.request.queryParameters["projectId"]
            val list = dbQuery {
                var q = SuspenseEntries.selectAll()
                if (projectId != null) q = q.andWhere { SuspenseEntries.projectId eq projectId }
                q.orderBy(SuspenseEntries.createdAt, SortOrder.DESC).map { it.toSuspenseMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /suspense/summary?projectId=xxx ───────────────────────────────
        get("/summary") {
            val projectId = call.request.queryParameters["projectId"]
            val rows = dbQuery {
                var q = SuspenseEntries.selectAll()
                if (projectId != null) q = q.andWhere { SuspenseEntries.projectId eq projectId }
                q.map { it.toSuspenseMap() }
            }
            val totalEntries    = rows.size
            val holdingEntries  = rows.count { it["status"] == "Holding" }
            val refundedEntries = rows.count { it["status"] == "Refunded" }
            val totalCollected  = rows.sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            val holdingAmount   = rows.filter { it["status"] == "Holding" }
                .sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            val refundedAmount  = rows.filter { it["status"] == "Refunded" }
                .sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            call.respond(HttpStatusCode.OK, mapOf(
                "totalEntries"    to totalEntries.toString(),
                "holdingEntries"  to holdingEntries.toString(),
                "refundedEntries" to refundedEntries.toString(),
                "totalCollected"  to totalCollected.toString(),
                "holdingAmount"   to holdingAmount.toString(),
                "refundedAmount"  to refundedAmount.toString()
            ))
        }

        // ── GET /suspense/project/{projectId} ─────────────────────────────────
        get("/project/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                SuspenseEntries.selectAll()
                    .where { SuspenseEntries.projectId eq projectId }
                    .orderBy(SuspenseEntries.createdAt, SortOrder.DESC)
                    .map { it.toSuspenseMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /suspense/{suspenseId} ────────────────────────────────────────
        get("/{suspenseId}") {
            val id = call.parameters["suspenseId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing suspenseId"))
            val row = dbQuery {
                SuspenseEntries.selectAll().where { SuspenseEntries.suspenseId eq id }.singleOrNull()?.toSuspenseMap()
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Suspense entry not found"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // ── PUT /suspense/{suspenseId}/adjust  (admin marks as Adjusted) ──────
        put("/{suspenseId}/adjust") {
            val id   = call.parameters["suspenseId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing suspenseId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            dbQuery {
                SuspenseEntries.update({ SuspenseEntries.suspenseId eq id }) {
                    it[SuspenseEntries.status] = "Adjusted"
                    it[SuspenseEntries.notes]  = json.str("notes")
                }
            }
            AuditService.log("suspense_entries", id, "ADJUSTED",
                changedBy = json.str("adjustedBy"), newValues = json.str("notes"))
            call.respond(HttpStatusCode.OK, mapOf("message" to "Suspense entry marked as Adjusted"))
        }

        // ── PUT /suspense/{suspenseId}/reassign  (move held money onto the CORRECT unit) ──
        // Handles the "sales rep picked the wrong unit, admin reverted it, now the payments
        // need to end up on the unit the customer actually bought" scenario WITHOUT a staff
        // member manually re-typing brand-new payment records by hand (error-prone — see
        // Tables.kt SuspenseEntries doc comment for the real incident this fixes). Instead,
        // the ORIGINAL CustomerPayments rows (receipts, bank details, transaction IDs, audit
        // history — all of it) are re-pointed onto the target unit/customer/collection, so
        // nothing about the original transaction is lost or duplicated.
        put("/{suspenseId}/reassign") {
            val id = call.parameters["suspenseId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing suspenseId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val targetUnitId = json.str("targetUnitId").ifBlank {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing targetUnitId"))
            }
            val adjustedBy = json.str("adjustedBy")
            val extraNotes = json.str("notes")

            val suspense = dbQuery {
                SuspenseEntries.selectAll().where { SuspenseEntries.suspenseId eq id }.singleOrNull()
            } ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Suspense entry not found"))

            if (suspense[SuspenseEntries.status] != "Holding") {
                return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "This suspense entry has already been adjusted"))
            }

            val originalCollectionId = suspense[SuspenseEntries.originalCollectionId]
            if (originalCollectionId.isBlank()) {
                return@put call.respond(HttpStatusCode.Conflict, mapOf(
                    "error" to "This suspense entry predates automatic reassignment support (no original sale reference was captured) — adjust it manually instead via /suspense/{id}/adjust."
                ))
            }

            val targetCollection = dbQuery {
                UnitCollections.selectAll()
                    .where { (UnitCollections.unitId eq targetUnitId) and (UnitCollections.status eq "Active") }
                    .orderBy(UnitCollections.createdAt, SortOrder.DESC)
                    .firstOrNull()
            } ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target unit has no active sale to attach these payments to — sell it to the correct customer first"))

            val targetCollectionId = targetCollection[UnitCollections.collectionId]
            val targetProjectId    = targetCollection[UnitCollections.projectId]

            val targetCustomerId = dbQuery {
                Customers.select(Customers.customerId)
                    .where { (Customers.unitId eq targetUnitId) and (Customers.isActive eq true) }
                    .firstOrNull()?.get(Customers.customerId)
            } ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target unit has no active customer record"))

            // Re-point every CustomerPayments row that belonged to the original (now
            // Reverted) sale onto the target unit/customer/collection — preserving
            // amount/date/transactionId/UTR/bank fields/receipt/auditStatus as-is.
            val movedPaymentIds = dbQuery {
                val ids = CustomerPayments.select(CustomerPayments.paymentId)
                    .where { CustomerPayments.collectionId eq originalCollectionId }
                    .map { it[CustomerPayments.paymentId] }
                if (ids.isNotEmpty()) {
                    CustomerPayments.update({ CustomerPayments.collectionId eq originalCollectionId }) {
                        it[CustomerPayments.customerId]   = targetCustomerId
                        it[CustomerPayments.projectId]    = targetProjectId
                        it[CustomerPayments.unitId]       = targetUnitId
                        it[CustomerPayments.collectionId] = targetCollectionId
                    }
                }
                ids
            }

            if (movedPaymentIds.isEmpty()) {
                return@put call.respond(HttpStatusCode.Conflict, mapOf(
                    "error" to "No payments found for this suspense entry's original sale — it may already have been reassigned, or had none to begin with."
                ))
            }

            // Only AUDITED payments count towards the unit's credited paidAmount —
            // PENDING ones still need an auditor to approve them on the target unit,
            // exactly as if they'd been entered there in the first place.
            val auditedMovedTotal = dbQuery {
                CustomerPayments.selectAll()
                    .where { (CustomerPayments.collectionId eq targetCollectionId) and (CustomerPayments.auditStatus eq "AUDITED") and (CustomerPayments.paymentId inList movedPaymentIds) }
                    .sumOf { it[CustomerPayments.amount] }
            }
            if (auditedMovedTotal > 0) {
                reconcileUnitCollection(targetUnitId, auditedMovedTotal, "", adjustedBy)
            }

            val summaryNote = "Reassigned ${movedPaymentIds.size} payment(s) (₹$auditedMovedTotal already audited) to unit $targetUnitId." +
                if (extraNotes.isNotBlank()) " $extraNotes" else ""
            dbQuery {
                SuspenseEntries.update({ SuspenseEntries.suspenseId eq id }) {
                    it[SuspenseEntries.status] = "Adjusted"
                    it[SuspenseEntries.notes]  = summaryNote
                }
            }

            AuditService.log("suspense_entries", id, "REASSIGNED",
                changedBy = adjustedBy,
                newValues = "targetUnitId=$targetUnitId targetCustomerId=$targetCustomerId movedPayments=${movedPaymentIds.size} auditedAmount=$auditedMovedTotal")

            call.respond(HttpStatusCode.OK, mapOf(
                "message"          to "Suspense entry reassigned to the correct unit",
                "movedPayments"    to movedPaymentIds.size.toString(),
                "auditedAmount"    to auditedMovedTotal.toString(),
                "targetCustomerId" to targetCustomerId,
                "targetUnitId"     to targetUnitId
            ))
        }

        // ── PUT /suspense/{suspenseId}/refund  (customer cancelled — money actually PAID BACK) ──
        // The opposite of /reassign: used when the unit was reverted because the CUSTOMER
        // genuinely cancelled and is being refunded, not because of a sales-rep mistake that
        // can be corrected onto another unit. Unlike /reassign, this doesn't touch the
        // original CustomerPayments rows (they correctly remain as historical proof the money
        // WAS received) — it instead records the refund as a real cash outflow in Financials
        // and marks the suspense entry "Refunded" (distinct from "Adjusted") so reports can
        // tell "money moved internally" apart from "money left the business" at a glance.
        // Works even for legacy suspense entries that predate originalCollectionId tracking,
        // since it never needs to locate the individual payment rows.
        put("/{suspenseId}/refund") {
            val id = call.parameters["suspenseId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing suspenseId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val refundMethod    = json.str("refundMethod").ifBlank { "Bank Transfer" }
            val refundReference = json.str("refundReference")
            val refundedBy      = json.str("refundedBy")
            val extraNotes      = json.str("notes")

            val suspense = dbQuery {
                SuspenseEntries.selectAll().where { SuspenseEntries.suspenseId eq id }.singleOrNull()
            } ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Suspense entry not found"))

            if (suspense[SuspenseEntries.status] != "Holding") {
                return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "This suspense entry has already been adjusted/refunded"))
            }

            val amount     = suspense[SuspenseEntries.collectedAmount]
            val unitNumber = suspense[SuspenseEntries.unitNumber]
            val custName   = suspense[SuspenseEntries.originalCustomerName]
            val projectId  = suspense[SuspenseEntries.projectId]

            // Record the actual cash outflow so Financials/expense reports reflect reality —
            // this is money leaving the business, unlike a /reassign which keeps it internal.
            val financialRecordId = java.util.UUID.randomUUID().toString()
            if (amount > 0) {
                dbQuery {
                    Financials.insert {
                        it[Financials.recordId]  = financialRecordId
                        it[Financials.projectId] = projectId
                        it[type]                 = "Expense"
                        it[category]             = "Customer Refund"
                        it[Financials.amount]    = amount
                        it[description]          = "Refund to ${custName.ifBlank { "customer" }} for unit $unitNumber" +
                            (if (refundReference.isNotBlank()) " (Ref: $refundReference)" else "")
                        it[date]                 = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    }
                }
            }

            val summaryNote = "Refunded ₹$amount to $custName via $refundMethod" +
                (if (refundReference.isNotBlank()) " (Ref: $refundReference)" else "") +
                if (extraNotes.isNotBlank()) ". $extraNotes" else ""
            dbQuery {
                SuspenseEntries.update({ SuspenseEntries.suspenseId eq id }) {
                    it[SuspenseEntries.status]          = "Refunded"
                    it[SuspenseEntries.refundMethod]    = refundMethod
                    it[SuspenseEntries.refundReference] = refundReference
                    it[SuspenseEntries.refundedBy]      = refundedBy
                    it[SuspenseEntries.refundedAt]      = System.currentTimeMillis()
                    it[SuspenseEntries.notes]           = summaryNote
                }
            }

            AuditService.log("suspense_entries", id, "REFUNDED",
                changedBy = refundedBy,
                newValues = "amount=$amount method=$refundMethod reference=$refundReference financialRecordId=$financialRecordId")

            call.respond(HttpStatusCode.OK, mapOf(
                "message"          to "Refund recorded",
                "amount"           to amount.toString(),
                "financialRecordId" to financialRecordId
            ))
        }
    }
}

private fun ResultRow.toSuspenseMap() = mapOf(
    "suspenseId"            to this[SuspenseEntries.suspenseId],
    "projectId"             to this[SuspenseEntries.projectId],
    "unitId"                to this[SuspenseEntries.unitId],
    "unitNumber"            to this[SuspenseEntries.unitNumber],
    "floor"                 to this[SuspenseEntries.floor],
    "unitType"              to this[SuspenseEntries.unitType],
    "originalCustomerName"  to this[SuspenseEntries.originalCustomerName],
    "originalCustomerPhone" to this[SuspenseEntries.originalCustomerPhone],
    "reassignable"          to this[SuspenseEntries.originalCollectionId].isNotBlank().toString(),
    "saleAmount"            to this[SuspenseEntries.saleAmount].toString(),
    "collectedAmount"       to this[SuspenseEntries.collectedAmount].toString(),
    "reason"                to this[SuspenseEntries.reason],
    "revertedBy"            to this[SuspenseEntries.revertedBy],
    "status"                to this[SuspenseEntries.status],
    "refundMethod"          to this[SuspenseEntries.refundMethod],
    "refundReference"       to this[SuspenseEntries.refundReference],
    "refundedBy"            to this[SuspenseEntries.refundedBy],
    "refundedAt"            to this[SuspenseEntries.refundedAt].toString(),
    "notes"                 to this[SuspenseEntries.notes],
    "createdAt"             to this[SuspenseEntries.createdAt].toString()
)

