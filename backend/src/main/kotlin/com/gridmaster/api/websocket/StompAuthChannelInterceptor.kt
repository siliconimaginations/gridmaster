package com.gridmaster.api.websocket

import com.gridmaster.api.security.JwtService
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component

/**
 * Validates the JWT on every STOMP CONNECT frame.
 *
 * The client includes the token in the STOMP `Authorization` header:
 * ```
 * CONNECT
 * Authorization: Bearer <jwt>
 * ```
 *
 * On success: injects a [UsernamePasswordAuthenticationToken] as the message's
 * user principal so Spring can route `/user/...` destinations correctly.
 *
 * On failure: throws [org.springframework.security.access.AccessDeniedException]
 * which Spring converts to an error frame — effectively closing the connection.
 */
@Component
class StompAuthChannelInterceptor(
    private val jwtService: JwtService,
) : ChannelInterceptor {
    private val log = LoggerFactory.getLogger(StompAuthChannelInterceptor::class.java)

    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*> {
        val accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
                ?: return message

        if (accessor.command != StompCommand.CONNECT) return message

        val rawHeader = accessor.getFirstNativeHeader("Authorization")
        val token =
            rawHeader
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        if (token == null) {
            log.warn("STOMP CONNECT rejected — missing Authorization header")
            throw org.springframework.security.access.AccessDeniedException(
                "Missing Authorization header on STOMP CONNECT",
            )
        }

        val userId = jwtService.userIdOrNull(token)
        if (userId == null) {
            log.warn("STOMP CONNECT rejected — invalid or expired JWT")
            throw org.springframework.security.access.AccessDeniedException(
                "Invalid or expired JWT on STOMP CONNECT",
            )
        }

        // Inject the authenticated principal so Spring's user-destination resolver
        // can route /user/queue/... messages back to this specific client.
        accessor.user =
            UsernamePasswordAuthenticationToken(
                userId,
                null,
                listOf(SimpleGrantedAuthority("ROLE_PLAYER")),
            )

        log.debug("STOMP CONNECT authenticated: userId={}", userId)
        return message
    }
}
