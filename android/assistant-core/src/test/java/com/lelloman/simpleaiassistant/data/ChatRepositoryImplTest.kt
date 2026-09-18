package com.lelloman.simpleaiassistant.data

import com.lelloman.simpleaiassistant.engine.*
import com.lelloman.simpleaiassistant.llm.LlmProvider
import com.lelloman.simpleaiassistant.mode.AssistantMode
import com.lelloman.simpleaiassistant.model.*
import com.lelloman.simpleaiassistant.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real JNI engine with deterministic host effects. */
class ChatRepositoryImplTest {
    private class Provider(private val stream: () -> Flow<StreamEvent>) : LlmProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override fun streamChat(messages: List<ChatMessage>, tools: List<ToolSpec>, systemPrompt: String) = stream()
        override suspend fun testConnection() = Result.success(Unit)
        override suspend fun listModels() = Result.success(emptyList<String>())
    }
    private val config = AssistantConfig(
        AssistantMode("main", "Main", "", toolIds = setOf("read"), children = listOf(AssistantMode("other", "Other", "", promptInstructions = "Other prompt"))),
        listOf(ToolSpec("read", "Read", mapOf("type" to "object"))),
    )
    private suspend fun awaitIdle(s: AssistantSession) = withTimeout(5000) { s.state.first { it.activity == "idle" } }
    @Test fun nativeToolTurnAndModeRestart() = runBlocking {
        var round = 0
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = AssistantSession.create(config, Provider { flow {
            if(round++ == 0) emit(StreamEvent.ToolUse("call", "read", emptyMap())) else emit(StreamEvent.Text("done"))
            emit(StreamEvent.Done)
        } }, { calls++; ToolResult(true, "ok") }, scope = scope)
        try {
            s.setLanguage("en"); s.send("hi"); awaitIdle(s)
            assertEquals(1, calls); assertEquals(4, s.state.value.messages.size)
            val id = s.state.value.messages.first().id
            s.switchMode("other"); s.restart(id); awaitIdle(s)
            assertEquals("main", s.state.value.modeId); assertEquals(2, s.state.value.messages.size)
        } finally { s.close(); scope.cancel() }
    }
    @Test fun clearCancelsStreamAndPreventsStaleTools() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = AssistantSession.create(config, Provider { flow {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } }, { calls++; ToolResult(true) }, scope = scope)
        try {
            s.setLanguage("en"); s.send("private"); withTimeout(5000) { entered.await() }; s.clear()
            withTimeout(5000) { cancelled.await() }
            assertTrue(s.state.value.messages.isEmpty()); assertEquals(0, calls)
        } finally { s.close(); scope.cancel() }
    }
    @Test fun changedModeKeepsHistoryUntilSelection() = runBlocking {
        val store = MemoryHistoryStore(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = Provider { flowOf(StreamEvent.Text("done"), StreamEvent.Done) }
        val first = AssistantSession.create(config, provider, { ToolResult(true) }, store, scope)
        first.setLanguage("en"); first.send("old"); awaitIdle(first); first.close()
        val second = AssistantSession.create(config.copy(rootMode = config.rootMode.copy(promptInstructions = "Updated")), provider, { ToolResult(true) }, store, scope)
        try {
            second.restart(second.state.value.messages.first().id)
            val choice = second.state.value.restartChoice!!
            assertEquals(2, second.state.value.messages.size)
            second.confirmRestart("other", choice.revision); awaitIdle(second)
            assertEquals("other", second.state.value.modeId)
        } finally { second.close(); scope.cancel() }
    }
    @Test fun persistenceFailurePreventsDispatch() = runBlocking {
        var fail = false; var calls = 0
        val store = object : AssistantHistoryStore {
            override suspend fun load(): String? = null
            override suspend fun save(archive: String) { if(fail) error("disk full") }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = AssistantSession.create(config, Provider { calls++; flowOf(StreamEvent.Done) }, { ToolResult(true) }, store, scope)
        try {
            s.setLanguage("en"); fail = true
            try { s.send("hi"); fail("Expected storage failure") } catch (e: IllegalStateException) { assertEquals("disk full", e.message) }
            assertEquals(0, calls)
        } finally { s.close(); scope.cancel() }
    }
    @Test fun diagnosticsFollowNativeTurnsAndClearRevokesSnapshots() = runBlocking {
        val storage = object : com.lelloman.simpleaiassistant.diagnostics.DiagnosticStorage {
            var content: String? = null
            override fun read(maxBytes: Int) = content
            override fun write(content: String) { this.content = content }
            override fun clear() { content = null }
        }
        val recorder = com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder(storage)
        recorder.setAccount("owner", true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var round = 0
        val s = AssistantSession.create(config, Provider { flow {
            if (round++ == 0) emit(StreamEvent.ToolUse("call", "read", mapOf("password" to "private-value")))
            else emit(StreamEvent.Text("answer"))
            emit(StreamEvent.Done)
        } }, { ToolResult(true, "result") }, scope = scope, diagnostics = recorder)
        try {
            s.setLanguage("en")
            s.send("hello")
            awaitIdle(s)
            val responseId = s.state.value.messages.last().id
            val snapshot = recorder.snapshot(messageId = responseId)!!
            assertTrue(snapshot.content.contains("completed"))
            assertTrue(snapshot.content.contains("tool_call"))
            assertTrue(snapshot.content.contains("tool_result"))
            assertTrue(snapshot.content.contains("answer"))
            assertFalse(snapshot.content.contains("private-value"))
            s.clear()
            assertNull(recorder.snapshot())
            assertFalse(recorder.isCurrent(snapshot))
        } finally { s.close(); scope.cancel() }
    }

}
