package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Users
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

fun Route.authRoutes() {

    route("/auth") {

        // ── POST /auth/register ───────────────────────────────────────────────
        post("/register") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val name     = json.str("name").trim()
            val email    = json.str("email").trim().lowercase()
            val password = json.str("password")
            val role     = json.str("role", "SITE_WORKER")
            val secQ     = json.str("secQuestion")
            val secA     = json.str("secAnswer").trim().lowercase()

            if (name.isBlank() || email.isBlank() || password.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Name, email required. Password must be ≥ 6 chars."))

            // Check duplicate email
            val existing = dbQuery {
                Users.selectAll().where { Users.email eq email }.count()
            }
            if (existing > 0)
                return@post call.respond(HttpStatusCode.Conflict,
                    mapOf("error" to "An account with this email already exists."))

            val userId       = UUID.randomUUID().toString()
            val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
            val answerHash   = if (secA.isNotBlank()) BCrypt.hashpw(secA, BCrypt.gensalt()) else ""

            dbQuery {
                Users.insert {
                    it[Users.userId]        = userId
                    it[Users.name]          = name
                    it[Users.email]         = email
                    it[Users.passwordHash]  = passwordHash
                    it[Users.role]          = role
                    it[Users.secQuestion]   = secQ
                    it[Users.secAnswerHash] = answerHash
                    it[Users.createdAt]     = System.currentTimeMillis()
                }
            }

            call.respond(HttpStatusCode.Created, mapOf(
                "userId" to userId,
                "name"   to name,
                "email"  to email,
                "role"   to role
            ))
        }

        // ── POST /auth/login ──────────────────────────────────────────────────
        post("/login") {
            val json     = Json.parseToJsonElement(call.receiveText()).jsonObject
            val email    = json.str("email").trim().lowercase()
            val password = json.str("password")

            val row = dbQuery {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "No account found with this email. Please register first."))

            if (!BCrypt.checkpw(password, row[Users.passwordHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect password. Please try again."))

            call.respond(HttpStatusCode.OK, mapOf(
                "userId" to row[Users.userId],
                "name"   to row[Users.name],
                "email"  to row[Users.email],
                "role"   to row[Users.role]
            ))
        }

        // ── POST /auth/biometric  (verify account exists, no password check) ──
        post("/biometric") {
            val json  = Json.parseToJsonElement(call.receiveText()).jsonObject
            val email = json.str("email").trim().lowercase()

            val row = dbQuery {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("error" to "Account not found."))

            call.respond(HttpStatusCode.OK, mapOf(
                "userId" to row[Users.userId],
                "name"   to row[Users.name],
                "email"  to row[Users.email],
                "role"   to row[Users.role]
            ))
        }

        // ── GET /auth/security-question?email=... ─────────────────────────────
        get("/security-question") {
            val email = call.request.queryParameters["email"]?.trim()?.lowercase()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing email"))

            val row = dbQuery {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Account not found"))

            val question = row[Users.secQuestion].ifBlank { null }
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    mapOf("error" to "No security question set for this account"))

            call.respond(HttpStatusCode.OK, mapOf("question" to question))
        }

        // ── POST /auth/reset-password ─────────────────────────────────────────
        post("/reset-password") {
            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val email       = json.str("email").trim().lowercase()
            val secAnswer   = json.str("secAnswer").trim().lowercase()
            val newPassword = json.str("newPassword")

            if (newPassword.length < 6)
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Password must be at least 6 characters."))

            val row = dbQuery {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            } ?: return@post call.respond(HttpStatusCode.NotFound,
                mapOf("error" to "Account not found."))

            if (!BCrypt.checkpw(secAnswer, row[Users.secAnswerHash]))
                return@post call.respond(HttpStatusCode.Unauthorized,
                    mapOf("error" to "Incorrect answer. Please try again."))

            val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            dbQuery {
                Users.update({ Users.email eq email }) {
                    it[Users.passwordHash] = newHash
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Password reset successfully."))
        }

        // ── GET /auth/users  (admin: list all users) ──────────────────────────
        get("/users") {
            val users = dbQuery {
                Users.selectAll().orderBy(Users.createdAt).map {
                    mapOf(
                        "userId"    to it[Users.userId],
                        "name"      to it[Users.name],
                        "email"     to it[Users.email],
                        "role"      to it[Users.role],
                        "createdAt" to it[Users.createdAt].toString()
                    )
                }
            }
            call.respond(HttpStatusCode.OK, users)
        }

        // ── PUT /auth/users/{userId}/role  (admin: change role) ───────────────
        put("/users/{userId}/role") {
            val userId = call.parameters["userId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val role = json.str("role").ifBlank {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing role"))
            }
            dbQuery { Users.update({ Users.userId eq userId }) { it[Users.role] = role } }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Role updated", "userId" to userId, "role" to role))
        }

        // ── DELETE /auth/users/{userId}  (admin: delete user) ─────────────────
        delete("/users/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing userId"))
            dbQuery { Users.deleteWhere { Users.userId eq userId } }
            call.respond(HttpStatusCode.OK, mapOf("message" to "User deleted", "userId" to userId))
        }
    }
}

