package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.service.AuditService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

fun Route.customerRoutes() {

    route("/customers") {

        // ── POST /customers/login  (customer portal) ──────────────────────────
        post("/login") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val email    = json.str("email").trim().lowercase()
            val password = json.str("password")

            val row = dbQuery {
                Customers.selectAll()
                    .where { Customers.loginEmail eq email }
                    .singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No customer account found with this email."))

            if (!BCrypt.checkpw(password, row[Customers.passwordHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect password."))

            call.respond(HttpStatusCode.OK, mapOf(
                "customerId" to row[Customers.customerId],
                "name"       to row[Customers.name],
                "email"      to row[Customers.loginEmail],
                "role"       to "CUSTOMER",
                "unitId"     to row[Customers.unitId],
                "projectId"  to row[Customers.projectId]
            ))
        }

        // ── GET /customers/project/{projectId}  ───────────────────────────────
        get("/project/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                Customers.selectAll()
                    .where { Customers.projectId eq projectId }
                    .orderBy(Customers.createdAt, SortOrder.DESC)
                    .map { it.toCustomerMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /customers/unit/{unitId}  ─────────────────────────────────────
        get("/unit/{unitId}") {
            val unitId = call.parameters["unitId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))
            val row = dbQuery {
                Customers.selectAll().where { Customers.unitId eq unitId }.singleOrNull()?.toCustomerMap()
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "No customer for this unit"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // ── GET /customers/{customerId}  ──────────────────────────────────────
        get("/{customerId}") {
            val id = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val row = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.toCustomerMap()
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Customer not found"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // ── POST /customers  ──────────────────────────────────────────────────
        post {
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId  = json.str("projectId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId")) }
            val unitId     = json.str("unitId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId")) }
            val name       = json.str("name").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Customer name required")) }

            val customerId = json.str("customerId").ifBlank { UUID.randomUUID().toString() }
            val loginEmail = json.str("loginEmail").trim().lowercase()
            val password   = json.str("password")
            val pwHash     = if (password.length >= 6) BCrypt.hashpw(password, BCrypt.gensalt()) else ""

            dbQuery {
                Customers.insert {
                    it[Customers.customerId]    = customerId
                    it[Customers.projectId]     = projectId
                    it[Customers.unitId]        = unitId
                    it[Customers.name]          = name
                    it[Customers.address]       = json.str("address")
                    it[Customers.phone]         = json.str("phone")
                    it[Customers.contactEmail]  = json.str("contactEmail")
                    it[Customers.loginEmail]    = loginEmail
                    it[Customers.passwordHash]  = pwHash
                    it[Customers.perSftPrice]   = json.str("perSftPrice").toDoubleOrNull()  ?: 0.0
                    it[Customers.gstPercentage] = json.str("gstPercentage").toDoubleOrNull() ?: 0.0
                    it[Customers.totalCost]     = json.str("totalCost").toDoubleOrNull()    ?: 0.0
                    it[Customers.notes]         = json.str("notes")
                    it[Customers.createdAt]     = System.currentTimeMillis()
                    it[Customers.createdBy]     = json.str("createdBy")
                }
            }
            AuditService.log("customers", customerId, "CREATE",
                changedBy = json.str("createdBy"), newValues = json.toString())
            call.respond(HttpStatusCode.Created, mapOf("message" to "Customer created", "customerId" to customerId))
        }

        // ── PUT /customers/{customerId}  ──────────────────────────────────────
        put("/{customerId}") {
            val id   = call.parameters["customerId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject

            // Fetch old for audit
            val old = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.toCustomerMap()
            }

            dbQuery {
                Customers.update({ Customers.customerId eq id }) {
                    json.str("name").takeIf { it.isNotBlank() }?.let          { v -> it[Customers.name]          = v }
                    json.str("address").let                                    { v -> it[Customers.address]       = v }
                    json.str("phone").let                                      { v -> it[Customers.phone]         = v }
                    json.str("contactEmail").let                               { v -> it[Customers.contactEmail]  = v }
                    json.str("perSftPrice").toDoubleOrNull()?.let              { v -> it[Customers.perSftPrice]   = v }
                    json.str("gstPercentage").toDoubleOrNull()?.let            { v -> it[Customers.gstPercentage] = v }
                    json.str("totalCost").toDoubleOrNull()?.let                { v -> it[Customers.totalCost]     = v }
                    json.str("notes").let                                      { v -> it[Customers.notes]         = v }
                    // Update login credentials only if provided
                    val newLoginEmail = json.str("loginEmail").trim().lowercase()
                    if (newLoginEmail.isNotBlank()) it[Customers.loginEmail] = newLoginEmail
                    val newPw = json.str("password")
                    if (newPw.length >= 6) it[Customers.passwordHash] = BCrypt.hashpw(newPw, BCrypt.gensalt())
                }
            }
            AuditService.log("customers", id, "UPDATE",
                changedBy = json.str("updatedBy"), oldValues = old.toString(), newValues = json.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Customer updated", "customerId" to id))
        }

        // ── DELETE /customers/{customerId}  ───────────────────────────────────
        delete("/{customerId}") {
            val id = call.parameters["customerId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val old = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.toCustomerMap()
            }
            dbQuery { Customers.deleteWhere { Customers.customerId eq id } }
            AuditService.log("customers", id, "DELETE", newValues = old.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Customer deleted"))
        }
    }
}

private fun ResultRow.toCustomerMap() = mapOf(
    "customerId"    to this[Customers.customerId],
    "projectId"     to this[Customers.projectId],
    "unitId"        to this[Customers.unitId],
    "name"          to this[Customers.name],
    "address"       to this[Customers.address],
    "phone"         to this[Customers.phone],
    "contactEmail"  to this[Customers.contactEmail],
    "loginEmail"    to this[Customers.loginEmail],
    "hasPortalAccess" to (this[Customers.loginEmail].isNotBlank() && this[Customers.passwordHash].isNotBlank()).toString(),
    "perSftPrice"   to this[Customers.perSftPrice].toString(),
    "gstPercentage" to this[Customers.gstPercentage].toString(),
    "totalCost"     to this[Customers.totalCost].toString(),
    "notes"         to this[Customers.notes],
    "createdAt"     to this[Customers.createdAt].toString(),
    "createdBy"     to this[Customers.createdBy]
)

