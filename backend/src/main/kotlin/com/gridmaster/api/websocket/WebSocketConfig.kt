package com.gridmaster.api.websocket

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * Spring WebSocket + STOMP configuration.
 *
 * Endpoints:
 * - [/ws] WebSocket handshake URL (SockJS fallback enabled)
 *
 * Broker destinations:
 * - [/topic/...] broadcast to all subscribers (server to all clients)
 * - [/queue/...]  per-user messages (server to one client via CommandAck)
 * - [/app/...]    routes to @MessageMapping methods (client to server)
 *
 * Game destinations:
 * - [/topic/session/{sessionId}/state] GameStateUpdate broadcast each tick
 * - [/app/session/{sessionId}/command] player command ingestion
 * - [/queue/session/{sessionId}/ack]   CommandAck back to commanding client
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val authChannelInterceptor: StompAuthChannelInterceptor,
) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry
            .addEndpoint("/ws")
            .setAllowedOriginPatterns("*") // Tightened per deployment via environment config
            .withSockJS()
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // In-memory broker for /topic (broadcast) and /queue (user-specific)
        registry.enableSimpleBroker("/topic", "/queue")
        // Prefix for @MessageMapping methods
        registry.setApplicationDestinationPrefixes("/app")
        // Prefix for user-specific destinations (/queue/...)
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(authChannelInterceptor)
    }
}
