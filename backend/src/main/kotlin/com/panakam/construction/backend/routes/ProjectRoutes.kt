package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Projects
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

fun Route.projectRoutes() {

    route("/projects") {

        // GET /projects – list all
        get {
            val rows = dbQuery {
                Projects.selectAll().map { it.toProjectMap() }
            }
            call.respond(HttpStatusCode.OK, rows)
        }

        // GET /projects/{projectId}
        get("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val row = dbQuery {
                Projects.selectAll().where { Projects.projectId eq projectId }
                    .singleOrNull()?.toProjectMap()
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // POST /projects
        post {
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId = json["projectId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            dbQuery {
                Projects.upsert {
                    it[Projects.projectId]   = projectId
                    it[name]         = json.str("name")
                    it[location]     = json.str("location")
                    it[status]       = json.str("status", "Planning")
                    it[startDate]    = json.str("startDate")
                    it[endDate]      = json.str("endDate")
                    it[budget]       = json.str("budget")
                    it[description]  = json.str("description")
                    it[mapLocation]  = json.str("mapLocation")
                    it[partnerName]  = json.str("partnerName")
                    it[partnerPhone] = json.str("partnerPhone")
                    it[partnerEmail] = json.str("partnerEmail")
                    it[projectType]    = json.str("projectType", "Builder Owned")
                    it[landOwnerName]  = json.str("landOwnerName")
                    it[landOwnerShare] = json.str("landOwnerShare")
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("message" to "Project created", "projectId" to projectId))
        }

        // PUT /projects/{projectId}
        put("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            dbQuery {
                Projects.upsert {
                    it[Projects.projectId]   = projectId
                    it[name]         = json.str("name")
                    it[location]     = json.str("location")
                    it[status]       = json.str("status", "Planning")
                    it[startDate]    = json.str("startDate")
                    it[endDate]      = json.str("endDate")
                    it[budget]       = json.str("budget")
                    it[description]  = json.str("description")
                    it[mapLocation]  = json.str("mapLocation")
                    it[partnerName]  = json.str("partnerName")
                    it[partnerPhone] = json.str("partnerPhone")
                    it[partnerEmail] = json.str("partnerEmail")
                    it[projectType]    = json.str("projectType", "Builder Owned")
                    it[landOwnerName]  = json.str("landOwnerName")
                    it[landOwnerShare] = json.str("landOwnerShare")
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Project updated", "projectId" to projectId))
        }

        // DELETE /projects/{projectId}
        delete("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            dbQuery {
                Projects.deleteWhere { Projects.projectId eq projectId }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Project deleted", "projectId" to projectId))
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun ResultRow.toProjectMap() = mapOf(
    "projectId"      to this[Projects.projectId],
    "name"           to this[Projects.name],
    "location"       to this[Projects.location],
    "status"         to this[Projects.status],
    "startDate"      to this[Projects.startDate],
    "endDate"        to this[Projects.endDate],
    "budget"         to this[Projects.budget],
    "description"    to this[Projects.description],
    "mapLocation"    to this[Projects.mapLocation],
    "partnerName"    to this[Projects.partnerName],
    "partnerPhone"   to this[Projects.partnerPhone],
    "partnerEmail"   to this[Projects.partnerEmail],
    "projectType"    to this[Projects.projectType],
    "landOwnerName"  to this[Projects.landOwnerName],
    "landOwnerShare" to this[Projects.landOwnerShare]
)

internal fun JsonObject.str(key: String, default: String = "") =
    this[key]?.jsonPrimitive?.contentOrNull ?: default
