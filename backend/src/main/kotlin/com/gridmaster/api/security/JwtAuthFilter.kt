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
 *
 * Runs on both initial and async dispatches. Spring MVC wraps Kotlin [suspend]
 * controller methods in an async dispatch cycle (via [kotlinx.coroutines.reactor]);
 * the default [OncePerRequestFilter] behaviour skips async re-dispatches, which
 * leaves the [SecurityContextHolder] empty on the async thread and causes Spring
 * Security to return 401 for all suspend-based endpoints despite a valid JWT.
 * Overriding [shouldNotFilterAsyncDispatch] to return `false` ensures the filter
 * re-establishes the authentication context on every dispatch type.
 */
@Component
class JwtAuthFilter(private val jwtService: JwtService) : OncePerRequestFilter() {
    /**
     * Run this filter on async dispatches as well as initial requests.
     *
     * Spring MVC executes Kotlin suspend controller functions via an async dispatch
     * (Servlet 3.0 `AsyncContext`). By default, [OncePerRequestFilter] skips
     * async re-dispatches, which leaves [SecurityContextHolder] empty and causes
     * Spring Security to return 401 even when the original request carried a valid
     * Bearer JWT. Returning `false` here makes [doFilterInternal] run on async
     * dispatches so that the JWT is re-validated and the security context is
     * re-populated before the authorization check fires.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

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
