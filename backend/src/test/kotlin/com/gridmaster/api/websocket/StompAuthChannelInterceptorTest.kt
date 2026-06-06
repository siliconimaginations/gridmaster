package com.gridmaster.api.websocket

import com.gridmaster.api.security.JwtService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.access.AccessDeniedException

/**
 * Unit tests for [StompAuthChannelInterceptor].
 */
class StompAuthChannelInterceptorTest {
    private lateinit var interceptor: StompAuthChannelInterceptor
    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        jwtService = mockk()
        interceptor = StompAuthChannelInterceptor(jwtService)
    }

    @Test
    fun `valid JWT on CONNECT sets user principal`() {
        every { jwtService.userIdOrNull("valid-token") } returns "user-123"

        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        accessor.addNativeHeader("Authorization", "Bearer valid-token")
        accessor.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        val result = interceptor.preSend(message, mockk(relaxed = true))

        val resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor::class.java)!!
        assertThat(resultAccessor.user?.name).isEqualTo("user-123")
    }

    @Test
    fun `missing Authorization header on CONNECT throws AccessDeniedException`() {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        accessor.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        assertThatThrownBy {
            interceptor.preSend(message, mockk(relaxed = true))
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `invalid JWT on CONNECT throws AccessDeniedException`() {
        every { jwtService.userIdOrNull("bad-token") } returns null

        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT)
        accessor.addNativeHeader("Authorization", "Bearer bad-token")
        accessor.setLeaveMutable(true)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        assertThatThrownBy {
            interceptor.preSend(message, mockk(relaxed = true))
        }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `non-CONNECT frame passes through without auth check`() {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE)
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)

        // Should not throw even without Authorization header
        val result = interceptor.preSend(message, mockk(relaxed = true))
        assertThat(result).isNotNull
    }
}
