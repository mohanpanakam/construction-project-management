package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Users
import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.security.AUTH_JWT
import com.panakam.construction.backend.security.JwtConfig
import com.panakam.construction.backend.security.currentUserId
import com.panakam.construction.backend.security.requireRole
import com.panakam.construction.backend.security.requireSelfOrRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
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

// Roles an Admin may assign when creating a new staff account via POST /auth/users.
// CUSTOMER is deliberately excluded — customer accounts are created via the
// Customer/Units flow, never here.
private val STAFF_ROLES = setOf("ADMIN", "PROJECT_MANAGER", "SITE_WORKER", "AUDITOR", "SALES_REP")

// Must mirror com.panakam.construction.auth.UserRole in the Android app.
private val VALID_ROLES = setOf(
    "ADMIN", "PROJECT_MANAGER", "SITE_WORKER", "AUDITOR", "SALES_REP", "CUSTOMER"
)

fun Route.authRoutes() {

    route("/auth") {

        // ── POST /auth/register ───────────────────────────────────────────────
        // Public self-registration is intentionally locked down. Historically this
        // let ANYONE who downloaded the app from the Play Store create a Site
        // Worker account and immediately see every project's units — a serious
        // data leak. Now this endpoint only ever succeeds ONCE, to bootstrap the
        // very first Admin account on a brand-new deployment (no users exist
        // yet). Every subsequent staff account must be created by an existing
        // Admin via POST /auth/users, which issues the phone number as a default
        // password and forces a change on first login.
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

            val totalUsers = dbQuery { Users.selectAll().count() }
            if (totalUsers > 0)
                return@post call.respond(HttpStatusCode.Forbidden, mapOf(
                    "error" to "Self-registration is disabled. Staff accounts are created " +
                        "by an Admin (Team → Add Staff). Please contact your Admin to get an account."
                ))

            // Check duplicate phone number
            val existing = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.count()
            }
            if (existing > 0)
                return@post call.respond(HttpStatusCode.Conflict,
                    mapOf("error" to "An account with this phone number already exists."))

            // The very first user ever created on a fresh deployment bootstraps as
            // ADMIN so there's always someone who can create/promote everyone else
            // afterwards via User Management. This branch cannot be reached again
            // once totalUsers > 0 (checked above).
            val role = "ADMIN"

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
                    it[Users.mustChangePassword] = false // they chose their own password
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

        // ── POST /auth/users  (admin only: create a new staff account) ────────
        // Replaces public self-registration. Default password = the staff
        // member's own phone number (same policy as Customers); mustChangePassword
        // is set so the app forces them to pick a real password on first login.
        authenticate(AUTH_JWT) {
        post("/users") {
            if (!call.requireRole("ADMIN")) return@post

            val json  = Json.parseToJsonElement(call.receiveText()).jsonObject
            val name  = json.str("name").trim()
            val phone = json.str("phone").trim()
            val role  = json.str("role").ifBlank { "SITE_WORKER" }
            val contactEmail = json.str("contactEmail").trim().lowercase()

            if (name.isBlank() || phone.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name and phone are required."))
            if (role !in STAFF_ROLES)
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid staff role: $role"))

            val existing = dbQuery { Users.selectAll().where { Users.phone eq phone }.count() }
            if (existing > 0)
                return@post call.respond(HttpStatusCode.Conflict,
                    mapOf("error" to "A staff account with this phone number already exists."))

            val userId       = UUID.randomUUID().toString()
            // Default password = their own phone number, exactly like the Customer
            // flow — never sent back in the response, just told to the Admin so
            // they can relay it, and the user is forced to change it immediately.
            val passwordHash = BCrypt.hashpw(phone, BCrypt.gensalt())

            dbQuery {
                Users.insert {
                    it[Users.userId]             = userId
                    it[Users.name]                = name
                    it[Users.phone]               = phone
                    it[Users.contactEmail]        = contactEmail
                    it[Users.passwordHash]        = passwordHash
                    it[Users.role]                = role
                    it[Users.mustChangePassword]  = true
                    it[Users.createdAt]           = System.currentTimeMillis()
                }
            }

            call.respond(HttpStatusCode.Created, mapOf(
                "userId"        to userId,
                "name"          to name,
                "phone"         to phone,
                "role"          to role,
                "contactEmail"  to contactEmail,
                "defaultPassword" to phone,
                "message"       to "Staff account created. Default password is the phone number ($phone) — they'll be asked to change it on first login."
            ))
        }
        }


        // ── POST /auth/login ──────────────────────────────────────────────────
        post("/login") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone    = json.str("phone").trim()
            val password = json.str("password")

            val row = dbQuery {
                Users.selectAll().where { Users.phone eq phone }.singleOrNull()
            } ?: run {
                // Not a staff account. If this phone number DOES exist as a Customer
                // (e.g. a former staff member — like a deleted Site Worker — who also
                // separately bought a unit), tell them to use the Customer Portal tab
                // instead of just "no account found", which is confusing/misleading
                // when a valid login for them actually does exist, just under a
                // different portal.
                val isCustomer = dbQuery {
                    Customers.selectAll().where { (Customers.phone eq phone) and (Customers.isActive eq true) }.count() > 0
                }
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "error" to if (isCustomer)
                        "No staff account found with this phone number. This phone is registered as a Customer — please switch to \"Customer Portal\" above and sign in there instead."
                    else
                        "No account found with this phone number. Staff accounts are created by an Admin — please contact your Admin."
                ))
            }

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
                "role"         to row[Users.role],
                "mustChangePassword" to row[Users.mustChangePassword].toString(),
                // Signed, server-verified identity — the app must send this back as
                // "Authorization: Bearer <token>" on every subsequent request. The
                // backend re-checks (in Auth.kt's validate block) on every single call
                // that this account still exists, so a deleted user is locked out
                // immediately instead of retaining access until they happen to log out.
                "token"        to JwtConfig.generateToken(row[Users.userId], row[Users.role])
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
                "role"         to row[Users.role],
                "token"        to JwtConfig.generateToken(row[Users.userId], row[Users.role])
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
                    it[Users.mustChangePassword] = false
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password reset successfully."))
        }

        // ── POST /auth/users/{userId}/change-password  (self only) ───────────
        // Used by a staff member on first login (default password = their phone
        // number, mustChangePassword = true) to set a real password, and optionally
        // a security question/answer + contact email for future recovery — mirrors
        // the equivalent Customer flow.
        authenticate(AUTH_JWT) {
        post("/users/{userId}/change-password") {
            val userId = call.parameters["userId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            if (!call.requireSelfOrRole(userId, "ADMIN")) return@post

            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val newPassword = json.str("newPassword")
            val contactEmail = json.str("contactEmail").trim().lowercase()
            val secQ        = json.str("secQuestion")
            val secA        = json.str("secAnswer").trim().lowercase()

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Password must be at least 6 characters."))

            val exists = dbQuery { Users.selectAll().where { Users.userId eq userId }.count() } > 0
            if (!exists) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Account not found."))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            val answerHash = if (secA.isNotBlank()) BCrypt.hashpw(secA, BCrypt.gensalt()) else null

            dbQuery {
                Users.update({ Users.userId eq userId }) {
                    it[Users.passwordHash] = newHash
                    it[Users.mustChangePassword] = false
                    if (contactEmail.isNotBlank()) it[Users.contactEmail] = contactEmail
                    if (secQ.isNotBlank()) it[Users.secQuestion] = secQ
                    if (answerHash != null) it[Users.secAnswerHash] = answerHash
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password updated successfully."))
        }
        }

        // ── GET /auth/users  (admin only: list all users) ─────────────────────
        authenticate(AUTH_JWT) {
        get("/users") {
            if (!call.requireRole("ADMIN")) return@get

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
                        "mustChangePassword" to row[Users.mustChangePassword].toString(),

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
            if (!call.requireSelfOrRole(userId, "ADMIN"))
                return@put

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
            if (!call.requireRole("ADMIN")) return@put

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
            if (!call.requireRole("ADMIN")) return@put

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
            if (!call.requireRole("ADMIN")) return@delete

            val userId = call.parameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            dbQuery { Users.deleteWhere { Users.userId eq userId } }
            call.respond(HttpStatusCode.OK, mapOf("message" to "User deleted", "userId" to userId))
        }
        }
    }
}

