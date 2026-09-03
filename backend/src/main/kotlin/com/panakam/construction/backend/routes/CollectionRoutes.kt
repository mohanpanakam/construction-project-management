package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
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
import java.util.UUID

fun Route.collectionRoutes() {

    route("/collections") {

        // ── GET /collections?projectId=xxx  (all or filtered by project) ─────
        get {
            val projectId = call.request.queryParameters["projectId"]
            val list = dbQuery {
                val q = if (projectId != null)
                    UnitCollections.selectAll().where { UnitCollections.projectId eq projectId }
                else
                    UnitCollections.selectAll()
                q.orderBy(UnitCollections.createdAt, SortOrder.DESC).map { it.toCollectionMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /collections/summary?projectId=xxx ───────────────────────────
        get("/summary") {
            val projectId = call.request.queryParameters["projectId"]
            val rows = dbQuery {
                var q = UnitCollections.selectAll().where { UnitCollections.status eq "Active" }
                if (projectId != null) q = q.andWhere { UnitCollections.projectId eq projectId }
                q.map { it.toCollectionMap() }
            }
            val totalUnits   = rows.size
            val totalBase    = rows.sumOf { it["baseAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            val totalGst     = rows.sumOf { it["gstAmount"]?.toString()?.toDoubleOrNull()  ?: 0.0 }
            val totalAmount  = rows.sumOf { it["totalAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            val totalPaid    = rows.sumOf { it["paidAmount"]?.toString()?.toDoubleOrNull()  ?: 0.0 }
            val totalPending = rows.sumOf { it["pendingAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            call.respond(HttpStatusCode.OK, mapOf(
                "totalUnits"   to totalUnits.toString(),
                "totalBase"    to totalBase.toString(),
                "totalGst"     to totalGst.toString(),
                "totalAmount"  to totalAmount.toString(),
                "totalPaid"    to totalPaid.toString(),
                "totalPending" to totalPending.toString()
            ))
        }

        // ── GET /collections/summary-by-sales-rep?projectId=xxx ──────────────
        // Groups active collection records by "soldBy" (Admin name or Sales Rep name)
        // so Admin can see total collections received, per sales rep, per project.
        get("/summary-by-sales-rep") {
            val projectId = call.request.queryParameters["projectId"]
            val rows = dbQuery {
                var q = UnitCollections.selectAll().where { UnitCollections.status eq "Active" }
                if (projectId != null) q = q.andWhere { UnitCollections.projectId eq projectId }
                q.map { it.toCollectionMap() }
            }
            val grouped = rows.groupBy { row ->
                row["soldBy"]?.toString()?.trim()?.ifBlank { "Admin" } ?: "Admin"
            }
            val result = grouped.map { (soldBy, list) ->
                val totalAmount  = list.sumOf { it["totalAmount"]?.toString()?.toDoubleOrNull()  ?: 0.0 }
                val totalPaid    = list.sumOf { it["paidAmount"]?.toString()?.toDoubleOrNull()    ?: 0.0 }
                val totalPending = list.sumOf { it["pendingAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
                mapOf(
                    "soldBy"       to soldBy,
                    "totalUnits"   to list.size.toString(),
                    "totalAmount"  to totalAmount.toString(),
                    "totalPaid"    to totalPaid.toString(),
                    "totalPending" to totalPending.toString()
                )
            }.sortedByDescending { it["totalAmount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            call.respond(HttpStatusCode.OK, result)
        }

        // ── GET /collections/project/{projectId} ─────────────────────────────
        get("/project/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                UnitCollections.selectAll()
                    .where { UnitCollections.projectId eq projectId }
                    .orderBy(UnitCollections.createdAt, SortOrder.DESC)
                    .map { it.toCollectionMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /collections/{collectionId} ──────────────────────────────────
        get("/{collectionId}") {
            val id = call.parameters["collectionId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing collectionId"))
            val row = dbQuery {
                UnitCollections.selectAll().where { UnitCollections.collectionId eq id }.singleOrNull()?.toCollectionMap()
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // ── POST /collections ─────────────────────────────────────────────────
        post {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId   = json.str("projectId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId")) }
            val unitId      = json.str("unitId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId")) }

            val collectionId = UUID.randomUUID().toString()
            val sba          = json.str("sba").toDoubleOrNull()          ?: 0.0
            val perSft       = json.str("perSftPrice").toDoubleOrNull()  ?: 0.0
            val gstPct       = json.str("gstPercentage").toDoubleOrNull() ?: 0.0
            val base         = if (json.str("baseAmount").isNotBlank())
                                   json.str("baseAmount").toDoubleOrNull() ?: (perSft * sba)
                               else perSft * sba
            val gstAmt       = if (json.str("gstAmount").isNotBlank())
                                   json.str("gstAmount").toDoubleOrNull() ?: (base * gstPct / 100)
                               else base * gstPct / 100
            val total        = if (json.str("totalAmount").isNotBlank())
                                   json.str("totalAmount").toDoubleOrNull() ?: (base + gstAmt)
                               else base + gstAmt

            dbQuery {
                UnitCollections.insert {
                    it[UnitCollections.collectionId]    = collectionId
                    it[UnitCollections.projectId]       = projectId
                    it[UnitCollections.unitId]          = unitId
                    it[UnitCollections.unitNumber]      = json.str("unitNumber")
                    it[UnitCollections.floor]           = json.str("floor")
                    it[UnitCollections.unitType]        = json.str("unitType")
                    it[UnitCollections.sba]             = sba
                    it[UnitCollections.customerName]    = json.str("customerName")
                    it[UnitCollections.customerPhone]   = json.str("customerPhone")
                    it[UnitCollections.perSftPrice]     = perSft
                    it[UnitCollections.gstPercentage]   = gstPct
                    it[UnitCollections.baseAmount]      = base
                    it[UnitCollections.gstAmount]       = gstAmt
                    it[UnitCollections.totalAmount]     = total
                    it[UnitCollections.paidAmount]      = 0.0
                    it[UnitCollections.pendingAmount]   = total
                    it[UnitCollections.paymentStatus]   = "Unpaid"
                    it[UnitCollections.lastPaymentDate] = ""
                    it[UnitCollections.saleDate]        = json.str("saleDate")
                    it[UnitCollections.soldBy]          = json.str("soldBy")
                    it[UnitCollections.notes]           = json.str("notes")
                    it[UnitCollections.createdAt]       = System.currentTimeMillis()
                }
            }
            AuditService.log("unit_collections", collectionId, "CREATE",
                changedBy = json.str("soldBy"), newValues = json.toString())
            call.respond(HttpStatusCode.Created, mapOf(
                "message"      to "Collection recorded",
                "collectionId" to collectionId
            ))
        }

        // ── PUT /collections/{collectionId} ──────────────────────────────────
        put("/{collectionId}") {
            val id   = call.parameters["collectionId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing collectionId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            dbQuery {
                UnitCollections.update({ UnitCollections.collectionId eq id }) {
                    json.str("customerName").takeIf  { it.isNotBlank() }?.let  { v -> it[UnitCollections.customerName]  = v }
                    json.str("customerPhone").takeIf { it.isNotBlank() }?.let  { v -> it[UnitCollections.customerPhone] = v }
                    json.str("perSftPrice").toDoubleOrNull()?.let               { v -> it[UnitCollections.perSftPrice]   = v }
                    json.str("gstPercentage").toDoubleOrNull()?.let             { v -> it[UnitCollections.gstPercentage] = v }
                    json.str("baseAmount").toDoubleOrNull()?.let                { v -> it[UnitCollections.baseAmount]    = v }
                    json.str("gstAmount").toDoubleOrNull()?.let                 { v -> it[UnitCollections.gstAmount]     = v }
                    json.str("totalAmount").toDoubleOrNull()?.let               { v -> it[UnitCollections.totalAmount]   = v }
                    json.str("saleDate").takeIf { it.isNotBlank() }?.let        { v -> it[UnitCollections.saleDate]      = v }
                    json.str("notes").let                                        { v -> it[UnitCollections.notes]         = v }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Collection updated", "collectionId" to id))
        }

        // ── DELETE /collections/{collectionId} ────────────────────────────────
        delete("/{collectionId}") {
            val id = call.parameters["collectionId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing collectionId"))
            dbQuery { UnitCollections.deleteWhere { UnitCollections.collectionId eq id } }
            AuditService.log("unit_collections", id, "DELETE")
            call.respond(HttpStatusCode.OK, mapOf("message" to "Collection deleted"))
        }
    }
}

private fun ResultRow.toCollectionMap() = mapOf(
    "collectionId"    to this[UnitCollections.collectionId],
    "projectId"       to this[UnitCollections.projectId],
    "unitId"          to this[UnitCollections.unitId],
    "unitNumber"      to this[UnitCollections.unitNumber],
    "floor"           to this[UnitCollections.floor],
    "unitType"        to this[UnitCollections.unitType],
    "sba"             to this[UnitCollections.sba].toString(),
    "customerName"    to this[UnitCollections.customerName],
    "customerPhone"   to this[UnitCollections.customerPhone],
    "perSftPrice"     to this[UnitCollections.perSftPrice].toString(),
    "gstPercentage"   to this[UnitCollections.gstPercentage].toString(),
    "baseAmount"      to this[UnitCollections.baseAmount].toString(),
    "gstAmount"       to this[UnitCollections.gstAmount].toString(),
    "totalAmount"     to this[UnitCollections.totalAmount].toString(),
    "paidAmount"      to this[UnitCollections.paidAmount].toString(),
    "pendingAmount"   to this[UnitCollections.pendingAmount].toString(),
    "paymentStatus"   to this[UnitCollections.paymentStatus],
    "lastPaymentDate" to this[UnitCollections.lastPaymentDate],
    "saleDate"        to this[UnitCollections.saleDate],
    "soldBy"          to this[UnitCollections.soldBy],
    "notes"           to this[UnitCollections.notes],
    "status"          to this[UnitCollections.status],
    "createdAt"       to this[UnitCollections.createdAt].toString()
)

