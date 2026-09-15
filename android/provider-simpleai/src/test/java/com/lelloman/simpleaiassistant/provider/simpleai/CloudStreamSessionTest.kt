package com.lelloman.simpleaiassistant.provider.simpleai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class CloudStreamSessionTest {
    @Test fun stoppingCollectionCancelsRemoteAndIgnoresLateCallbacks() = runBlocking {
        var cancelled = 0
        lateinit var callback: (String) -> Unit
        val events = cloudStreamSession(start = { onData, _ ->
            callback = onData
            onData("""{"choices":[{"delta":{"content":"hello"}}]}""")
        }, cancel = { cancelled++ }).take(1).toList()
        assertEquals(1, events.size)
        assertEquals(1, cancelled)
        callback("""{"choices":[{"delta":{"content":"late"}}]}""")
        assertEquals(1, events.size)
    }

    @Test fun timeoutCancelsRemote() = runBlocking {
        var cancelled = false
        try {
            cloudStreamSession(timeoutMillis = 25, start = { _, _ -> },
                cancel = { cancelled = true }).collect()
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue(cancelled)
        }
    }

    @Test fun serviceDeathFailsAndCleansUp() = runBlocking {
        var cancelled = false
        try {
            cloudStreamSession(start = { _, onFailure ->
                onFailure(IllegalStateException("disconnected"))
            }, cancel = { cancelled = true }).collect()
            fail("Expected disconnection")
        } catch (e: IllegalStateException) {
            assertEquals("disconnected", e.message)
            assertTrue(cancelled)
        }
    }
}
