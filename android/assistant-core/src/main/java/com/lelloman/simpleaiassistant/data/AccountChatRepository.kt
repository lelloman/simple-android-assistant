package com.lelloman.simpleaiassistant.data

import com.lelloman.simpleaiassistant.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single-account local history. Unknown legacy ownership is deliberately cleared. */
class AccountChatRepository(
    private val delegate: ChatRepository,
    accountChanges: Flow<String?>,
    private val readOwner: () -> String?,
    private val writeOwner: (String?) -> Unit,
    scope: CoroutineScope,
    private val diagnostics: com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder? = null,
    private val diagnosticsEnabled: () -> Boolean = { false },
) : ChatRepository by delegate {
    private val account = MutableStateFlow<String?>(null)
    private val resolved = MutableStateFlow(false)
    private val ending = MutableStateFlow(false)
    private val readyOwner = MutableStateFlow<String?>(null)
    private val transitions = Mutex()
    private val failure = MutableStateFlow<Exception?>(null)

    override val streamingText = combine(delegate.streamingText, readyOwner, account, ending) { text, ready, current, stopped ->
        if (!stopped && current != null && current == ready) text else ""
    }.stateIn(scope, SharingStarted.Eagerly, "")

    override val isStreaming = combine(delegate.isStreaming, readyOwner, account, ending) { streaming, ready, current, stopped ->
        !stopped && current != null && current == ready && streaming
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val error = combine(delegate.error, readyOwner, account, ending) { error, ready, current, stopped ->
        if (!stopped && current != null && current == ready) error else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val restartChoice = combine(delegate.restartChoice, readyOwner, account, ending) { choice, ready, current, stopped ->
        if (!stopped && current != null && current == ready) choice else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val messages: Flow<List<ChatMessage>> = combine(
        delegate.messages, readyOwner, account, ending,
    ) { messages, ready, current, stopped ->
        if (!stopped && current != null && current == ready) messages else emptyList()
    }

    init {
        scope.launch {
            accountChanges.collectLatest { current ->
                account.value = current
                resolved.value = true
                if (current == null) ending.value = false
                readyOwner.value = null
                failure.value = null
                try {
                    transitions.withLock {
                        diagnostics?.setAccount(current, diagnosticsEnabled())
                        if (current == null || readOwner() != current) {
                            delegate.clearHistory()
                            delegate.setLanguage(null)
                            writeOwner(current)
                        }
                        readyOwner.value = current
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure.value = error
                }
            }
        }
    }

    private suspend fun requireAccount() {
        resolved.first { it }
        check(!ending.value) { "Assistant session ended" }
        val expected = checkNotNull(account.value) { "Sign in to use the assistant" }
        combine(readyOwner, account, ending, failure) { ready, current, stopped, error ->
            ready == expected || current != expected || stopped || error != null
        }.first { it }
        failure.value?.let { throw it }
        check(!ending.value && account.value == expected && readyOwner.value == expected) { "Assistant account changed" }
    }

    override suspend fun sendMessage(text: String) {
        requireAccount()
        delegate.sendMessage(text)
    }

    override suspend fun restartFromMessage(messageId: String) {
        requireAccount()
        delegate.restartFromMessage(messageId)
    }

    override suspend fun switchMode(modeId: String): Boolean {
        requireAccount()
        return delegate.switchMode(modeId)
    }

    override suspend fun confirmRestart(modeId: String, revision: Long) {
        requireAccount()
        delegate.confirmRestart(modeId, revision)
    }

    /** Called before host logout changes credentials or clears its data stores. */
    suspend fun endSession() {
        ending.value = true
        readyOwner.value = null
        transitions.withLock {
            diagnostics?.setAccount(null, false)
            delegate.clearHistory()
            delegate.setLanguage(null)
            writeOwner(null)
        }
    }
}
