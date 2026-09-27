package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.CustomerAccounts
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.db.UnitCollections
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.security.AUTH_JWT
import com.panakam.construction.backend.security.JwtConfig
import com.panakam.construction.backend.security.currentUserId
import com.panakam.construction.backend.security.currentUserRole
import com.panakam.construction.backend.security.requireRole
import com.panakam.construction.backend.security.requireSelfOrRole
import com.panakam.construction.backend.security.currentUserName
import com.panakam.construction.backend.security.applyPricingRestriction
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

// Roles allowed to see customer identity/pricing/payment details for ANY unit
// (Site Workers and unauthenticated callers must never see this — see the
// CustomerDetailScreen client-side gate this mirrors, now enforced server-side too).
private val CUSTOMER_DATA_ROLES = setOf("ADMIN", "PROJECT_MANAGER", "SALES_REP", "AUDITOR")

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

            val account = dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.loginEmail eq email }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No customer account found with this email."))

            if (!BCrypt.checkpw(password, account[CustomerAccounts.passwordHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect password."))

            // Pick a representative active unit-purchase row for this account to
            // shape the legacy response (customerId/unitId/projectId) — the JWT
            // subject stays a Customers.customerId, unchanged, for compatibility
            // with every existing requireSelfOrRole()/currentUserId() check.
            val phone = account[CustomerAccounts.phone]
            val custRow = dbQuery {
                Customers.selectAll().where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.ASC).firstOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No active unit found for this account."))

            call.respond(HttpStatusCode.OK, mapOf(
                "customerId"        to custRow[Customers.customerId],
                "name"              to custRow[Customers.name],
                "email"             to account[CustomerAccounts.loginEmail],
                "phone"             to phone,
                "role"              to "CUSTOMER",
                "unitId"            to custRow[Customers.unitId],
                "projectId"         to custRow[Customers.projectId],
                "mustChangePassword" to account[CustomerAccounts.mustChangePassword].toString(),
                "token"             to JwtConfig.generateToken(custRow[Customers.customerId], "CUSTOMER")
            ))
        }

        // ── POST /customers/login-phone  (customer portal — by phone) ────────────
        post("/login-phone") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone    = json.str("phone").trim()
            val password = json.str("password")

            if (phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "Phone number required"))

            // One row, one bcrypt check — CustomerAccounts is keyed by phone, so
            // there's no more "try every sibling Customers row's password hash"
            // loop needed (that loop existed only because credentials used to be
            // duplicated per-unit; see CustomerAccounts' doc comment in Tables.kt).
            val account = dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.phone eq phone }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No account found with this phone number."))

            if (account[CustomerAccounts.passwordHash].isBlank() ||
                !BCrypt.checkpw(password, account[CustomerAccounts.passwordHash])
            ) return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Incorrect password."))

            val rows = dbQuery {
                Customers.selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.ASC)
                    .toList()
            }
            if (rows.isEmpty()) return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No active unit found for this account."))
            val authRow = rows.first()

            call.respond(HttpStatusCode.OK, mapOf(
                "phone"             to phone,
                "name"              to authRow[Customers.name],
                "customerId"        to authRow[Customers.customerId],
                "unitId"            to authRow[Customers.unitId],
                "projectId"         to authRow[Customers.projectId],
                "unitCount"         to rows.size.toString(),
                "mustChangePassword" to account[CustomerAccounts.mustChangePassword].toString(),
                "token"             to JwtConfig.generateToken(authRow[Customers.customerId], "CUSTOMER")
            ))
        }

        // ── GET /customers/security-question?phone=...  (forgot password step 1) ──
        get("/security-question") {
            val phone = call.request.queryParameters["phone"]?.trim()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing phone"))

            val account = dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.phone eq phone }.singleOrNull()
            }
            if (account == null || account[CustomerAccounts.secQuestion].isBlank())
                return@get call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "No security question set for this account. Please contact support."))

            call.respond(HttpStatusCode.OK, mapOf("question" to account[CustomerAccounts.secQuestion]))
        }

        // ── POST /customers/reset-password  (forgot password step 2) ─────────────
        post("/reset-password") {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val phone       = json.str("phone").trim()
            val secAnswer   = json.str("secAnswer").trim().lowercase()
            val newPassword = json.str("newPassword")

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Password must be at least 6 characters."))

            val account = dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.phone eq phone }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound,
                mapOf("error" to "Account not found or no security question set."))
            if (account[CustomerAccounts.secQuestion].isBlank())
                return@post call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "Account not found or no security question set."))

            if (!BCrypt.checkpw(secAnswer, account[CustomerAccounts.secAnswerHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect answer. Please try again."))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            dbQuery {
                CustomerAccounts.update({ CustomerAccounts.phone eq phone }) {
                    it[CustomerAccounts.passwordHash]       = newHash
                    // A self-service reset via the security question genuinely
                    // counts as "changed their password" — clear the flag too
                    // (previously left untouched here, a latent inconsistency).
                    it[CustomerAccounts.mustChangePassword] = false
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password reset successfully."))
        }

        // ── GET /customers/by-phone/{phone}  (all units for a phone number) ──────
        // Requires a valid token. Staff (Admin/PM/Sales/Auditor) may look up any
        // phone; a Customer token may only fetch their OWN phone's records — this
        // is real customer/pricing/payment data, never usable by Site Workers or
        // an unauthenticated caller, and never usable by one customer to snoop on
        // another customer's purchase.
        authenticate(AUTH_JWT) {
        get("/by-phone/{phone}") {
            val phone = java.net.URLDecoder.decode(
                call.parameters["phone"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing phone")),
                "UTF-8"
            )
            if (call.currentUserRole() !in CUSTOMER_DATA_ROLES) {
                val ownPhone = dbQuery {
                    Customers.selectAll().where { Customers.customerId eq call.currentUserId() }
                        .firstOrNull()?.get(Customers.phone)
                }
                if (ownPhone != phone)
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to view this."))
            }
            val role    = call.currentUserRole()
            val repName = if (role == "SALES_REP") currentUserName(call.currentUserId()) else ""
            val list = dbQuery {
                (Customers innerJoin Units)
                    .selectAll()
                    .where { (Customers.phone eq phone) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.ASC)
                    .map { row ->
                        val collection = activeCollectionFor(row[Customers.unitId])
                        val totalCost  = collection?.get(UnitCollections.totalAmount) ?: row[Customers.totalCost]
                        val soldBy     = collection?.get(UnitCollections.soldBy) ?: ""
                        val raw = mapOf(
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
                            "paymentStatus" to (collection?.get(UnitCollections.paymentStatus) ?: "Unpaid"),
                            "discountAmount" to (collection?.get(UnitCollections.discountAmount)?.toString() ?: "0.0"),
                            "discountReason" to (collection?.get(UnitCollections.discountReason) ?: "")
                        )
                        applyPricingRestriction(raw, role, repName, soldBy)
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /customers/project/{projectId}  ───────────────────────────────
        // Staff only (Admin/PM/Sales/Auditor) — this is customer identity + pricing
        // data for an entire project; Site Workers and customers must never see it.
        get("/project/{projectId}") {
            if (!call.requireRole(*CUSTOMER_DATA_ROLES.toTypedArray())) return@get
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val role    = call.currentUserRole()
            val repName = if (role == "SALES_REP") currentUserName(call.currentUserId()) else ""
            val list = dbQuery {
                Customers.join(CustomerAccounts, JoinType.LEFT, onColumn = Customers.phone, otherColumn = CustomerAccounts.phone)
                    .selectAll()
                    .where { (Customers.projectId eq projectId) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.DESC)
                    .map { row ->
                        val soldBy = activeCollectionFor(row[Customers.unitId])?.get(UnitCollections.soldBy) ?: ""
                        applyPricingRestriction(row.toCustomerMap(), role, repName, soldBy)
                    }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // ── GET /customers/unit/{unitId}  ─────────────────────────────────────
        // Staff (Admin/PM/Sales/Auditor) may view any unit's customer; a Customer
        // token may only view the record for THEIR OWN unit. Site Workers and
        // unauthenticated callers get 401/403 — this is the exact endpoint that
        // previously let a deleted/unauthorized user keep seeing customer name,
        // phone, pricing and payment status for any unit.
        get("/unit/{unitId}") {
            val unitId = call.parameters["unitId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId"))
            if (call.currentUserRole() !in CUSTOMER_DATA_ROLES) {
                val ownUnitId = dbQuery {
                    Customers.selectAll().where { Customers.customerId eq call.currentUserId() }
                        .firstOrNull()?.get(Customers.unitId)
                }
                if (ownUnitId != unitId)
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to view this."))
            }
            val role    = call.currentUserRole()
            val repName = if (role == "SALES_REP") currentUserName(call.currentUserId()) else ""
            val row = dbQuery {
                Customers.join(CustomerAccounts, JoinType.LEFT, onColumn = Customers.phone, otherColumn = CustomerAccounts.phone)
                    .selectAll()
                    .where { (Customers.unitId eq unitId) and (Customers.isActive eq true) }
                    .orderBy(Customers.createdAt, SortOrder.DESC)
                    .firstOrNull()
                    ?.toCustomerMap()
                    ?.let { m -> enrichWithCollection(m, unitId) }
                    ?.let { m -> applyPricingRestriction(m, role, repName, activeCollectionFor(unitId)?.get(UnitCollections.soldBy) ?: "") }
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "No customer for this unit"))
            else             call.respond(HttpStatusCode.OK, row)
        }

        // ── GET /customers/{customerId}  ──────────────────────────────────────
        get("/{customerId}") {
            val id = call.parameters["customerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            if (!call.requireSelfOrRole(id, *CUSTOMER_DATA_ROLES.toTypedArray())) return@get
            val role    = call.currentUserRole()
            val repName = if (role == "SALES_REP") currentUserName(call.currentUserId()) else ""
            val row = dbQuery {
                Customers.join(CustomerAccounts, JoinType.LEFT, onColumn = Customers.phone, otherColumn = CustomerAccounts.phone)
                    .selectAll().where { Customers.customerId eq id }.singleOrNull()
                    ?.toCustomerMap()
                    ?.let { m -> enrichWithCollection(m, m["unitId"]?.toString() ?: "") }
                    ?.let { m -> applyPricingRestriction(m, role, repName, activeCollectionFor(m["unitId"] ?: "")?.get(UnitCollections.soldBy) ?: "") }
            }
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Customer not found"))
            else             call.respond(HttpStatusCode.OK, row)
        }
        } // end authenticate(AUTH_JWT)

        // ── POST /customers  (Admin/PM/Sales only — create a customer record) ──
        authenticate(AUTH_JWT) {
        post {
            if (!call.requireRole("ADMIN", "PROJECT_MANAGER", "SALES_REP")) return@post
            val json       = Json.parseToJsonElement(call.receiveText()).jsonObject
            val projectId  = json.str("projectId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId")) }
            val unitId     = json.str("unitId").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing unitId")) }
            val name       = json.str("name").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Customer name required")) }

            val customerId = json.str("customerId").ifBlank { UUID.randomUUID().toString() }
            val phone      = json.str("phone").trim()

            // Ensure a CustomerAccounts row exists for this phone — reuse it if the
            // phone already has an account (this person owns/owned another unit),
            // otherwise create a fresh one with a default password (their own phone
            // number) forcing a first-login change, exactly like the old
            // per-Customers-row logic used to, but now there's only ever ONE row
            // to create/reuse per phone instead of one per unit.
            val existingAccount = if (phone.isNotBlank()) dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.phone eq phone }.singleOrNull()
            } else null

            if (phone.isNotBlank() && existingAccount == null) {
                val password = json.str("password")
                val effectivePassword = if (password.length >= 6) password else phone
                val mustChangePw = password.length < 6
                val pwHash = if (effectivePassword.length >= 6) BCrypt.hashpw(effectivePassword, BCrypt.gensalt()) else ""
                dbQuery {
                    CustomerAccounts.insert {
                        it[CustomerAccounts.phone]              = phone
                        it[CustomerAccounts.loginEmail]         = json.str("loginEmail").trim().lowercase()
                        it[CustomerAccounts.contactEmail]       = json.str("contactEmail")
                        it[CustomerAccounts.passwordHash]       = pwHash
                        it[CustomerAccounts.mustChangePassword] = mustChangePw
                        it[CustomerAccounts.createdAt]          = System.currentTimeMillis()
                    }
                }
            }

            dbQuery {
                Customers.insert {
                    it[Customers.customerId]       = customerId
                    it[Customers.projectId]        = projectId
                    it[Customers.unitId]           = unitId
                    it[Customers.name]             = name
                    it[Customers.address]          = json.str("address")
                    it[Customers.phone]            = phone
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

        // ── PUT /customers/{customerId}  (Admin/PM/Sales only) ────────────────
        put("/{customerId}") {
            if (!call.requireRole("ADMIN", "PROJECT_MANAGER", "SALES_REP")) return@put
            val id   = call.parameters["customerId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject

            val old = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.toCustomerMapUnitOnly()
            }
            val currentPhone = old?.get("phone").orEmpty()

            // Unit-scoped fields only — stays on Customers.
            dbQuery {
                Customers.update({ Customers.customerId eq id }) {
                    json.str("name").takeIf { it.isNotBlank() }?.let          { v -> it[Customers.name]          = v }
                    json.str("address").let                                    { v -> it[Customers.address]       = v }
                    json.str("phone").let                                      { v -> it[Customers.phone]         = v }
                    json.str("perSftPrice").toDoubleOrNull()?.let              { v -> it[Customers.perSftPrice]   = v }
                    json.str("gstPercentage").toDoubleOrNull()?.let            { v -> it[Customers.gstPercentage] = v }
                    json.str("totalCost").toDoubleOrNull()?.let                { v -> it[Customers.totalCost]     = v }
                    json.str("notes").let                                      { v -> it[Customers.notes]         = v }
                }
            }

            // Login-credential fields go to the ONE CustomerAccounts row for the
            // customer's CURRENT phone (before any change above) — no more
            // "propagate to every sibling row sharing a phone" dance needed, since
            // there's only ever one account row per phone now.
            val newLoginEmail = json.str("loginEmail").trim().lowercase()
            val newPw         = json.str("password")
            if (currentPhone.isNotBlank() && (newLoginEmail.isNotBlank() || newPw.length >= 6)) {
                val accountExists = dbQuery {
                    CustomerAccounts.selectAll().where { CustomerAccounts.phone eq currentPhone }.count() > 0
                }
                if (accountExists) {
                    dbQuery {
                        CustomerAccounts.update({ CustomerAccounts.phone eq currentPhone }) {
                            if (newLoginEmail.isNotBlank()) it[CustomerAccounts.loginEmail] = newLoginEmail
                            if (newPw.length >= 6) {
                                it[CustomerAccounts.passwordHash]       = BCrypt.hashpw(newPw, BCrypt.gensalt())
                                it[CustomerAccounts.mustChangePassword] = false
                            }
                        }
                    }
                } else {
                    // Data-consistency fallback (shouldn't normally happen): no
                    // account row exists yet for this phone — create one.
                    dbQuery {
                        CustomerAccounts.insert {
                            it[CustomerAccounts.phone]              = currentPhone
                            it[CustomerAccounts.loginEmail]         = newLoginEmail
                            it[CustomerAccounts.passwordHash]       = if (newPw.length >= 6) BCrypt.hashpw(newPw, BCrypt.gensalt()) else ""
                            it[CustomerAccounts.mustChangePassword] = false
                            it[CustomerAccounts.createdAt]          = System.currentTimeMillis()
                        }
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
        // Updates the ONE CustomerAccounts row for this customer's phone — no
        // propagation logic needed at all anymore (fixed 2026-09-26; see
        // CustomerAccounts' doc comment in Tables.kt for why this used to be buggy).
        post("/{customerId}/change-password") {
            val id   = call.parameters["customerId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            // Only the customer themselves (their own verified token) or an Admin
            // may change this password — never an anonymous/unauthenticated caller.
            if (!call.requireSelfOrRole(id, "ADMIN")) return@post
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val newPassword  = json.str("newPassword")
            val contactEmail = json.str("contactEmail").trim().lowercase()
            val secQuestion  = json.str("secQuestion")
            val secAnswer    = json.str("secAnswer").trim().lowercase()

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 6 characters"))

            val phone = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.get(Customers.phone)
            }.orEmpty()
            if (phone.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Customer has no phone on record"))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            val answerHash = if (secQuestion.isNotBlank() && secAnswer.isNotBlank())
                BCrypt.hashpw(secAnswer, BCrypt.gensalt()) else null

            val accountExists = dbQuery {
                CustomerAccounts.selectAll().where { CustomerAccounts.phone eq phone }.count() > 0
            }
            if (accountExists) {
                dbQuery {
                    CustomerAccounts.update({ CustomerAccounts.phone eq phone }) {
                        it[CustomerAccounts.passwordHash]       = newHash
                        it[CustomerAccounts.mustChangePassword] = false
                        if (contactEmail.isNotBlank()) it[CustomerAccounts.contactEmail] = contactEmail
                        if (answerHash != null) {
                            it[CustomerAccounts.secQuestion]   = secQuestion
                            it[CustomerAccounts.secAnswerHash] = answerHash
                        }
                    }
                }
            } else {
                dbQuery {
                    CustomerAccounts.insert {
                        it[CustomerAccounts.phone]              = phone
                        it[CustomerAccounts.passwordHash]       = newHash
                        it[CustomerAccounts.mustChangePassword] = false
                        it[CustomerAccounts.contactEmail]       = contactEmail
                        if (answerHash != null) {
                            it[CustomerAccounts.secQuestion]   = secQuestion
                            it[CustomerAccounts.secAnswerHash] = answerHash
                        }
                        it[CustomerAccounts.createdAt]           = System.currentTimeMillis()
                    }
                }
            }
            AuditService.log("customers", id, "CHANGE_PASSWORD", changedBy = id)

            call.respond(HttpStatusCode.OK, mapOf("message" to "Password changed successfully"))
        }


        // ── DELETE /customers/{customerId}  (Admin only) ──────────────────────
        // Deletes only this unit-purchase row. The CustomerAccounts row (login
        // credentials) is intentionally left alone — it may still be in use by
        // other units this same phone number owns, and even if this was their
        // last unit, keeping it around is harmless (just an unused login).
        delete("/{customerId}") {
            if (!call.requireRole("ADMIN")) return@delete
            val id = call.parameters["customerId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing customerId"))
            val old = dbQuery {
                Customers.selectAll().where { Customers.customerId eq id }.singleOrNull()?.toCustomerMapUnitOnly()
            }
            dbQuery { Customers.deleteWhere { Customers.customerId eq id } }
            AuditService.log("customers", id, "DELETE", newValues = old.toString())
            call.respond(HttpStatusCode.OK, mapOf("message" to "Customer deleted"))
        }
        } // end authenticate(AUTH_JWT) for writes
    }
}

/** Unit-purchase fields only — no CustomerAccounts join (used where a login/
 *  credentials view isn't needed, e.g. audit-log snapshots). */
private fun ResultRow.toCustomerMapUnitOnly() = mapOf(
    "customerId"   to this[Customers.customerId],
    "projectId"    to this[Customers.projectId],
    "unitId"       to this[Customers.unitId],
    "name"         to this[Customers.name],
    "address"      to this[Customers.address],
    "phone"        to this[Customers.phone],
    "perSftPrice"  to this[Customers.perSftPrice].toString(),
    "gstPercentage" to this[Customers.gstPercentage].toString(),
    "totalCost"    to this[Customers.totalCost].toString(),
    "isActive"     to this[Customers.isActive].toString(),
    "notes"        to this[Customers.notes],
    "createdAt"    to this[Customers.createdAt].toString(),
    "createdBy"    to this[Customers.createdBy]
)

/** Full customer map INCLUDING login/credential fields — call only on a row from
 *  a query that left-joined `Customers` with `CustomerAccounts` on phone (see the
 *  GET endpoints above); falls back to blank/default credential values if this
 *  particular row had no matching CustomerAccounts row (e.g. blank phone). */
private fun ResultRow.toCustomerMap(): Map<String, String> {
    val loginEmail   = getOrNull(CustomerAccounts.loginEmail).orEmpty()
    val contactEmail = getOrNull(CustomerAccounts.contactEmail).orEmpty()
    val passwordHash = getOrNull(CustomerAccounts.passwordHash).orEmpty()
    val mustChange   = getOrNull(CustomerAccounts.mustChangePassword) ?: true
    return toCustomerMapUnitOnly() + mapOf(
        "contactEmail"       to contactEmail,
        "loginEmail"         to loginEmail,
        "hasPortalAccess"    to (loginEmail.isNotBlank() && passwordHash.isNotBlank()).toString(),
        "mustChangePassword" to mustChange.toString()
    )
}

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
        "paymentStatus" to (collection?.get(UnitCollections.paymentStatus) ?: "Unpaid"),
        "discountAmount" to (collection?.get(UnitCollections.discountAmount)?.toString() ?: "0.0"),
        "discountReason" to (collection?.get(UnitCollections.discountReason) ?: "")
    )
}

