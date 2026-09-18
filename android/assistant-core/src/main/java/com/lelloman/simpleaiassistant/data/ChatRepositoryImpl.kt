package com.lelloman.simpleaiassistant.data

import com.lelloman.simpleaiassistant.engine.*
import com.lelloman.simpleaiassistant.llm.LlmProvider
import com.lelloman.simpleaiassistant.mode.*
import com.lelloman.simpleaiassistant.model.*
import com.lelloman.simpleaiassistant.tool.*
import com.lelloman.simpleaiassistant.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

/** Android presentation adapter. Conversation policy is implemented exclusively in Rust. */
class ChatRepositoryImpl(
    historyStore: AssistantHistoryStore,
    llmProvider: LlmProvider,
    toolRegistry: ToolRegistry,
    systemPromptBuilder: SystemPromptBuilder,
    private val languagePreferences: LanguagePreferences,
    scope: CoroutineScope,
    private val modeManager: ModeManager? = null,
    authErrorHandler: AuthErrorHandler = AuthErrorHandler.NoOp,
    diagnostics: com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder? = null,
) : ChatRepository {
    private val epoch = AtomicLong()
    private val state = MutableStateFlow(AssistantState())
    private val modes = modeManager?.getAllModes().orEmpty()
    private val session = scope.async {
        val root = modeManager?.getRootMode() ?: AssistantMode("default", "Assistant", "", toolIds = toolRegistry.getAllSpecs().map { it.name }.toSet())
        AssistantSession.create(
            config = AssistantConfig(root, toolRegistry.getAllSpecs(), systemPromptBuilder.build(null, toolRegistry)),
            provider = llmProvider,
            executeTool = { call -> toolRegistry.findByName(call.name)?.execute(call.input) ?: ToolResult(false, error = "Unknown tool") },
            history = historyStore, scope = scope, authErrorHandler = authErrorHandler, diagnostics = diagnostics,
        ).also { engine ->
            if (engine.state.value.messages.isEmpty()) languagePreferences.getLanguage()?.let { engine.setLanguage(it.code) }
            scope.launch {
                engine.state.collect {
                    state.value = it
                    languagePreferences.setLanguage(it.language?.let(Language::fromCode))
                    if (it.modeId != modeManager?.currentMode?.value?.id) modeManager?.switchToMode(it.modeId)
                }
            }
        }
    }
    override val messages: Flow<List<ChatMessage>> = state.map { it.messages }
    override val currentMode: StateFlow<AssistantMode?> = state.map { s -> modes.find { it.id == s.modeId } }.stateIn(scope, SharingStarted.Eagerly, modeManager?.currentMode?.value)
    override val streamingText = state.map { it.streamingText }.stateIn(scope, SharingStarted.Eagerly, "")
    override val isStreaming = state.map { it.activity != "idle" }.stateIn(scope, SharingStarted.Eagerly, false)
    override val language = state.map { it.language?.let(Language::fromCode) }.stateIn(scope, SharingStarted.Eagerly, languagePreferences.getLanguage())
    override val isDetectingLanguage = state.map { it.activity == "detect" }.stateIn(scope, SharingStarted.Eagerly, false)
    override val error = state.map { it.error }.stateIn(scope, SharingStarted.Eagerly, null)
    override val restartChoice = state.map { it.restartChoice }.stateIn(scope, SharingStarted.Eagerly, null)
    override suspend fun sendMessage(text: String) {
        val started = epoch.get(); val engine = session.await()
        if (started == epoch.get()) engine.send(text)
    }
    override suspend fun setLanguage(language: Language?) { session.await().setLanguage(language?.code) }
    override suspend fun clearHistory() { epoch.incrementAndGet(); session.await().clear() }
    override suspend fun cancel() { epoch.incrementAndGet(); session.await().cancel() }
    override suspend fun restartFromMessage(messageId: String) { val started=epoch.get(); val engine=session.await(); if(started==epoch.get()) engine.restart(messageId) }
    override suspend fun switchMode(modeId: String): Boolean {
        if (modeId !in modes.map { it.id }) return false
        session.await().switchMode(modeId); return true
    }
    override suspend fun confirmRestart(modeId: String, revision: Long) { session.await().confirmRestart(modeId, revision) }
    override suspend fun dismissRestart() { session.await().dismissRestart() }
    suspend fun close() { epoch.incrementAndGet(); session.await().close() }
}

fun interface SystemPromptBuilder {
    fun build(language: Language?, toolRegistry: ToolRegistry): String
}
