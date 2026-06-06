package com.gridmaster.api

import com.gridmaster.api.dto.IssueTokenRequest
import com.gridmaster.api.dto.TokenResponse
import com.gridmaster.api.security.JwtService
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Issues JWTs for single-user game authentication.
 *
 * No registration or password required — the server mints a stable UUID for the
 * player on first call and returns a signed JWT. The client stores both and re-presents
 * them on subsequent launches. If the token expires the client calls this endpoint again
 * with the saved [IssueTokenRequest.userId] to get a fresh token.
 *
 * This endpoint is public (no auth required — see [SecurityConfig]).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val jwtService: JwtService,
    @Value("\${gridmaster.auth.jwt-expiry-days:30}") private val expiryDays: Long,
) {
    /**
     * Issue (or re-issue) a JWT.
     *
     * - If [IssueTokenRequest.userId] is provided and non-blank, a token is issued for
     *   that user (the client is responsible for providing the correct stable UUID).
     * - If omitted, a new UUID is generated (first-launch flow).
     */
    @PostMapping("/token")
    fun issueToken(
        @Valid @RequestBody(required = false) body: IssueTokenRequest?,
    ): ResponseEntity<TokenResponse> {
        val userId =
            body?.userId?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
        val token = jwtService.issue(userId)
        return ResponseEntity.ok(
            TokenResponse(
                token = token,
                userId = userId,
                expiresInDays = expiryDays,
            ),
        )
    }
}
