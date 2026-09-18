package com.lelloman.simpleaiassistant.engine

import com.lelloman.simpleaiassistant.llm.LlmProvider
import com.lelloman.simpleaiassistant.mode.AssistantMode
import com.lelloman.simpleaiassistant.model.*
import com.lelloman.simpleaiassistant.tool.*
import com.lelloman.simpleaiassistant.util.AuthErrorHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** An opaque versioned archive. Use a different store for each account's history. */
interface AssistantHistoryStore {
    suspend fun load(): String?
    suspend fun save(archive: String)
}
class MemoryHistoryStore : AssistantHistoryStore {
    private var archive: String? = null
    override suspend fun load(): String? = archive
    override suspend fun save(archive: String) { this.archive = archive }
}
data class AssistantConfig(
    val rootMode: AssistantMode,
    val tools: List<ToolSpec>,
    val basePrompt: String = "",
    val maxToolRounds: Int = 10,
    val keepRecentTokens: Int = 4000,
    val summaryThresholdTokens: Int = 4000,
)
data class RestartChoice(val messageId: String, val historicalModeName: String?, val revision: Long)
data class AssistantState(
    val messages: List<ChatMessage> = emptyList(),
    val modeId: String = "",
    val modePath: List<String> = emptyList(),
    val language: String? = null,
    val activity: String = "idle",
    val streamingText: String = "",
    val error: String? = null,
    val restartChoice: RestartChoice? = null,
)

