package com.gridmaster.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Extracts a Bearer JWT from the `Authorization` header and, if valid, populates
 * the [SecurityContextHolder] with an authenticated [UsernamePasswordAuthenticationToken].
 *
 * Requests without a valid token pass through unauthenticated; Spring Security's
 * [SecurityConfig] then decides whether to reject them with 401.
 */
@Component
class JwtAuthFilter(private val jwtService: JwtService) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val bearerToken =
            request.getHeader("Authorization")
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")

        if (bearerToken != null) {
            val userId = jwtService.userIdOrNull(bearerToken)
            if (userId != null && SecurityContextHolder.getContext().authentication == null) {
                val auth =
                    UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_USER")),
                    )
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        chain.doFilter(request, response)
    }
}
