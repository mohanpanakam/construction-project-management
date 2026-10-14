package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.ProjectSalesReps
import com.panakam.construction.backend.db.Users
import com.panakam.construction.backend.security.AUTH_JWT
import com.panakam.construction.backend.security.currentUserId
import com.panakam.construction.backend.service.AuditService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

/**
 * Admin manages a lightweight list of "Sales Reps" (sales guys) per project.
 * These are simple attribution records used when selling a unit (dropdown selection)
 * and for grouping the Collections report by who sold each unit — they are NOT
 * full app login accounts.
 */
fun Route.salesRepRoutes() {

    route("/projects/{projectId}/sales-reps") {

        // ── GET /projects/{projectId}/sales-reps  (active reps for a project) ────
        get {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                ProjectSalesReps.selectAll()
                    .where { (ProjectSalesReps.projectId eq projectId) and (ProjectSalesReps.active eq true) }
                    .orderBy(ProjectSalesReps.name, SortOrder.ASC)
                    .map { it.toSalesRepMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── POST /projects/{projectId}/sales-reps  (add a new sales rep) ─────────
        // Optionally accepts a "userId" (an existing staff account's Users.userId,
        // normally with role SALES_REP) to LINK this attribution entry to a real
        // login account — this is the authoritative way a Sales Rep's own
        // payment/collections history gets correctly scoped to them later (see
        // security/SalesRepScope.kt's soldByMatchesCaller), immune to the name
        // typed here ever silently drifting out of sync with that account's own
        // Users.name. When a userId is given, `name`/`phone` are taken from that
        // account's own record (never trusting client-supplied values for an
        // account that already has verified identity) rather than from the body.
        authenticate(AUTH_JWT) {
        post {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val linkedUserId = json.str("userId").trim()

            val (name, phone) = if (linkedUserId.isNotBlank()) {
                val userRow = dbQuery {
                    Users.selectAll().where { (Users.userId eq linkedUserId) and (Users.role eq "SALES_REP") }.firstOrNull()
                } ?: return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No Sales Rep staff account found for that userId"))
                userRow[Users.name] to userRow[Users.phone]
            } else {
                val typedName = json.str("name").trim().ifBlank {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name required"))
                }
                typedName to json.str("phone").trim()
            }

            val actorId = call.currentUserId()
            val salesRepId = UUID.randomUUID().toString()
            dbQuery {
                ProjectSalesReps.insert {
                    it[ProjectSalesReps.salesRepId]    = salesRepId
                    it[ProjectSalesReps.projectId]     = projectId
                    it[ProjectSalesReps.name]          = name
                    it[ProjectSalesReps.phone]         = phone
                    it[ProjectSalesReps.linkedUserId]  = linkedUserId
                    it[ProjectSalesReps.active]        = true
                    it[ProjectSalesReps.createdAt]     = System.currentTimeMillis()
                    it[ProjectSalesReps.createdBy]     = actorId
                }
            }
            AuditService.log("project_sales_reps", salesRepId, "CREATE",
                changedBy = actorId, newValues = json.toString())
            call.respond(HttpStatusCode.Created, mapOf(
                "message" to "Sales rep added", "salesRepId" to salesRepId
            ))
        }
        }

        // ── DELETE /projects/{projectId}/sales-reps/{salesRepId}  (deactivate) ────
        delete("/{salesRepId}") {
            val projectId  = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val salesRepId = call.parameters["salesRepId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing salesRepId"))
            dbQuery {
                ProjectSalesReps.update({ (ProjectSalesReps.projectId eq projectId) and (ProjectSalesReps.salesRepId eq salesRepId) }) {
                    it[ProjectSalesReps.active] = false
                }
            }
            AuditService.log("project_sales_reps", salesRepId, "DEACTIVATE")
            call.respond(HttpStatusCode.OK, mapOf("message" to "Sales rep removed"))
        }
    }
}

private fun ResultRow.toSalesRepMap() = mapOf(
    "salesRepId"   to this[ProjectSalesReps.salesRepId],
    "projectId"    to this[ProjectSalesReps.projectId],
    "name"         to this[ProjectSalesReps.name],
    "phone"        to this[ProjectSalesReps.phone],
    "linkedUserId" to this[ProjectSalesReps.linkedUserId],
    "active"       to this[ProjectSalesReps.active].toString(),
    "createdAt"    to this[ProjectSalesReps.createdAt].toString(),
    "createdBy"    to this[ProjectSalesReps.createdBy]
)