/** Owns one native engine and serializes commands and durable writes. */
class AssistantSession private constructor(
    private val handle: Long,
    private val provider: LlmProvider,
    private val executeTool: suspend (ToolCall) -> ToolResult,
    private val history: AssistantHistoryStore,
    parentScope: CoroutineScope,
    private val authErrorHandler: AuthErrorHandler,
    diagnostics: com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder?,
) {
    private val diagnostics = diagnostics?.let { SessionDiagnostics(it, provider.id) }
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = mutableState.asStateFlow()
    private var closed = false
    private var storageFailure: Exception? = null

    companion object {
        suspend fun create(
            config: AssistantConfig,
            provider: LlmProvider,
            executeTool: suspend (ToolCall) -> ToolResult,
            history: AssistantHistoryStore = MemoryHistoryStore(),
            scope: CoroutineScope,
            authErrorHandler: AuthErrorHandler = AuthErrorHandler.NoOp,
            diagnostics: com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder? = null,
        ): AssistantSession {
            val archive = history.load().orEmpty()
            val handle = NativeEngine.create(config.toJson().toString(), UUID.randomUUID().toString(), archive)
            val session = AssistantSession(handle, provider, executeTool, history, scope, authErrorHandler, diagnostics)
            try { session.apply(buildJsonObject { put("type", "snapshot") }, forceSave = true) }
            catch (e: Exception) { NativeEngine.destroy(handle); session.job.cancel(); throw e }
            return session
        }
    }
    suspend fun send(text: String) = apply(buildJsonObject { put("type", "send"); put("text", text); put("timestamp", System.currentTimeMillis()) })
    suspend fun cancel() = apply(command("cancel"))
    suspend fun clear() = apply(command("clear"))
    suspend fun switchMode(id: String) = apply(buildJsonObject { put("type", "switch_mode"); put("modeId", id) })
    suspend fun setLanguage(code: String?) = apply(buildJsonObject { put("type", "set_language"); put("language", code?.let(::JsonPrimitive) ?: JsonNull) })
    suspend fun restart(messageId: String) = apply(buildJsonObject { put("type", "restart"); put("messageId", messageId) })
    suspend fun confirmRestart(modeId: String, revision: Long) = apply(buildJsonObject { put("type", "confirm_restart"); put("modeId", modeId); put("revision", revision) })
    suspend fun dismissRestart() = apply(command("dismiss_restart"))
    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                diagnostics?.close()
                requests.values.forEach { it.cancel() }; requests.clear()
                val output = Json.parseToJsonElement(NativeEngine.dispatch(handle, command("cancel").toString())).jsonObject
                try { if (storageFailure == null) history.save(output.getValue("state").toString()) }
                finally { closed = true; NativeEngine.destroy(handle); job.cancel() }
            }
        }
    }
    private suspend fun apply(command: JsonObject, forceSave: Boolean = false) = withContext(NonCancellable) {
        mutex.withLock {
            check(!closed) { "Assistant session is closed" }
            storageFailure?.let { throw it }
            val output = Json.parseToJsonElement(NativeEngine.dispatch(handle, command.toString())).jsonObject
            val rawState = output.getValue("state").jsonObject
            val effects = output.getValue("effects").jsonArray
            effects.filter { it.jsonObject.text("type") == "cancel" }.forEach { requests.remove(it.jsonObject.text("requestId"))?.cancel() }
            try {
                if (forceSave || output.getValue("persist").jsonPrimitive.boolean) history.save(rawState.toString())
            } catch (e: Exception) {
                storageFailure = e; requests.values.forEach { it.cancel() }; requests.clear()
                NativeEngine.dispatch(handle, command("cancel").toString())
                mutableState.value = parseState(rawState).copy(activity = "idle", streamingText = "", error = "History could not be saved: ${e.message}")
                throw e
            }
            val nextState = parseState(rawState)
            diagnostics?.record(command, mutableState.value, nextState)
            mutableState.value = nextState
            effects.filter { it.jsonObject.text("type") != "cancel" }.forEach { element ->
                val effect = element.jsonObject; val id = effect.text("requestId")
                val child = scope.launch(start = CoroutineStart.LAZY) { runEffect(effect) }
                requests[id] = child; child.start()
            }
        }
    }
    private suspend fun runEffect(effect: JsonObject) {
        val id = effect.text("requestId")
        try {
            currentCoroutineContext().ensureActive()
            if (effect.text("type") == "provider") {
                val messages = effect.getValue("messages").jsonArray.map { parseMessage(it.jsonObject) }
                val tools = effect.getValue("tools").jsonArray.map { val t=it.jsonObject; ToolSpec(t.text("name"), t.text("description"), t.getValue("inputSchema").jsonObject.toAnyMap()) }
                if (effect.text("purpose") == "detect") {
                    val detected = provider.detectLanguage(messages.last().content)
                    if (detected != null) { providerEvent(id, buildJsonObject { put("type", "text"); put("content", detected) }); providerEvent(id, command("done")); return }
                }
                var terminal = false
                var emitted = false
                var retried = false
                while (true) {
                    var retry = false
                    provider.streamChat(messages, tools, effect.text("systemPrompt")).takeWhile { !terminal && !retry }.collect { event ->
                        currentCoroutineContext().ensureActive()
                        if (event is StreamEvent.Error && !emitted && !retried && event.message.contains("Authentication failed") && authErrorHandler.onAuthError(event.message)) { retry = true }
                        else {
                            terminal = event is StreamEvent.Done || event is StreamEvent.Error
                            if (event is StreamEvent.Text || event is StreamEvent.ToolUse) emitted = true
                            providerEvent(id, event.toJson())
                        }
                    }
                    if (!retry) break
                    retried = true
                }
                if (!terminal) providerEvent(id, buildJsonObject { put("type", "error"); put("message", "Provider stream ended without completion") })
            } else {
                val c = effect.getValue("call").jsonObject
                val call = ToolCall(c.text("id"), c.text("name"), c.getValue("input").jsonObject.toAnyMap())
                currentCoroutineContext().ensureActive()
                val result = executeTool(call)
                currentCoroutineContext().ensureActive()
                apply(buildJsonObject { put("type", "tool_result"); put("requestId", id); put("result", if (result.success) anyJson(result.data) else buildJsonObject { put("error", result.error ?: "Tool failed") }) })
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (effect.text("type") == "provider") providerEvent(id, buildJsonObject { put("type", "error"); put("message", e.message ?: "Provider failed") })
            else apply(buildJsonObject { put("type", "tool_result"); put("requestId", id); put("result", buildJsonObject { put("error", e.message ?: "Tool failed") }) })
        } finally {
            withContext(NonCancellable) { mutex.withLock { requests.remove(id) } }
        }
    }
    private suspend fun providerEvent(id: String, event: JsonObject) {
        currentCoroutineContext().ensureActive()
        apply(buildJsonObject { put("type", "provider_event"); put("requestId", id); put("event", event) })
    }
}
private fun command(type: String) = buildJsonObject { put("type", type) }
internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
internal fun anyJson(value: Any?): JsonElement = when(value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to anyJson(it.value) })
    is Iterable<*> -> JsonArray(value.map(::anyJson))
    else -> error("Unsupported JSON value: ${value::class}")
}
private fun JsonElement.toAny(): Any? = when(this) {
    JsonNull -> null
    is JsonObject -> toAnyMap()
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> if(isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}
internal fun JsonObject.toAnyMap(): Map<String, Any?> = mapValues { it.value.toAny() }
internal fun parseMessage(m: JsonObject) = ChatMessage(
    id=m.text("id"), role=MessageRole.valueOf(m.text("role").uppercase()), content=m.text("content"),
    timestamp=m["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
    toolCalls=m["toolCalls"]?.jsonArray?.map { val t=it.jsonObject; ToolCall(t.text("id"), t.text("name"), t.getValue("input").jsonObject.toAnyMap()) },
    toolCallId=m["toolCallId"]?.jsonPrimitive?.contentOrNull, toolName=m["toolName"]?.jsonPrimitive?.contentOrNull,
)
private fun parseState(s: JsonObject) = AssistantState(
    messages=s.getValue("messages").jsonArray.map { parseMessage(it.jsonObject) }, modeId=s.text("modeId"),
    modePath=s.getValue("modePath").jsonArray.map { it.jsonPrimitive.content }, language=s["language"]?.jsonPrimitive?.contentOrNull,
    activity=s.text("activity"), streamingText=s.text("streamingText"), error=s["error"]?.jsonPrimitive?.contentOrNull,
    restartChoice=(s["restartChoice"] as? JsonObject)?.let { RestartChoice(it.text("messageId"), (it["historicalMode"] as? JsonObject)?.text("name"), it.getValue("revision").jsonPrimitive.long) },
)
private fun StreamEvent.toJson() = buildJsonObject {
    when(val event=this@toJson) {
        is StreamEvent.Text -> { put("type", "text"); put("content", event.content) }
        is StreamEvent.ToolUse -> { put("type", "tool_use"); put("id", event.id); put("name", event.name); put("input", anyJson(event.input)) }
        is StreamEvent.Error -> { put("type", "error"); put("message", event.message) }
        StreamEvent.Done -> put("type", "done")
    }
}
private fun AssistantMode.toJson(): JsonObject = buildJsonObject {
    require(!inheritAllAncestorPrompts || inheritPromptsFrom.isEmpty()) { "Choose all ancestors or specific ancestors" }
    put("id", id); put("name", name); put("description", description); put("prompt", promptInstructions)
    put("toolIds", anyJson(toolIds)); put("revision", revision)
    put("inheritPrompts", if(inheritAllAncestorPrompts) JsonPrimitive("all") else anyJson(inheritPromptsFrom))
    put("children", JsonArray(children.map { it.toJson() }))
}
private fun AssistantConfig.toJson() = buildJsonObject {
    put("basePrompt", basePrompt); put("rootMode", rootMode.toJson()); put("maxToolRounds", maxToolRounds)
    put("keepRecentTokens", keepRecentTokens); put("summaryThresholdTokens", summaryThresholdTokens)
    put("tools", JsonArray(tools.map { t -> buildJsonObject { put("name", t.name); put("description", t.description); put("inputSchema", anyJson(t.inputSchema)) } }))
}
