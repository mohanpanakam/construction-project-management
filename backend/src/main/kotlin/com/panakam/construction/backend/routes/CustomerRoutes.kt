package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.db.UnitCollections
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

fun Route.customerRoutes() {

    route("/customers") {

        // ── GET /customers/search?q=...  (admin: find a customer to link a staff
        // user account to — matches by name, phone or unit number, capped to 20) ──
        get("/search") {
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            if (q.isBlank()) return@get call.respond(HttpStatusCode.OK, emptyList<Map<String, String>>())
            val like = "%${q.lowercase()}%"
            val list = dbQuery {
                (Customers innerJoin Units)
                    .selectAll()
                    .where {
                        (Customers.name.lowerCase() like like) or
                        (Customers.phone.lowerCase() like like) or
                        (Units.unitNumber.lowerCase() like like)
                    }
                    .limit(20)
                    .map { row ->
                        mapOf(
                            "customerId" to row[Customers.customerId],
                            "name"       to row[Customers.name],
                            "phone"      to row[Customers.phone],
                            "unitNumber" to row[Units.unitNumber],
                            "projectId"  to row[Customers.projectId],
                            "unitId"     to row[Customers.unitId]
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── POST /customers/login  (customer portal — by email) ──────────────────
        post("/login") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val email    = json.str("email").trim().lowercase()
            val password = json.str("password")

            val row = dbQuery {
                Customers.selectAll()
                    .where { (Customers.loginEmail eq email) and (Customers.isActive eq true) }
                    .singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No customer account found with this email."))

            if (!BCrypt.checkpw(password, row[Customers.passwordHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect password."))

            call.respond(HttpStatusCode.OK, mapOf(
                "customerId"        to row[Customers.customerId],
                "name"              to row[Customers.name],
                "email"             to row[Customers.loginEmail],
                "phone"             to row[Customers.phone],
                "role"              to "CUSTOMER",
                "unitId"            to row[Customers.unitId],
                "projectId"         to row[Customers.projectId],
                "mustChangePassword" to row[Customers.mustChangePassword].toString()
            ))
        }

        // ── POST /customers/login-phone  (customer portal — by phone) ────────────
        post("/login-phone") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone    = json.str("phone").trim()
            val password = json.str("password")

            if (phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "Phone number required"))

            val rows = dbQuery {
                Customers.selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.ASC)
                    .toList()
            }

            if (rows.isEmpty()) return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No account found with this phone number."))

            val authRow = rows.firstOrNull { r ->
                r[Customers.passwordHash].isNotBlank() &&
                BCrypt.checkpw(password, r[Customers.passwordHash])
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "Incorrect password."))

            call.respond(HttpStatusCode.OK, mapOf(
                "phone"             to phone,
                "name"              to authRow[Customers.name],
                "customerId"        to authRow[Customers.customerId],
                "unitId"            to authRow[Customers.unitId],
                "projectId"         to authRow[Customers.projectId],
                "unitCount"         to rows.size.toString(),
                "mustChangePassword" to authRow[Customers.mustChangePassword].toString()
            ))
        }

        // ── GET /customers/security-question?phone=...  (forgot password step 1) ──
        // Uses whichever row for this phone has a security question set (all rows
        // for the same phone share the same recovery info — see change-password).
        get("/security-question") {
            val phone = call.request.queryParameters["phone"]?.trim()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone"))

            val row = dbQuery {
                Customers.selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .firstOrNull { it[Customers.secQuestion].isNotBlank() }
            } ?: return@get call.respond(HttpStatusCode.NotFound,
                mapOf("error" to "No security question set for this account. Please contact support."))

            call.respond(HttpStatusCode.OK, mapOf("question" to row[Customers.secQuestion]))
        }

        // ── POST /customers/reset-password  (forgot password step 2) ─────────────
        // Verifies the security answer, then resets the password for ALL rows
        // sharing this phone number (multi-unit customers use one shared login).
        post("/reset-password") {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone       = json.str("phone").trim()
            val secAnswer   = json.str("secAnswer").trim().lowercase()
            val newPassword = json.str("newPassword")

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Password must be at least 6 characters."))

            val row = dbQuery {
                Customers.selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .firstOrNull { it[Customers.secQuestion].isNotBlank() }
            } ?: return@post call.respond(HttpStatusCode.NotFound,
                mapOf("error" to "Account not found or no security question set."))

            if (!BCrypt.checkpw(secAnswer, row[Customers.secAnswerHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect answer. Please try again."))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            dbQuery {
                Customers.update({ Customers.phone eq phone }) {
                    it[Customers.passwordHash] = newHash
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password reset successfully."))
        }

        // ── GET /customers/by-phone/{phone}  (all units for a phone number) ──────
        get("/by-phone/{phone}") {
            val phone = java.net.URLDecoder.decode(
                call.parameters["phone"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing phone")),
                "UTF-8"
            )
            val list = dbQuery {
                (Customers innerJoin Units)
                    .selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.ASC)
                    .map { row ->
                        val collection = activeCollectionFor(row[Customers.unitId])
                        val totalCost  = collection?.get(UnitCollections.totalAmount) ?: row[Customers.totalCost]
                        mapOf(
                            "customerId"    to row[Customers.customerId],
                            "projectId"     to row[Customers.projectId],
                            "unitId"        to row[Customers.unitId],
                            "name"          to row[Customers.name],
                            "unitNumber"    to row[Units.unitNumber],
                            "floor"         to row[Units.floor],
                            "type"          to row[Units.type],
                            "sba"           to (collection?.get(UnitCollections.sba)?.toString() ?: row[Units.sba].toString()),
                            "unitStatus"    to row[Units.status],
                            "availability"  to row[Units.availability],
                            "perSftPrice"   to row[Customers.perSftPrice].toString(),
                            "totalCost"     to totalCost.toString(),
                            "totalAmount"   to totalCost.toString(),
                            "paidAmount"    to (collection?.get(UnitCollections.paidAmount)?.toString()    ?: "0.0"),
                            "pendingAmount" to (collection?.get(UnitCollections.pendingAmount)?.toString() ?: totalCost.toString()),
                            "paymentStatus" to (collection?.get(UnitCollections.paymentStatus) ?: "Unpaid")
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /customers/project/{projectId}  ───────────────────────────────
        get("/project/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val list = dbQuery {
                Customers.selectAll()
                    .where { (Customers.projectId eq projectId) and (Customers.isActive eq true) }
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
                Customers.selectAll()
                    .where { (Customers.unitId eq unitId) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.DESC)
                    .firstOrNull()
                    ?.toCustomerMap()
                    ?.let { m -> enrichWithCollection(m, unitId) }
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
                    ?.let { m -> enrichWithCollection(m, m["unitId"]?.toString() ?: "") }
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

            // If same phone already registered, reuse existing login credentials
            val phone = json.str("phone").trim()
            val existingByPhone = if (phone.isNotBlank()) dbQuery {
                Customers.selectAll().where { Customers.phone eq phone }.firstOrNull()
            } else null

            val loginEmail = when {
                existingByPhone != null -> existingByPhone[Customers.loginEmail]
                else -> json.str("loginEmail").trim().lowercase()
            }
            val password = json.str("password")
            // If no password given (or too short), use phone as default password
            val effectivePassword = if (password.length >= 6) password else phone.ifBlank { password }
            val mustChangePw: Boolean
            val pwHash = when {
                existingByPhone != null && existingByPhone[Customers.passwordHash].isNotBlank() -> {
                    mustChangePw = existingByPhone[Customers.mustChangePassword]
                    existingByPhone[Customers.passwordHash]
                }
                effectivePassword.length >= 6 -> {
                    mustChangePw = password.length < 6  // true when we fell back to phone
                    BCrypt.hashpw(effectivePassword, BCrypt.gensalt())
                }
                else -> {
                    mustChangePw = true
                    ""
                }
            }

            dbQuery {
                Customers.insert {
                    it[Customers.customerId]       = customerId
                    it[Customers.projectId]        = projectId
                    it[Customers.unitId]           = unitId
                    it[Customers.name]             = name
                    it[Customers.address]          = json.str("address")
                    it[Customers.phone]            = json.str("phone")
                    it[Customers.contactEmail]     = json.str("contactEmail")
                    it[Customers.loginEmail]       = loginEmail
                    it[Customers.passwordHash]     = pwHash
                    it[Customers.mustChangePassword] = mustChangePw
                    it[Customers.perSftPrice]      = json.str("perSftPrice").toDoubleOrNull()  ?: 0.0
                    it[Customers.gstPercentage]    = json.str("gstPercentage").toDoubleOrNull() ?: 0.0
                    it[Customers.totalCost]        = json.str("totalCost").toDoubleOrNull()    ?: 0.0
                    it[Customers.isActive]         = true
                    it[Customers.notes]            = json.str("notes")
                    it[Customers.createdAt]        = System.currentTimeMillis()
                    it[Customers.createdBy]        = json.str("createdBy")
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
                    if (newPw.length >= 6) {
                        it[Customers.passwordHash]      = BCrypt.hashpw(newPw, BCrypt.gensalt())
                        it[Customers.mustChangePassword] = false
                    }
                }
            }
            AuditService.log("customers", id, "UPDATE",
                changedBy = json.str("updatedBy"), oldValues = old.toString(), newValues = json.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Customer updated", "customerId" to id))
        }

        // ── POST /customers/{customerId}/change-password  ────────────────────
        // Also used for the mandatory first-login flow — accepts optional
        // contactEmail/secQuestion/secAnswer so a customer can set up their
        // forgot-password recovery info at the same time as their new password.
        // Security Q&A + contact email are propagated to ALL Customers rows that
        // share the same phone number (one person may have bought multiple units).
        post("/{customerId}/change-password") {
            val id   = call.parameters["customerId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val newPassword  = json.str("newPassword")
            val contactEmail = json.str("contactEmail").trim().lowercase()
            val secQuestion  = json.str("secQuestion")
            val secAnswer    = json.str("secAnswer").trim().lowercase()

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 6 characters"))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            dbQuery {
                Customers.update({ Customers.customerId eq id }) {
                    it[Customers.passwordHash]       = newHash
                    it[Customers.mustChangePassword] = false
                    if (contactEmail.isNotBlank()) it[Customers.contactEmail] = contactEmail
                }
            }

            // Propagate security Q&A (and contact email) to sibling rows sharing the same phone.
            if (secQuestion.isNotBlank() && secAnswer.isNotBlank()) {
                val phone = dbQuery {
                    Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.get(Customers.phone)
                } ?: ""
                val answerHash = BCrypt.hashpw(secAnswer, BCrypt.gensalt())
                if (phone.isNotBlank()) {
                    dbQuery {
                        Customers.update({ Customers.phone eq phone }) {
                            it[Customers.secQuestion]   = secQuestion
                            it[Customers.secAnswerHash] = answerHash
                            if (contactEmail.isNotBlank()) it[Customers.contactEmail] = contactEmail
                        }
                    }
                } else {
                    dbQuery {
                        Customers.update({ Customers.customerId eq id }) {
                            it[Customers.secQuestion]   = secQuestion
                            it[Customers.secAnswerHash] = answerHash
                        }
                    }
                }
            }
            AuditService.log("customers", id, "CHANGE_PASSWORD", changedBy = id)

            call.respond(HttpStatusCode.OK, mapOf("message" to "Password changed successfully"))
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
    "customerId"        to this[Customers.customerId],
    "projectId"         to this[Customers.projectId],
    "unitId"            to this[Customers.unitId],
    "name"              to this[Customers.name],
    "address"           to this[Customers.address],
    "phone"             to this[Customers.phone],
    "contactEmail"      to this[Customers.contactEmail],
    "loginEmail"        to this[Customers.loginEmail],
    "hasPortalAccess"   to (this[Customers.loginEmail].isNotBlank() && this[Customers.passwordHash].isNotBlank()).toString(),
    "mustChangePassword" to this[Customers.mustChangePassword].toString(),
    "perSftPrice"       to this[Customers.perSftPrice].toString(),
    "gstPercentage"     to this[Customers.gstPercentage].toString(),
    "totalCost"         to this[Customers.totalCost].toString(),
    "isActive"          to this[Customers.isActive].toString(),
    "notes"             to this[Customers.notes],
    "createdAt"         to this[Customers.createdAt].toString(),
    "createdBy"         to this[Customers.createdBy]
)

/** Finds the current active (non-reverted) sale record for a unit, if any. */
private fun activeCollectionFor(unitId: String) =
    UnitCollections.selectAll()
        .where { (UnitCollections.unitId eq unitId) and (UnitCollections.status eq "Active") }
        .orderBy(UnitCollections.createdAt, SortOrder.DESC)
        .firstOrNull()

/**
 * Enriches a customer map with the accurate, reconciled sale figures
 * (SBA, totalAmount, paidAmount, pendingAmount, paymentStatus) from the linked
 * UnitCollections record — the source of truth kept in sync with payments and
 * unit edits. Falls back to the Customer's own cached totalCost when no
 * collection record exists yet.
 */
private fun enrichWithCollection(customerMap: Map<String, String>, unitId: String): Map<String, String> {
    val collection = if (unitId.isNotBlank()) activeCollectionFor(unitId) else null
    val fallbackTotal = customerMap["totalCost"] ?: "0.0"
    val totalAmount = collection?.get(UnitCollections.totalAmount)?.toString() ?: fallbackTotal
    return customerMap + mapOf(
        "sba"           to (collection?.get(UnitCollections.sba)?.toString() ?: (customerMap["sba"] ?: "0")),
        "totalCost"     to totalAmount,
        "totalAmount"   to totalAmount,
        "paidAmount"    to (collection?.get(UnitCollections.paidAmount)?.toString()    ?: "0.0"),
        "pendingAmount" to (collection?.get(UnitCollections.pendingAmount)?.toString() ?: totalAmount),
        "paymentStatus" to (collection?.get(UnitCollections.paymentStatus) ?: "Unpaid")
    )
}

