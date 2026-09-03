package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.ProjectSalesReps
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
        post {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val name = json.str("name").trim().ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name required"))
            }
            val salesRepId = UUID.randomUUID().toString()
            dbQuery {
                ProjectSalesReps.insert {
                    it[ProjectSalesReps.salesRepId] = salesRepId
                    it[ProjectSalesReps.projectId]  = projectId
                    it[ProjectSalesReps.name]       = name
                    it[ProjectSalesReps.phone]      = json.str("phone").trim()
                    it[ProjectSalesReps.active]     = true
                    it[ProjectSalesReps.createdAt]  = System.currentTimeMillis()
                    it[ProjectSalesReps.createdBy]  = json.str("createdBy")
                }
            }
            AuditService.log("project_sales_reps", salesRepId, "CREATE",
                changedBy = json.str("createdBy"), newValues = json.toString())
            call.respond(HttpStatusCode.Created, mapOf(
                "message" to "Sales rep added", "salesRepId" to salesRepId
            ))
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
    "salesRepId" to this[ProjectSalesReps.salesRepId],
    "projectId"  to this[ProjectSalesReps.projectId],
    "name"       to this[ProjectSalesReps.name],
    "phone"      to this[ProjectSalesReps.phone],
    "active"     to this[ProjectSalesReps.active].toString(),
    "createdAt"  to this[ProjectSalesReps.createdAt].toString()
)

