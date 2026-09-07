package com.panakam.construction.backend.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

/**
 * Mints and verifies signed JWTs that carry the caller's verified identity
 * (userId + role). Replaces the previous approach of trusting client-supplied
 * "createdBy"/"changedBy"/"adminId" fields, which any authenticated (or even
 * unauthenticated) caller could spoof to impersonate another user in audit logs.
 *
 * IMPORTANT: JWT_SECRET MUST be set to a long random value in production via
 * environment variable. The fallback below is for local dev only and is NOT safe
 * to use in any deployed environment — anyone who knows it can forge tokens.
 */
object JwtConfig {
    private val secret = System.getenv("JWT_SECRET") ?: "dev-insecure-secret-change-me"
    const val issuer = "construction-backend"
    const val audience = "construction-app"
    const val realm = "construction"

    private const val EXPIRY_MILLIS = 12 * 60 * 60 * 1000L // 12 hours

    private val algorithm = Algorithm.HMAC256(secret)

    /** Generates a signed token embedding the verified userId + role claims. */
    fun generateToken(userId: String, role: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + EXPIRY_MILLIS))
            .sign(algorithm)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()
}

