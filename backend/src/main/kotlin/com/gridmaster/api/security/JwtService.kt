package com.gridmaster.api.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.crypto.SecretKey

/**
 * Issues and validates JWTs for single-user game sessions.
 *
 * Tokens carry one claim: `sub` = the stable player UUID. There is no registration
 * flow — the server issues a token on first launch and the client stores it. The
 * secret is configured via [gridmaster.auth.jwt-secret]; an insecure default is
 * provided for local dev and **must** be overridden in any shared deployment.
 */
@Service
class JwtService(
    @Value("\${gridmaster.auth.jwt-secret}") rawSecret: String,
    @Value("\${gridmaster.auth.jwt-expiry-days:30}") private val expiryDays: Long,
) {
    private val signingKey: SecretKey = Keys.hmacShaKeyFor(rawSecret.toByteArray(Charsets.UTF_8))

    /**
     * Issue a JWT for [userId] valid for [expiryDays] days from now.
     * The expiry is a rolling window — clients re-issue via `POST /api/auth/token`
     * when they detect a 401.
     */
    fun issue(userId: String): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expiryDays, ChronoUnit.DAYS)))
            .signWith(signingKey)
            .compact()
    }

    /**
     * Validate [token] and return the [Claims] if valid.
     *
     * @throws JwtException (or subclass) if the token is malformed, tampered, or expired.
     */
    fun parse(token: String): Claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

    /**
     * Extract the userId (`sub` claim) from [token] without throwing.
     * Returns null on any parse failure.
     */
    fun userIdOrNull(token: String): String? = runCatching { parse(token).subject }.getOrNull()
}
