package com.lelloman.simpleaiassistant.provider.ollama

import com.lelloman.simpleaiassistant.model.ChatMessage
import com.lelloman.simpleaiassistant.model.MessageRole
import com.lelloman.simpleaiassistant.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaProviderTest {

    @Test
    fun `message conversion preserves tool conversation metadata`() {
        val provider = OllamaProvider(
            OllamaConfig(baseUrl = "http://localhost", model = "test")
        )
        val messages = listOf(
            ChatMessage(id = "user", role = MessageRole.USER, content = "Weather?"),
            ChatMessage(
                id = "assistant",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall("call-1", "weather", mapOf("city" to "Rome"))
                )
            ),
            ChatMessage(
                id = "tool",
                role = MessageRole.TOOL,
                content = "sunny",
                toolCallId = "call-1",
                toolName = "weather"
            )
        )

        val converted = provider.buildMessageList(messages, "system")

        assertEquals("system", converted[0].role)
        assertEquals("weather", converted[2].toolCalls?.single()?.function?.name)
        assertEquals("call-1", converted[2].toolCalls?.single()?.id)
        assertEquals("Rome", converted[2].toolCalls?.single()?.function?.arguments?.get("city")?.toString()?.trim('"'))
        assertEquals("weather", converted[3].toolName)
        assertEquals("call-1", converted[3].toolCallId)
        assertNull(converted[1].toolCalls)
    }
}
