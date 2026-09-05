package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Users
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.Units
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

// Roles that a user is allowed to grant THEMSELVES via public self-registration.
// Anything privileged (ADMIN, PROJECT_MANAGER, AUDITOR, SALES_REP) must be granted
// afterwards by an existing Admin via PUT /auth/users/{id}/role — never at signup.
// The client-supplied "role" field on /register is intentionally ignored.
private const val SELF_REGISTER_ROLE = "SITE_WORKER"

// Must mirror com.panakam.construction.auth.UserRole in the Android app.
private val VALID_ROLES = setOf(
    "ADMIN", "PROJECT_MANAGER", "SITE_WORKER", "AUDITOR", "SALES_REP", "CUSTOMER"
)

fun Route.authRoutes() {

    route("/auth") {

        // ── POST /auth/register ───────────────────────────────────────────────
        post("/register") {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val name        = json.str("name").trim()
            val phone       = json.str("phone").trim()
            val contactEmail = json.str("contactEmail").trim().lowercase()
            val password    = json.str("password")
            val secQ        = json.str("secQuestion")
            val secA        = json.str("secAnswer").trim().lowercase()

            if (name.isBlank() || phone.isBlank() || password.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Name, phone number required. Password must be ≥ 6 chars."))

            // Check duplicate phone number
            val existing = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.count()
            }
            if (existing > 0)
                return@post call.respond(HttpStatusCode.Conflict,
                    mapOf("error" to "An account with this phone number already exists."))

            // Security: never trust a client-supplied role. Self-registration always
            // creates the lowest-privilege staff account. The ONLY exception is the
            // very first user ever created on a fresh deployment (no users exist yet)
            // — that one bootstraps as ADMIN so there's always someone who can promote
            // everyone else afterwards via User Management.
            val totalUsers = dbQuery { Users.selectAll().count() }
            val role = if (totalUsers == 0L) "ADMIN" else SELF_REGISTER_ROLE

            val userId       = UUID.randomUUID().toString()
            val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
            val answerHash   = if (secA.isNotBlank()) BCrypt.hashpw(secA, BCrypt.gensalt()) else ""

            dbQuery {
                Users.insert {
                    it[Users.userId]        = userId
                    it[Users.name]          = name
                    it[Users.phone]         = phone
                    it[Users.contactEmail]  = contactEmail
                    it[Users.passwordHash]  = passwordHash
                    it[Users.role]          = role
                    it[Users.secQuestion]   = secQ
                    it[Users.secAnswerHash] = answerHash
                    it[Users.createdAt]     = System.currentTimeMillis()
                }
            }

            call.respond(HttpStatusCode.Created, mapOf(
                "userId"       to userId,
                "name"         to name,
                "phone"        to phone,
                "contactEmail" to contactEmail,
                "role"         to role
            ))
        }


        // ── POST /auth/login ──────────────────────────────────────────────────
        post("/login") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone    = json.str("phone").trim()
            val password = json.str("password")

            val row = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No account found with this phone number. Please register first."))

            if (!BCrypt.checkpw(password, row[Users.passwordHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect password. Please try again."))

            // If this staff account is also linked to a Customer record (they
            // personally bought a unit), surface that customer/unit info in the
            // login response too — the Android app uses these same fields (already
            // used for pure customer-portal logins) to show a "My Unit" entry point
            // for this single set of credentials, alongside their staff dashboard.
            val linkedCustomerId = row[Users.linkedCustomerId]
            val linked = if (linkedCustomerId.isNotBlank()) dbQuery {
                Customers.selectAll().where { Customers.customerId eq linkedCustomerId }.singleOrNull()
            } else null

            val response = mutableMapOf(
                "userId"       to row[Users.userId],
                "name"         to row[Users.name],
                "phone"        to row[Users.phone],
                "contactEmail" to row[Users.contactEmail],
                "role"         to row[Users.role]
            )
            if (linked != null) {
                response["customerId"] = linked[Customers.customerId]
                response["unitId"]     = linked[Customers.unitId]
                response["projectId"]  = linked[Customers.projectId]
                response["phone"]      = linked[Customers.phone]
            }
            call.respond(HttpStatusCode.OK, response)
        }

        // ── POST /auth/biometric  (verify account exists, no password check) ──
        post("/biometric") {
            val json  = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone = json.str("phone").trim()

            val row = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "Account not found."))

            val linkedCustomerId = row[Users.linkedCustomerId]
            val linked = if (linkedCustomerId.isNotBlank()) dbQuery {
                Customers.selectAll().where { Customers.customerId eq linkedCustomerId }.singleOrNull()
            } else null

            val response = mutableMapOf(
                "userId"       to row[Users.userId],
                "name"         to row[Users.name],
                "phone"        to row[Users.phone],
                "contactEmail" to row[Users.contactEmail],
                "role"         to row[Users.role]
            )

            if (linked != null) {
                response["customerId"] = linked[Customers.customerId]
                response["unitId"]     = linked[Customers.unitId]
                response["projectId"]  = linked[Customers.projectId]
                response["phone"]      = linked[Customers.phone]
            }
            call.respond(HttpStatusCode.OK, response)
        }

        // ── GET /auth/security-question?phone=... ─────────────────────────────
        get("/security-question") {
            val phone = call.request.queryParameters["phone"]?.trim()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone"))

            val row = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.singleOrNull()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Account not found"))

            val question = row[Users.secQuestion].ifBlank { null }
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "No security question set for this account"))

            call.respond(HttpStatusCode.OK, mapOf("question" to question))
        }

        // ── POST /auth/reset-password ─────────────────────────────────────────
        post("/reset-password") {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone       = json.str("phone").trim()
            val secAnswer   = json.str("secAnswer").trim().lowercase()
            val newPassword = json.str("newPassword")

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Password must be at least 6 characters."))

            val row = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound,
                mapOf("error" to "Account not found."))

            if (!BCrypt.checkpw(secAnswer, row[Users.secAnswerHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect answer. Please try again."))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            dbQuery {
                Users.update({ Users.phone eq phone }) {
                    it[Users.passwordHash] = newHash
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password reset successfully."))
        }

        // ── GET /auth/users  (admin only: list all users) ─────────────────────
        // Requires ?adminId=<callerUserId> — verified server-side to actually be ADMIN.
        get("/users") {
            if (!call.callerIsAdmin())
                return@get call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "Only Admins can view the user list."))

            val users = dbQuery {
                Users.selectAll().orderBy(Users.createdAt).map { row ->
                    val linkedId = row[Users.linkedCustomerId]
                    val linkedCustomer = if (linkedId.isNotBlank())
                        (Customers innerJoin Units).selectAll()
                            .where { Customers.customerId eq linkedId }
                            .firstOrNull()
                    else null
                    mapOf(
                        "userId"           to row[Users.userId],
                        "name"             to row[Users.name],
                        "phone"            to row[Users.phone],
                        "contactEmail"     to row[Users.contactEmail],
                        "role"             to row[Users.role],
                        "createdAt"        to row[Users.createdAt].toString(),
                        "linkedCustomerId" to linkedId,
                        "linkedUnitNumber" to (linkedCustomer?.get(Units.unitNumber) ?: "")
                    )
                }
            }
            call.respond(HttpStatusCode.OK, users)
        }


        // ── PUT /auth/users/{userId}/contact-email  (self or admin) ───────────
        // Sets/updates the staff member's optional contact email — used only for
        // future notifications (payment alerts, digests), NEVER for login.
        // A user may edit their OWN contact email; only Admins may edit others'.
        put("/users/{userId}/contact-email") {
            val userId = call.parameters["userId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            val callerId = call.request.queryParameters["adminId"]
            if (callerId != userId && !call.callerIsAdmin())
                return@put call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "You can only update your own contact email."))

            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val contactEmail = json.str("contactEmail").trim().lowercase()

            dbQuery { Users.update({ Users.userId eq userId }) { it[Users.contactEmail] = contactEmail } }
            call.respond(HttpStatusCode.OK, mapOf(

                "message" to "Contact email updated", "userId" to userId, "contactEmail" to contactEmail
            ))
        }

        // ── PUT /auth/users/{userId}/link-customer  (admin only) ──────────────
        // Links (or unlinks, when customerId is blank) this staff account to an
        // existing Customer record — for staff who ALSO personally own a unit,
        // so ONE login surfaces both their staff dashboard and their own
        // customer/unit info (no second account needed).
        put("/users/{userId}/link-customer") {
            if (!call.callerIsAdmin())
                return@put call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "Only Admins can link user accounts to a customer."))

            val userId = call.parameters["userId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val customerId = json.str("customerId").trim()

            if (customerId.isNotBlank()) {
                val exists = dbQuery {
                    Customers.selectAll().where { Customers.customerId eq customerId }.count()
                } > 0
                if (!exists) return@put call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "Customer record not found"))
            }

            dbQuery { Users.update({ Users.userId eq userId }) { it[Users.linkedCustomerId] = customerId } }
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to (if (customerId.isBlank()) "Unlinked" else "Linked"),
                "userId" to userId, "linkedCustomerId" to customerId
            ))
        }

        // ── PUT /auth/users/{userId}/role  (admin only: change role) ──────────
        put("/users/{userId}/role") {
            if (!call.callerIsAdmin())
                return@put call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "Only Admins can change user roles."))

            val userId = call.parameters["userId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val role = json.str("role").ifBlank {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing role"))
            }
            if (role !in VALID_ROLES)
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid role: $role"))
            dbQuery { Users.update({ Users.userId eq userId }) { it[Users.role] = role } }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Role updated", "userId" to userId, "role" to role))
        }

        // ── DELETE /auth/users/{userId}  (admin only: delete user) ────────────
        delete("/users/{userId}") {
            if (!call.callerIsAdmin())
                return@delete call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "Only Admins can delete users."))

            val userId = call.parameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            dbQuery { Users.deleteWhere { Users.userId eq userId } }
            call.respond(HttpStatusCode.OK, mapOf("message" to "User deleted", "userId" to userId))
        }
    }
}

/**
 * Minimal caller-identity guard used until real session/JWT auth is added.
 * The client must pass the CURRENTLY LOGGED-IN user's own id as ?adminId=...,
 * and that id is looked up server-side to confirm its role is really ADMIN —
 * the client cannot simply claim to be an admin.
 */
private suspend fun io.ktor.server.application.ApplicationCall.callerIsAdmin(): Boolean {
    val callerId = request.queryParameters["adminId"] ?: return false
    val role = dbQuery {
        Users.selectAll().where { Users.userId eq callerId }.singleOrNull()?.get(Users.role)
    }
    return role == "ADMIN"
}

