package com.panakam.construction.backend.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

/** Name used everywhere `authenticate(AUTH_JWT) { ... }` is applied. */
const val AUTH_JWT = "auth-jwt"

/** Installs the JWT authentication provider. Call once from Application.module(). */
fun Application.configureAuth() {
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = JwtConfig.realm
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                if (!userId.isNullOrBlank()) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing authentication token"))
            }
        }
    }
}

/** The verified caller's own userId (or customerId, for customer-portal tokens), from the JWT — never from client-supplied body/query fields. */
fun ApplicationCall.currentUserId(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString().orEmpty()

/** The verified caller's role (e.g. ADMIN, PROJECT_MANAGER, SALES_REP, AUDITOR, SITE_WORKER, CUSTOMER), from the JWT. */
fun ApplicationCall.currentUserRole(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString().orEmpty()

/**
 * Responds 403 Forbidden and returns false if the caller's verified role isn't
 * one of [roles]; otherwise returns true. Callers should `if (!call.requireRole(...)) return@x`.
 */
suspend fun ApplicationCall.requireRole(vararg roles: String): Boolean {
    if (currentUserRole() !in roles) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to perform this action."))
        return false
    }
    return true
}

/**
 * Like [requireRole], but also allows the request through when the caller's own
 * verified userId matches [selfId] (a user acting on their own record) — used for
 * self-service endpoints (e.g. a customer confirming their own KYC, a staff member
 * editing their own contact email).
 */
suspend fun ApplicationCall.requireSelfOrRole(selfId: String, vararg roles: String): Boolean {
    if (currentUserId() == selfId) return true
    return requireRole(*roles)
}

