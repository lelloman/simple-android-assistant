package com.lelloman.simpleaiassistant.data

import com.lelloman.simpleaiassistant.data.local.ChatMessageDao
import com.lelloman.simpleaiassistant.data.local.ChatMessageEntity
import com.lelloman.simpleaiassistant.llm.LlmProvider
import com.lelloman.simpleaiassistant.model.ChatMessage
import com.lelloman.simpleaiassistant.model.Language
import com.lelloman.simpleaiassistant.model.MessageRole
import com.lelloman.simpleaiassistant.model.StreamEvent
import com.lelloman.simpleaiassistant.tool.Tool
import com.lelloman.simpleaiassistant.tool.ToolNode
import com.lelloman.simpleaiassistant.tool.ToolRegistry
import com.lelloman.simpleaiassistant.tool.ToolResult
import com.lelloman.simpleaiassistant.tool.ToolSpec
import com.lelloman.simpleaiassistant.util.AuthErrorHandler
import com.lelloman.simpleaiassistant.util.LanguagePreferences
import com.lelloman.simpleaiassistant.util.StringProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryImplTest {

    @Test
    fun `streams and persists one complete assistant response`() = runBlocking {
        val dao = FakeChatMessageDao()
        val provider = QueueProvider(
            listOf(StreamEvent.Text("Hello "), StreamEvent.Text("world"), StreamEvent.Done)
        )
        val repository = repository(dao, provider)

        repository.sendMessage("Hi")

        val messages = dao.domainMessages()
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("Hello world", messages.last().content)
        assertFalse(repository.isStreaming.value)
        assertEquals("", repository.streamingText.value)
    }

    @Test
    fun `provider error is terminal and does not save an empty response`() = runBlocking {
        val dao = FakeChatMessageDao()
        val provider = QueueProvider(
            listOf(
                StreamEvent.Text("partial"),
                StreamEvent.Error("network down"),
                StreamEvent.Text("must be ignored"),
                StreamEvent.Done
            )
        )
        val repository = repository(dao, provider)

        repository.sendMessage("Hi")

        val messages = dao.domainMessages()
        assertEquals(2, messages.size)
        assertEquals("Error: network down", messages.last().content)
    }

    @Test
    fun `authentication is refreshed and retried once`() = runBlocking {
        val dao = FakeChatMessageDao()
        val provider = QueueProvider(
            listOf(StreamEvent.Error("Authentication failed: expired")),
            listOf(StreamEvent.Text("Recovered"), StreamEvent.Done)
        )
        var refreshes = 0
        val repository = repository(
            dao = dao,
            provider = provider,
            authErrorHandler = AuthErrorHandler {
                refreshes++
                true
            }
        )

        repository.sendMessage("Hi")

        assertEquals(1, refreshes)
        assertEquals(2, provider.calls)
        assertEquals("Recovered", dao.domainMessages().last().content)
    }

    @Test
    fun `tool is resolved by advertised name and receives valid input`() = runBlocking {
        val dao = FakeChatMessageDao()
        var receivedInput: Map<String, Any?>? = null
        val tool = RecordingTool { input ->
            receivedInput = input
            ToolResult(success = true, data = "sunny")
        }
        val provider = QueueProvider(
            listOf(
                StreamEvent.ToolUse("call-1", "weather", mapOf("city" to "Rome")),
                StreamEvent.Done
            ),
            listOf(StreamEvent.Text("It is sunny."), StreamEvent.Done)
        )
        val registry = ToolRegistry(
            tools = mapOf("host-weather-id" to tool),
            topography = listOf(ToolNode.ToolRef("host-weather-id"))
        )
        val repository = repository(dao, provider, registry)

        repository.sendMessage("Weather?")

        assertEquals(mapOf("city" to "Rome"), receivedInput)
        val messages = dao.domainMessages()
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("call-1", messages[2].toolCallId)
        assertEquals("weather", messages[2].toolName)
        assertEquals("sunny", messages[2].content)
    }

    @Test
    fun `invalid tool input is returned to model without executing tool`() = runBlocking {
        val dao = FakeChatMessageDao()
        var executed = false
        val tool = RecordingTool {
            executed = true
            ToolResult(success = true)
        }
        val provider = QueueProvider(
            listOf(
                StreamEvent.ToolUse("call-1", "weather", emptyMap()),
                StreamEvent.Done
            ),
            listOf(StreamEvent.Text("Please provide a city."), StreamEvent.Done)
        )
        val repository = repository(
            dao,
            provider,
            ToolRegistry(
                tools = mapOf("weather" to tool),
                topography = listOf(ToolNode.ToolRef("weather"))
            )
        )

        repository.sendMessage("Weather?")

        assertFalse(executed)
        val toolResult = dao.domainMessages().first { it.role == MessageRole.TOOL }
        assertTrue(toolResult.content.contains("missing required properties: city"))
    }

    @Test
    fun `multiple tool calls execute sequentially before the follow-up`() = runBlocking {
        val dao = FakeChatMessageDao()
        val executions = mutableListOf<String>()
        val weather = RecordingTool("weather") {
            executions += "weather"
            ToolResult(success = true, data = "sunny")
        }
        val clock = RecordingTool("clock") {
            executions += "clock"
            ToolResult(success = true, data = "12:00")
        }
        val provider = QueueProvider(
            listOf(
                StreamEvent.ToolUse("call-1", "weather", mapOf("city" to "Rome")),
                StreamEvent.ToolUse("call-2", "clock", mapOf("city" to "Rome")),
                StreamEvent.Done
            ),
            listOf(StreamEvent.Text("Sunny at noon."), StreamEvent.Done)
        )
        val repository = repository(
            dao,
            provider,
            ToolRegistry(
                tools = mapOf("weather-id" to weather, "clock-id" to clock),
                topography = listOf(
                    ToolNode.ToolRef("weather-id"),
                    ToolNode.ToolRef("clock-id")
                )
            )
        )

        repository.sendMessage("Weather and time?")

        assertEquals(listOf("weather", "clock"), executions)
        assertEquals(
            listOf("call-1", "call-2"),
            dao.domainMessages().filter { it.role == MessageRole.TOOL }.map { it.toolCallId }
        )
        assertEquals(2, provider.calls)
    }

    @Test
    fun `tool exceptions become tool results instead of aborting conversation`() = runBlocking {
        val dao = FakeChatMessageDao()
        val tool = RecordingTool { throw IllegalStateException("sensor unavailable") }
        val provider = QueueProvider(
            listOf(
                StreamEvent.ToolUse("call-1", "weather", mapOf("city" to "Rome")),
                StreamEvent.Done
            ),
            listOf(StreamEvent.Text("The sensor is unavailable."), StreamEvent.Done)
        )
        val repository = repository(
            dao,
            provider,
            ToolRegistry(
                tools = mapOf("weather" to tool),
                topography = listOf(ToolNode.ToolRef("weather"))
            )
        )

        repository.sendMessage("Weather?")

        val toolResult = dao.domainMessages().first { it.role == MessageRole.TOOL }
        assertEquals("Error: sensor unavailable", toolResult.content)
        assertEquals("The sensor is unavailable.", dao.domainMessages().last().content)
    }

    @Test
    fun `concurrent sends are serialized`() = runBlocking {
        val dao = FakeChatMessageDao()
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val provider = object : LlmProvider {
            override val id = "concurrency-test"
            override val displayName = "Concurrency test"

            override fun streamChat(
                messages: List<ChatMessage>,
                tools: List<ToolSpec>,
                systemPrompt: String
            ): Flow<StreamEvent> = flow {
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { maxOf(it, nowActive) }
                try {
                    delay(25)
                    emit(StreamEvent.Text("done"))
                    emit(StreamEvent.Done)
                } finally {
                    active.decrementAndGet()
                }
            }

            override suspend fun testConnection() = Result.success(Unit)
            override suspend fun listModels() = Result.success(emptyList<String>())
        }
        val repository = repository(dao, provider)

        val first = async { repository.sendMessage("first") }
        val second = async { repository.sendMessage("second") }
        first.await()
        second.await()

        assertEquals(1, maximumActive.get())
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            dao.domainMessages().map { it.role }
        )
    }

    @Test
    fun `tool loop stops after ten model iterations`() = runBlocking {
        val dao = FakeChatMessageDao()
        val tool = RecordingTool { ToolResult(success = true, data = "again") }
        val calls = AtomicInteger(0)
        val provider = object : LlmProvider {
            override val id = "loop"
            override val displayName = "Loop"

            override fun streamChat(
                messages: List<ChatMessage>,
                tools: List<ToolSpec>,
                systemPrompt: String
            ): Flow<StreamEvent> = flow {
                val call = calls.incrementAndGet()
                emit(StreamEvent.ToolUse("call-$call", "weather", mapOf("city" to "Rome")))
                emit(StreamEvent.Done)
            }

            override suspend fun testConnection() = Result.success(Unit)
            override suspend fun listModels() = Result.success(emptyList<String>())
        }
        val repository = repository(
            dao,
            provider,
            ToolRegistry(
                tools = mapOf("weather" to tool),
                topography = listOf(ToolNode.ToolRef("weather"))
            )
        )

        repository.sendMessage("Keep going")

        assertEquals(10, calls.get())
        assertEquals("Maximum tool iterations reached", dao.domainMessages().last().content)
        assertEquals(10, dao.domainMessages().count { it.role == MessageRole.TOOL })
    }

    private fun repository(
        dao: FakeChatMessageDao,
        provider: LlmProvider,
        registry: ToolRegistry = ToolRegistry(emptyMap(), emptyList()),
        authErrorHandler: AuthErrorHandler = AuthErrorHandler.NoOp
    ) = ChatRepositoryImpl(
        chatMessageDao = dao,
        llmProvider = provider,
        toolRegistry = registry,
        systemPromptBuilder = SystemPromptBuilder { _, _ -> "system" },
        stringProvider = object : StringProvider {
            override fun getString(resId: Int): String = "Maximum tool iterations reached"
        },
        languagePreferences = object : LanguagePreferences {
            override fun getLanguage(): Language = Language.ENGLISH
            override fun setLanguage(language: Language?) = Unit
        },
        authErrorHandler = authErrorHandler
    )

    private class RecordingTool(
        name: String = "weather",
        private val action: suspend (Map<String, Any?>) -> ToolResult
    ) : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "Get weather",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "city" to mapOf("type" to "string")
                ),
                "required" to listOf("city")
            )
        )

        override suspend fun execute(input: Map<String, Any?>): ToolResult = action(input)
    }

    private class QueueProvider(vararg responses: List<StreamEvent>) : LlmProvider {
        private val responses = ArrayDeque(responses.toList())
        var calls: Int = 0
            private set

        override val id = "queue"
        override val displayName = "Queue"

        override fun streamChat(
            messages: List<ChatMessage>,
            tools: List<ToolSpec>,
            systemPrompt: String
        ): Flow<StreamEvent> = flow {
            calls++
            val response = responses.removeFirstOrNull()
                ?: error("No queued provider response for call $calls")
            response.forEach { emit(it) }
        }

        override suspend fun testConnection() = Result.success(Unit)
        override suspend fun listModels() = Result.success(emptyList<String>())
    }

    private class FakeChatMessageDao : ChatMessageDao {
        private val state = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

        override fun observeAll(): Flow<List<ChatMessageEntity>> = state
        override suspend fun getAll(): List<ChatMessageEntity> = state.value
        override suspend fun insert(message: ChatMessageEntity) {
            state.value = (state.value.filterNot { it.id == message.id } + message)
                .sortedBy { it.timestamp }
        }

        override suspend fun insertAll(messages: List<ChatMessageEntity>) {
            messages.forEach { insert(it) }
        }

        override suspend fun deleteAll() {
            state.value = emptyList()
        }

        override suspend fun deleteAfterTimestamp(timestamp: Long) {
            state.value = state.value.filter { it.timestamp <= timestamp }
        }

        override suspend fun getById(id: String): ChatMessageEntity? =
            state.value.firstOrNull { it.id == id }

        override suspend fun count(): Int = state.value.size

        fun domainMessages(): List<ChatMessage> = state.value.map { it.toDomain() }
    }
}
