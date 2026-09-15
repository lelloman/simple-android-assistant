package com.lelloman.simpleaiassistant.provider.simpleai

import com.lelloman.simpleaiassistant.model.StreamEvent
import org.junit.Assert.*
import org.junit.Test

class CloudStreamDecoderTest {
    @Test fun textArrivesBeforeCompletion() {
        val decoder = CloudStreamDecoder()
        assertEquals(listOf(StreamEvent.Text("Ciao 🌍")), decoder.accept(
            """{"choices":[{"index":0,"delta":{"content":"Ciao 🌍"}}]}"""
        ))
        assertFalse(decoder.finished)
        decoder.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(listOf(StreamEvent.Done), decoder.accept("[DONE]"))
        assertTrue(decoder.finished)
    }

    @Test fun splitToolsOnlyBecomeExecutableAfterDone() {
        val decoder = CloudStreamDecoder()
        assertTrue(decoder.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"play","arguments":"{\"ids\":["}}]}}]}"""
        ).isEmpty())
        assertTrue(decoder.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a\"],\"shuffle\":true}"}}]},"finish_reason":"tool_calls"}]}"""
        ).isEmpty())
        val events = decoder.accept("[DONE]")
        assertEquals(StreamEvent.ToolUse("call_1", "play", mapOf("ids" to listOf("a"), "shuffle" to true)), events[0])
        assertEquals(StreamEvent.Done, events[1])
    }

    @Test fun supportsCompleteUnindexedOllamaCalls() {
        val decoder = CloudStreamDecoder()
        decoder.accept(
            """{"choices":[{"delta":{"tool_calls":[{"id":"call_0","function":{"name":"play","arguments":"{}"}}]},"finish_reason":"stop"}]}"""
        )
        assertEquals(StreamEvent.ToolUse("call_0", "play", emptyMap<String, Any?>()), decoder.accept("[DONE]")[0])
    }

    @Test fun incompleteAndTruncatedToolsAreRejected() {
        for (reason in listOf("tool_calls", "length")) {
            val decoder = CloudStreamDecoder()
            decoder.accept(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"delete","arguments":"{"}}]},"finish_reason":"$reason"}]}"""
            )
            assertThrows(Exception::class.java) { decoder.accept("[DONE]") }
        }
    }

    @Test fun errorAndMissingFinishNeverReleaseTools() {
        val decoder = CloudStreamDecoder()
        assertEquals(listOf(StreamEvent.Error("Authentication failed: 401")),
            decoder.accept("""{"error":{"message":"Authentication failed: 401"}}"""))
        assertTrue(decoder.finished)
        assertThrows(Exception::class.java) { CloudStreamDecoder().accept("[DONE]") }
    }
}
