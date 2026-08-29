package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.SuspenseEntries
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
            val totalCollected  = rows.sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            val holdingAmount   = rows.filter { it["status"] == "Holding" }
                .sumOf { it["collectedAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            call.respond(HttpStatusCode.OK, mapOf(
                "totalEntries"   to totalEntries.toString(),
                "holdingEntries" to holdingEntries.toString(),
                "totalCollected" to totalCollected.toString(),
                "holdingAmount"  to holdingAmount.toString()
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
    "saleAmount"            to this[SuspenseEntries.saleAmount].toString(),
    "collectedAmount"       to this[SuspenseEntries.collectedAmount].toString(),
    "reason"                to this[SuspenseEntries.reason],
    "revertedBy"            to this[SuspenseEntries.revertedBy],
    "status"                to this[SuspenseEntries.status],
    "notes"                 to this[SuspenseEntries.notes],
    "createdAt"             to this[SuspenseEntries.createdAt].toString()
)

